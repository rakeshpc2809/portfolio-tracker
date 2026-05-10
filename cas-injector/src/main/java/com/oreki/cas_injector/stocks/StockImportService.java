package com.oreki.cas_injector.stocks;

import com.oreki.cas_injector.core.model.Folio;
import com.oreki.cas_injector.core.repository.FolioRepository;
import com.oreki.cas_injector.stocks.model.Stock;
import com.oreki.cas_injector.stocks.model.StockTransaction;
import com.oreki.cas_injector.stocks.model.StockTaxLot;
import com.oreki.cas_injector.stocks.repository.StockRepository;
import com.oreki.cas_injector.stocks.repository.StockTransactionRepository;
import com.oreki.cas_injector.stocks.repository.StockTaxLotRepository;
import com.oreki.cas_injector.stocks.repository.StockCapitalGainRepository;
import com.oreki.cas_injector.dashboard.repository.PortfolioDashboardReadModelRepository;
import org.springframework.cache.CacheManager;
import org.springframework.cache.Cache;
import lombok.RequiredArgsConstructor;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class StockImportService {
    private static final Logger log = LoggerFactory.getLogger(StockImportService.class);

    private final StockRepository stockRepo;
    private final StockTransactionRepository txnRepo;
    private final StockTaxLotRepository lotRepo;
    private final StockCapitalGainRepository gainRepo;
    private final FolioRepository folioRepo;
    private final StockTaxLotService taxLotService;
    private final PortfolioDashboardReadModelRepository summaryRepo;
    private final CacheManager cacheManager;

    @Transactional
    public void purgeStockData(String pan) {
        log.info("🗑️ Purging all stock data for PAN: {}", pan);
        Folio folio = getFolio(pan);
        List<Stock> stocks = stockRepo.findByFolioInvestorPan(pan);
        for (Stock stock : stocks) {
            gainRepo.deleteByStock(stock);
            lotRepo.deleteByStock(stock);
            txnRepo.deleteByStock(stock);
            stockRepo.delete(stock);
        }
    }

    @Transactional
    public int importFromCsv(byte[] content, String pan, String source) throws Exception {
        log.info("🚀 Starting Stock CSV Ingestion for PAN: {}", pan);
        
        byte[] finalContent = content;
        if (content.length > 4 && content[0] == 'P' && content[1] == 'K') {
            log.info("📦 Detected XLSX format. Converting to CSV...");
            finalContent = convertXlsxToCsv(content);
        }

        String contentStr = new String(finalContent, StandardCharsets.UTF_8);
        String first1000 = contentStr.substring(0, Math.min(1000, contentStr.length()));
        log.info("📄 File snippet: {}", first1000.replace("\n", " [NL] "));

        int importedCount;
        if (first1000.contains("Central Depository Services (India) Limited")) {
            importedCount = importFromCdslStatement(contentStr, pan);
        } else if (first1000.contains("Execution Date") || first1000.contains("Trade Date") || 
                   first1000.toLowerCase().contains("execution date") || first1000.toLowerCase().contains("trade date")) {
            importedCount = importFromIndMoneyTransactions(finalContent, pan);
        } else {
            log.info("📊 Falling back to legacy summary parser");
            importedCount = importFromIndMoneyCsv(finalContent, pan, source);
        }

        // --- CLEAR CACHES ---
        log.info("🧹 Clearing dashboard read model and caches for PAN: {}", pan);
        summaryRepo.deleteById(pan);
        Cache pCache = cacheManager.getCache("portfolioCache");
        if (pCache != null) pCache.evict(pan);
        Cache dCache = cacheManager.getCache("dashboardSummaryV3");
        if (dCache != null) dCache.evict(pan);

        return importedCount;
    }

    private byte[] convertXlsxToCsv(byte[] xlsxContent) throws Exception {
        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(xlsxContent))) {
            Sheet sheet = workbook.getSheetAt(0);
            StringBuilder csv = new StringBuilder();
            DataFormatter formatter = new DataFormatter();

            for (Row row : sheet) {
                boolean firstCell = true;
                for (Cell cell : row) {
                    if (!firstCell) csv.append(",");
                    String val = formatter.formatCellValue(cell);
                    if (val.contains(",")) {
                        csv.append("\"").append(val).append("\"");
                    } else {
                        csv.append(val);
                    }
                    firstCell = false;
                }
                csv.append("\n");
            }
            return csv.toString().getBytes(StandardCharsets.UTF_8);
        }
    }

    private int importFromCdslStatement(String content, String pan) {
        log.info("🔍 Detected CDSL Statement format. Parsing holdings...");
        Folio folio = getFolio(pan);
        int count = 0;
        
        boolean inHoldings = false;
        String[] lines = content.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.contains("STATEMENT OF HOLDINGS AS ON")) {
                inHoldings = true;
                continue;
            }
            if (inHoldings && line.contains("\"ISIN\"")) continue; // Header
            if (inHoldings && line.contains("End of Statement")) break;
            
            if (inHoldings && !line.isEmpty() && line.contains(",")) {
                try {
                    // Format: "ISIN","ISIN DESCRIPTION","CURRENT BALANCE FREE BALANCE","PLEDGE BALANCE LOCKIN BALANCE","CLOSING PRICE","VALUE"
                    // Split while respecting quotes
                    String[] parts = line.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)");
                    if (parts.length < 6) continue;

                    String isin = parts[0].replace("\"", "");
                    String description = parts[1].replace("\"", "");
                    String qtyStr = parts[2].replace("\"", "").split(" ")[0]; // Take first part of "210 210"
                    BigDecimal quantity = new BigDecimal(qtyStr);
                    BigDecimal closePrice = new BigDecimal(parts[4].replace("\"", ""));
                    
                    if (quantity.compareTo(BigDecimal.ZERO) <= 0) continue;

                    // Extract a ticker-like name from description
                    String ticker = isin;
                    if (description.contains("-")) {
                        ticker = description.split("-")[description.split("-").length - 1].trim();
                    }

                    Stock stock = upsertStock(isin, ticker, description, folio);
                    createSyntheticLot(stock, quantity, closePrice, "CDSL_STATEMENT");
                    count++;
                } catch (Exception e) {
                    log.error("Failed to parse CDSL row: {} - {}", line, e.getMessage());
                }
            }
        }
        return count;
    }

    private int importFromIndMoneyTransactions(byte[] content, String pan) throws Exception {
        log.info("📥 Parsing INDmoney Transaction Report for PAN: {}", pan);
        Folio folio = getFolio(pan);
        int count = 0;
        java.util.Set<Stock> stocksToRebuild = new java.util.HashSet<>();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(content), StandardCharsets.UTF_8))) {
            String line;
            StringBuilder csvContent = new StringBuilder();
            boolean headerFound = false;
            
            while ((line = reader.readLine()) != null) {
                if (!headerFound) {
                    if (line.contains("Execution Date") || line.contains("Trade Date")) {
                        headerFound = true;
                        csvContent.append(line).append("\n");
                    }
                    continue;
                }
                csvContent.append(line).append("\n");
            }

            if (!headerFound) {
                log.error("❌ Could not find header row (Execution/Trade Date) in the file!");
                throw new RuntimeException("Could not find header row in INDmoney CSV. Ensure the file contains 'Execution Date' or 'Trade Date' columns.");
            }

            log.info("✅ Header found. Parsing with CSV parser...");
            CSVParser parser = CSVFormat.DEFAULT.builder()
                    .setHeader()
                    .setSkipHeaderRecord(true)
                    .setIgnoreHeaderCase(true)
                    .setTrim(true)
                    .setIgnoreEmptyLines(true)
                    .build()
                    .parse(new java.io.StringReader(csvContent.toString()));
            for (CSVRecord record : parser) {
                try {
                    String isin = record.get("ISIN");
                    String symbol = getAny(record, "Scrip Symbol", "Symbol", "Ticker");
                    String type = getAny(record, "Type", "Transaction Type").toUpperCase();
                    String dateStr = getAny(record, "Execution Date", "Trade Date");
                    BigDecimal quantity = new BigDecimal(record.get("Quantity").replace(",", ""));
                    BigDecimal price = new BigDecimal(record.get("Price").replace(",", ""));
                    
                    if (quantity.compareTo(BigDecimal.ZERO) <= 0) continue;
                    if (!type.contains("BUY") && !type.contains("SELL")) continue;

                    LocalDate date = parseDate(dateStr);

                    Stock stock = upsertStock(isin, symbol, record.isMapped("Scrip Name") ? record.get("Scrip Name") : symbol, folio);
                    
                    txnRepo.save(StockTransaction.builder()
                            .stock(stock)
                            .transactionDate(date)
                            .transactionType(type.contains("BUY") ? "BUY" : "SELL")
                            .quantity(quantity)
                            .pricePerShare(price)
                            .totalAmount(quantity.multiply(price))
                            .source("INDMONEY_CSV")
                            .build());
                    
                    stocksToRebuild.add(stock);
                    count++;
                } catch (Exception e) {
                    log.error("⚠️ Failed to process transaction row: {}. Error: {}", record.toString(), e.getMessage());
                }
            }
        }

        log.info("🔄 Rebuilding lots for {} affected stocks", stocksToRebuild.size());
        for (Stock stock : stocksToRebuild) {
            taxLotService.rebuildLotsForStock(stock);
        }
        
        return count;
    }

    private String getAny(CSVRecord record, String... keys) {
        for (String key : keys) {
            if (record.isMapped(key)) return record.get(key);
        }
        throw new RuntimeException("Missing required column. Tried: " + String.join(", ", keys));
    }

    private LocalDate parseDate(String dateStr) {
        if (dateStr == null || dateStr.isEmpty()) return LocalDate.now();
        String clean = dateStr.split(" ")[0].trim(); // Remove time if present
        
        // Try ISO (2024-01-15)
        try { return LocalDate.parse(clean); } catch (Exception ignored) {}
        
        // Try DD-MMM-YYYY (15-Jan-2024)
        try { return LocalDate.parse(clean, java.time.format.DateTimeFormatter.ofPattern("dd-MMM-yyyy", java.util.Locale.ENGLISH)); } catch (Exception ignored) {}
        
        // Try DD-MM-YYYY (15-01-2024)
        try { return LocalDate.parse(clean, java.time.format.DateTimeFormatter.ofPattern("dd-MM-yyyy")); } catch (Exception ignored) {}

        // Try M/d/yy (3/17/25)
        try { return LocalDate.parse(clean, java.time.format.DateTimeFormatter.ofPattern("M/d/yy")); } catch (Exception ignored) {}

        // Try M/d/yyyy (3/17/2025)
        try { return LocalDate.parse(clean, java.time.format.DateTimeFormatter.ofPattern("M/d/yyyy")); } catch (Exception ignored) {}

        // Try d/M/yy (17/3/25)
        try { return LocalDate.parse(clean, java.time.format.DateTimeFormatter.ofPattern("d/M/yy")); } catch (Exception ignored) {}

        // Try d/M/yyyy (17/3/2025)
        try { return LocalDate.parse(clean, java.time.format.DateTimeFormatter.ofPattern("d/M/yyyy")); } catch (Exception ignored) {}

        log.warn("Could not parse date: {}, using current date", dateStr);
        return LocalDate.now();
    }

    private int importFromIndMoneyCsv(byte[] content, String pan, String source) throws Exception {
        Folio folio = getFolio(pan);
        int count = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(content), StandardCharsets.UTF_8))) {
            CSVParser parser = CSVFormat.DEFAULT.withFirstRecordAsHeader().parse(reader);
            for (CSVRecord record : parser) {
                try {
                    String isin = record.get("ISIN");
                    String symbol = record.isMapped("Symbol") ? record.get("Symbol") : record.isMapped("Ticker") ? record.get("Ticker") : isin;
                    String companyName = record.isMapped("Company Name") ? record.get("Company Name") : symbol;
                    BigDecimal quantity = new BigDecimal(record.get("Quantity").replace(",", ""));
                    BigDecimal avgPrice = new BigDecimal(record.get("Average Price").replace(",", ""));
                    
                    if (quantity.compareTo(BigDecimal.ZERO) <= 0) continue;

                    Stock stock = upsertStock(isin, symbol, companyName, folio);
                    createSyntheticLot(stock, quantity, avgPrice, source);
                    count++;
                } catch (Exception e) {
                    log.error("Failed to process INDmoney row: {}", e.getMessage());
                }
            }
        }
        return count;
    }

    private Folio getFolio(String pan) {
        return folioRepo.findAll().stream()
                .filter(f -> f.getInvestor().getPan().equals(pan))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("No folio found for investor: " + pan));
    }

    private Stock upsertStock(String isin, String ticker, String companyName, Folio folio) {
        return stockRepo.findByIsin(isin).orElseGet(() -> {
            return stockRepo.save(Stock.builder()
                    .isin(isin)
                    .ticker(ticker)
                    .tradingSymbol(ticker)
                    .companyName(companyName)
                    .exchange("NSE")
                    .folio(folio)
                    .build());
        });
    }

    private void createSyntheticLot(Stock stock, BigDecimal quantity, BigDecimal price, String source) {
        // Create Synthetic BUY Transaction
        StockTransaction txn = txnRepo.save(StockTransaction.builder()
                .stock(stock)
                .transactionDate(LocalDate.now())
                .transactionType("BUY")
                .quantity(quantity)
                .pricePerShare(price)
                .totalAmount(quantity.multiply(price))
                .source(source)
                .notes("Initial snapshot import")
                .build());

        // Create Initial Tax Lot
        lotRepo.save(StockTaxLot.builder()
                .stock(stock)
                .buyTransaction(txn)
                .buyDate(LocalDate.now())
                .originalQty(quantity)
                .remainingQty(quantity)
                .costBasisPerShare(price)
                .status("OPEN")
                .build());
    }
}
