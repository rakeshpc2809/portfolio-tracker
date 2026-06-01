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
import com.oreki.cas_injector.stocks.dto.NormalizedTransactionDTO;
import org.springframework.cache.CacheManager;
import org.springframework.cache.Cache;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.LocalDate;
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
        List<Stock> stocks = stockRepo.findByFolioInvestorPan(pan);
        for (Stock stock : stocks) {
            gainRepo.deleteByStock(stock);
            lotRepo.deleteByStock(stock);
            txnRepo.deleteByStock(stock);
            stockRepo.delete(stock);
        }
    }

    @Transactional
    public int importNormalizedTransactions(String pan, List<NormalizedTransactionDTO> txns) {
        log.info("🚀 Ingesting {} normalized transactions for PAN: {}", txns.size(), pan);
        Folio folio = getFolio(pan);
        int count = 0;
        java.util.Set<Stock> stocksToRebuild = new java.util.HashSet<>();

        for (NormalizedTransactionDTO dto : txns) {
            try {
                BigDecimal quantity = dto.getQuantity();
                if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) continue;
                
                String type = dto.getTransactionType() != null ? dto.getTransactionType().toUpperCase() : "BUY";

                // Resolve company name or default to ticker
                String companyName = dto.getTicker();
                Stock stock = upsertStock(dto.getIsin(), dto.getTicker(), companyName, folio);

                if ("CDSL_STATEMENT".equalsIgnoreCase(dto.getSource()) || "INDMONEY_SUMMARY".equalsIgnoreCase(dto.getSource())) {
                    // For statement/summary snapshots, create synthetic lot directly
                    createSyntheticLot(stock, quantity, dto.getPricePerShare(), dto.getSource());
                } else {
                    // Save transaction and rebuild lots later
                    txnRepo.save(StockTransaction.builder()
                            .stock(stock)
                            .transactionDate(dto.getTransactionDate() != null ? dto.getTransactionDate() : LocalDate.now())
                            .transactionType(type.contains("BUY") ? "BUY" : "SELL")
                            .quantity(quantity)
                            .pricePerShare(dto.getPricePerShare())
                            .brokerage(dto.getBrokerage() != null ? dto.getBrokerage() : BigDecimal.ZERO)
                            .stt(dto.getStt() != null ? dto.getStt() : BigDecimal.ZERO)
                            .totalAmount(dto.getTotalAmount() != null ? dto.getTotalAmount() : quantity.multiply(dto.getPricePerShare()))
                            .source(dto.getSource() != null ? dto.getSource() : "PYTHON_ETL")
                            .build());
                    stocksToRebuild.add(stock);
                }
                count++;
            } catch (Exception e) {
                log.error("Failed to import normalized transaction for ticker: {} | Error: {}", dto.getTicker(), e.getMessage());
            }
        }

        if (!stocksToRebuild.isEmpty()) {
            log.info("🔄 Rebuilding lots for {} affected stocks", stocksToRebuild.size());
            for (Stock stock : stocksToRebuild) {
                taxLotService.rebuildLotsForStock(stock);
            }
        }

        // --- CLEAR CACHES ---
        log.info("🧹 Clearing dashboard read model and caches for PAN: {}", pan);
        summaryRepo.deleteById(pan);
        Cache pCache = cacheManager.getCache("portfolioCache");
        if (pCache != null) pCache.evict(pan);
        Cache dCache = cacheManager.getCache("dashboardSummaryV3");
        if (dCache != null) dCache.evict(pan);

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
                    .exchange("String")
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
