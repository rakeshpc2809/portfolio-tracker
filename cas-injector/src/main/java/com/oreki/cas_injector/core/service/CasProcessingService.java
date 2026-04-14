package com.oreki.cas_injector.core.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.stereotype.Service;
import org.springframework.cache.CacheManager;

import com.oreki.cas_injector.backfill.service.NavService;
import com.oreki.cas_injector.core.dto.SchemeDetailsDTO;
import com.oreki.cas_injector.core.model.Folio;
import com.oreki.cas_injector.core.model.Investor;
import com.oreki.cas_injector.core.model.Scheme;
import com.oreki.cas_injector.core.repository.FolioRepository;
import com.oreki.cas_injector.core.repository.InvestorRepository;
import com.oreki.cas_injector.core.repository.SchemeRepository;
import com.oreki.cas_injector.core.utils.CommonUtils;
import com.oreki.cas_injector.dashboard.repository.PortfolioSummaryRepository;
import com.oreki.cas_injector.taxmanagement.service.FifoInventoryService;
import com.oreki.cas_injector.transactions.model.Transaction;
import com.oreki.cas_injector.transactions.repository.TransactionRepository;

import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.fasterxml.jackson.databind.JsonNode;

import com.oreki.cas_injector.convictionmetrics.service.ConvictionScoringService;
import com.oreki.cas_injector.convictionmetrics.service.QuantitativeEngineService;
import org.springframework.scheduling.annotation.Async;

@Service
@Slf4j
@RequiredArgsConstructor
public class CasProcessingService {

    private final InvestorRepository investorRepo;
    private final TransactionRepository txnRepo;
    private final SchemeRepository schemeRepo;
    private final FolioRepository folioRepo;
    private final NavService navService;
    private final CacheManager cacheManager;
    private final FifoInventoryService fifoService;
    private final QuantitativeEngineService quantitativeEngineService;
    private final ConvictionScoringService convictionScoringService;
    private final JdbcTemplate jdbcTemplate;
    private final PortfolioSummaryRepository summaryRepo;

    @Transactional
    public void processJson(JsonNode root) {
        String rawPan = root.path("pan").asText();
        String pan = rawPan != null ? rawPan.trim().toUpperCase() : "UNKNOWN";
        
        Investor investor = investorRepo.findById(pan).orElseGet(() -> 
            investorRepo.save(Investor.builder()
                .pan(pan)
                .name(root.path("name").asText())
                .email(root.path("email").asText())
                .build())
        );

        List<Transaction> pendingTransactions = new ArrayList<>();

        root.path("folios").forEach(folioNode -> {
            Folio folio = findOrCreateFolio(folioNode, investor); 

            folioNode.path("schemes").forEach(schemeNode -> {
                Scheme scheme = findOrCreateScheme(schemeNode, folio);
                
                schemeNode.path("transactions").forEach(txNode -> {
                    Transaction tx = prepareTransaction(txNode, scheme);
                    if (tx != null) {
                        pendingTransactions.add(tx);
                    }
                });
            });
        });

        // Batch Insert Transactions
        if (!pendingTransactions.isEmpty()) {
            batchInsertTransactions(pendingTransactions);
            
            // Post-Batch: Apply FIFO logic. 
            // We need to fetch transactions back to get IDs for FIFO linking.
            pendingTransactions.forEach(tx -> {
                txnRepo.findByTxnHash(tx.getTxnHash()).ifPresent(savedTx -> {
                    String category = "UNKNOWN";
                    if ("SELL".equalsIgnoreCase(savedTx.getTransactionType()) || "STAMP_DUTY".equalsIgnoreCase(savedTx.getTransactionType())) {
                         category = navService.getLatestSchemeDetails(savedTx.getScheme().getAmfiCode()).getCategory();
                    }
                    fifoService.applyInventoryRules(savedTx, category);
                });
            });
        }

        // Refresh Materialized View
        summaryRepo.refreshView();

        // 4. Evict caches for this PAN
        if (cacheManager.getCache("portfolioCache") != null) {
            cacheManager.getCache("portfolioCache").evict(pan);
        }
        if (cacheManager.getCache("dashboardSummary") != null) {
            cacheManager.getCache("dashboardSummary").evict(pan);
        }

        // Trigger initial scoring for this investor
        runInitialScoringAsync(pan);
    }

    @Async("mathEngineExecutor")
    public void runInitialScoringAsync(String pan) {
        try {
            log.info("🆕 New investor data loaded for PAN {}. Triggering initial scoring...", pan);
            quantitativeEngineService.runNightlyMathEngine();  // runs global metrics first
            convictionScoringService.calculateAndSaveFinalScores(pan);
            log.info("✅ Initial conviction scoring complete for PAN {}", pan);
        } catch (Exception e) {
            log.warn("⚠️ Initial scoring failed for PAN {} (non-fatal): {}", pan, e.getMessage());
        }
    }

    private Folio findOrCreateFolio(JsonNode node, Investor investor) {
        String folioNum = node.path("folio_number").asText().trim(); 
        return folioRepo.findByFolioNumber(folioNum)
            .orElseGet(() -> folioRepo.save(Folio.builder()
                .folioNumber(folioNum)
                .amc(node.path("amc").asText("")) 
                .investor(investor) 
                .build()));
    }

    private Scheme findOrCreateScheme(JsonNode node, Folio folio) {
        String isin = node.path("isin").asText(null);
        String rawName = node.has("name") ? node.path("name").asText() : node.path("scheme").asText();
        String name = CommonUtils.SANITIZE.apply(rawName);
        
        String rawAmfi = node.has("amfiCode") ? node.path("amfiCode").asText() : node.path("amfi").asText();
        String amfiCode = CommonUtils.SANITIZE_AMFI.apply(rawAmfi); 

        Scheme existingScheme = schemeRepo.findByIsin(isin).orElse(null);

        if (existingScheme != null) {
            if (existingScheme.getAssetCategory() == null || existingScheme.getAssetCategory().equals("UNKNOWN")) {
                String realCategory = navService.getLatestSchemeDetails(existingScheme.getAmfiCode()).getCategory();
                existingScheme.setAssetCategory(realCategory);
                schemeRepo.save(existingScheme); 
                log.info("🔧 FIXED DB ROW: Updated {} with category: {}", name, realCategory);
            }
            return existingScheme;
        }

        SchemeDetailsDTO details = navService.getLatestSchemeDetails(amfiCode);
        String extractedCategory = details.getCategory();
        String benchmark = CommonUtils.DETERMINE_BENCHMARK.apply("", extractedCategory);

        Scheme newScheme = Scheme.builder()
            .isin(isin)
            .name(name)
            .amfiCode(amfiCode)
            .folio(folio)
            .assetCategory(extractedCategory)
            .benchmarkIndex(benchmark)
            .build();

        return schemeRepo.save(newScheme);
    }

    private Transaction prepareTransaction(JsonNode txNode, Scheme scheme) {
        String hash = CommonUtils.GENERATE_HASH.apply(txNode, scheme.getId());
        if (txnRepo.existsByTxnHash(hash)) return null;

        return Transaction.builder()
            .txnHash(hash)
            .date(LocalDate.parse(txNode.path("date").asText()))
            .description(CommonUtils.SANITIZE.apply(txNode.path("description").asText()))
            .amount(CommonUtils.TO_DECIMAL.apply(txNode.path("amount")))
            .units(CommonUtils.TO_DECIMAL.apply(txNode.path("units")))
            .transactionType(txNode.path("transaction_type").asText())
            .scheme(scheme)
            .build();
    }

    private void batchInsertTransactions(List<Transaction> transactions) {
        log.info("📦 Batch inserting {} transactions", transactions.size());
        String sql = "INSERT INTO transaction (txn_hash, transaction_date, description, units, amount, transaction_type, scheme_id) VALUES (?, ?, ?, ?, ?, ?, ?)";
        
        jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                Transaction tx = transactions.get(i);
                ps.setString(1, tx.getTxnHash());
                ps.setObject(2, java.sql.Date.valueOf(tx.getDate()));
                ps.setString(3, tx.getDescription());
                ps.setBigDecimal(4, tx.getUnits());
                ps.setBigDecimal(5, tx.getAmount());
                ps.setString(6, tx.getTransactionType());
                ps.setLong(7, tx.getScheme().getId());
            }

            @Override
            public int getBatchSize() {
                return transactions.size();
            }
        });
    }

    public void processPortfolioTaxation(List<Transaction> allSells) {
        Map<String, String> amfiToCategoryMap = allSells.stream()
            .map(tx -> tx.getScheme().getAmfiCode())
            .filter(Objects::nonNull) 
            .distinct()
            .collect(Collectors.toMap(
                code -> code,                                    
                code -> navService.getLatestSchemeDetails(code).getCategory(), 
                (existing, replacement) -> existing                        
            ));

        for (Transaction sell : allSells) {
            String category = amfiToCategoryMap.get(sell.getScheme().getAmfiCode());
            fifoService.consumeLotsFIFO(sell, category);
        }
    }
}
