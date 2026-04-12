package com.oreki.cas_injector.core.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.cache.CacheManager;

import com.oreki.cas_injector.backfill.service.NavService;
import com.oreki.cas_injector.core.model.Folio;
import com.oreki.cas_injector.core.model.Investor;
import com.oreki.cas_injector.core.model.Scheme;
import com.oreki.cas_injector.core.repository.FolioRepository;
import com.oreki.cas_injector.core.repository.InvestorRepository;
import com.oreki.cas_injector.core.repository.SchemeRepository;
import com.oreki.cas_injector.core.utils.CommonUtils;
import com.oreki.cas_injector.taxmanagement.service.FifoInventoryService;
import com.oreki.cas_injector.transactions.model.Transaction;
import com.oreki.cas_injector.transactions.repository.TransactionRepository;

import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import com.fasterxml.jackson.databind.JsonNode;

@Service
@Slf4j
public class CasProcessingService {

    @Autowired private InvestorRepository investorRepo;
    @Autowired private TransactionRepository txnRepo;
    @Autowired private SchemeRepository schemeRepo;
    @Autowired private FolioRepository folioRepo;
    @Autowired private NavService navService;
    @Autowired private CacheManager cacheManager;
    @Autowired private FifoInventoryService fifoService;

    @Transactional
    public void processJson(JsonNode root) {
        String pan = root.path("pan").asText();
        Investor investor = investorRepo.findById(pan).orElseGet(() -> 
            investorRepo.save(Investor.builder()
                .pan(pan)
                .name(root.path("name").asText())
                .email(root.path("email").asText())
                .build())
        );

        root.path("folios").forEach(folioNode -> {
            Folio folio = findOrCreateFolio(folioNode, investor); 

            folioNode.path("schemes").forEach(schemeNode -> {
                Scheme scheme = findOrCreateScheme(schemeNode, folio);
                
                schemeNode.path("transactions").forEach(txNode -> {
                    processTransaction(txNode, scheme);
                });
            });
        });

        // 4. Evict caches for this PAN
        if (cacheManager.getCache("portfolioCache") != null) {
            cacheManager.getCache("portfolioCache").evict(pan);
        }
        if (cacheManager.getCache("dashboardSummary") != null) {
            cacheManager.getCache("dashboardSummary").evict(pan);
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
        String amfiCode = sanitizeAmfi(rawAmfi); 

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

        String extractedCategory = navService.getLatestSchemeDetails(amfiCode).getCategory();
        log.info("🚨 STEP 1 - NavService Returned: " + extractedCategory);

        Scheme newScheme = Scheme.builder()
            .isin(isin)
            .name(name)
            .amfiCode(amfiCode)
            .folio(folio)
            .assetCategory(extractedCategory)
            .build();

        return schemeRepo.save(newScheme);
    }

    private void processTransaction(JsonNode txNode, Scheme scheme) {
        String hash = CommonUtils.GENERATE_HASH.apply(txNode, scheme.getId());
        if (txnRepo.existsByTxnHash(hash)) return;

        Transaction tx = Transaction.builder()
            .txnHash(hash)
            .date(LocalDate.parse(txNode.path("date").asText()))
            .description(CommonUtils.SANITIZE.apply(txNode.path("description").asText()))
            .amount(CommonUtils.TO_DECIMAL.apply(txNode.path("amount")))
            .units(CommonUtils.TO_DECIMAL.apply(txNode.path("units")))
            .transactionType(txNode.path("transaction_type").asText())
            .scheme(scheme)
            .build();
        
        txnRepo.save(tx);
        
        String category = "UNKNOWN";
        if ("SELL".equalsIgnoreCase(tx.getTransactionType()) || "STAMP_DUTY".equalsIgnoreCase(tx.getTransactionType())) {
             category = navService.getLatestSchemeDetails(tx.getScheme().getAmfiCode()).getCategory();
        }
        fifoService.applyInventoryRules(tx, category);
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

    private String sanitizeAmfi(String amfi) {
        if (amfi == null) return "";
        String s = amfi.trim();
        return s.replaceFirst("^0+(?!$)", "");
    }
}
