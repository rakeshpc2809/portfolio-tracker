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

import com.oreki.cas_injector.backfill.service.NavService;
import com.oreki.cas_injector.core.model.Folio;
import com.oreki.cas_injector.core.model.Investor;
import com.oreki.cas_injector.core.model.Scheme;
import com.oreki.cas_injector.core.repository.FolioRepository;
import com.oreki.cas_injector.core.repository.InvestorRepository;
import com.oreki.cas_injector.core.repository.SchemeRepository;
import com.oreki.cas_injector.core.utils.CommonUtils;
import com.oreki.cas_injector.transactions.model.CapitalGainAudit;
import com.oreki.cas_injector.transactions.model.TaxLot;
import com.oreki.cas_injector.transactions.model.Transaction;
import com.oreki.cas_injector.transactions.repository.CapitalGainAuditRepository;
import com.oreki.cas_injector.transactions.repository.TaxLotRepository;
import com.oreki.cas_injector.transactions.repository.TransactionRepository;

import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;

@Service
@Slf4j
public class CasProcessingService {

    @Autowired private InvestorRepository investorRepo; // Ported from InjectionService
    @Autowired private TransactionRepository txnRepo;
    @Autowired private SchemeRepository schemeRepo;
    @Autowired private TaxLotRepository taxLotRepo;
    @Autowired private CapitalGainAuditRepository auditRepo;
    @Autowired private FolioRepository folioRepo;
    @Autowired private NavService navService;

    @Transactional
    public void processJson(JsonNode root) {
        // 1. Investor Level (Ported from InjectionService)
        String pan = root.path("pan").asText();
        Investor investor = investorRepo.findById(pan).orElseGet(() -> 
            investorRepo.save(Investor.builder()
                .pan(pan)
                .name(root.path("name").asText())
                .email(root.path("email").asText())
                .build())
        );

        root.path("folios").forEach(folioNode -> {
            // 2. Find/Create Folio
            Folio folio = findOrCreateFolio(folioNode, investor); 

            folioNode.path("schemes").forEach(schemeNode -> {
                // 3. Find/Create Scheme
                Scheme scheme = findOrCreateScheme(schemeNode, folio);
                
                schemeNode.path("transactions").forEach(txNode -> {
                    processTransaction(txNode, scheme);
                });
            });
        });
    }

    private Folio findOrCreateFolio(JsonNode node, Investor investor) {
        // Ported from InjectionService: Uses "folio_number" to match your actual JSON
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
        
        // Handle variations in JSON keys ("amfiCode" vs "amfi")
        String rawAmfi = node.has("amfiCode") ? node.path("amfiCode").asText() : node.path("amfi").asText();
        String amfiCode = CommonUtils.SANITIZE.apply(rawAmfi); 

        // 1. Look for the scheme in the database
        Scheme existingScheme = schemeRepo.findByIsin(isin).orElse(null);

        if (existingScheme != null) {
            // 🌟 SELF-HEALING: If the DB has a null or UNKNOWN category, fix it!
            if (existingScheme.getAssetCategory() == null || existingScheme.getAssetCategory().equals("UNKNOWN")) {
                String realCategory = navService.getLatestSchemeDetails(existingScheme.getAmfiCode()).getCategory();
                existingScheme.setAssetCategory(realCategory);
                schemeRepo.save(existingScheme); 
                log.info("🔧 FIXED DB ROW: Updated {} with category: {}", name, realCategory);
            }
            return existingScheme;
        }

        // 2. Brand new scheme
        String extractedCategory = navService.getLatestSchemeDetails(amfiCode).getCategory();
        log.info("🚨 STEP 1 - NavService Returned: " + extractedCategory);

        Scheme newScheme = Scheme.builder()
            .isin(isin)
            .name(name)
            .amfiCode(amfiCode)
            .folio(folio)
            .assetCategory(extractedCategory) // <--- Safely attached!
            .build();

        return schemeRepo.save(newScheme);
    }

    private void processTransaction(JsonNode txNode, Scheme scheme) {
        // 1. Check Idempotency
        String hash = CommonUtils.GENERATE_HASH.apply(txNode, scheme.getId());
        if (txnRepo.existsByTxnHash(hash)) return;

        // 2. Build and Save Transaction
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

        // 3. Route to Inventory/Accounting logic
        applyInventoryRules(tx);
    }

    private void applyInventoryRules(Transaction tx) {
        String type = tx.getTransactionType();

        if ("BUY".equalsIgnoreCase(type)) {
            taxLotRepo.save(TaxLot.builder()
                .buyTransaction(tx)
                .scheme(tx.getScheme())
                .buyDate(tx.getDate())
                .originalUnits(tx.getUnits())
                .remainingUnits(tx.getUnits())
                .costBasisPerUnit(CommonUtils.CALC_NAV.apply(tx.getAmount(), tx.getUnits()))
                .status("OPEN")
                .build());
        } 
        else if ("STAMP_DUTY".equalsIgnoreCase(type)) {
            taxLotRepo.findFirstBySchemeAndStatusOrderByBuyDateDesc(tx.getScheme(), "OPEN")
                .ifPresent(lot -> {
                    BigDecimal currentTotalCost = lot.getCostBasisPerUnit().multiply(lot.getOriginalUnits());
                    BigDecimal newTotalCost = currentTotalCost.add(tx.getAmount().abs());
                    
                    lot.setCostBasisPerUnit(newTotalCost.divide(lot.getOriginalUnits(), 4, RoundingMode.HALF_UP));
                    taxLotRepo.save(lot);
                    
                    tx.setParent(lot.getBuyTransaction());
                    txnRepo.save(tx);
                });
        } 
        else if ("SELL".equalsIgnoreCase(type)) {
            String category = navService.getLatestSchemeDetails(tx.getScheme().getAmfiCode()).getCategory();
            consumeLotsFIFO(tx, category);
        }
    }

    // Advanced Batch Processor (Retained from V2)
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
            consumeLotsFIFO(sell, category);
        }
    }

    // Advanced Tax Calculation (Retained from V2)
    private void consumeLotsFIFO(Transaction sellTx, String category) {
        BigDecimal unitsToRedeem = sellTx.getUnits().abs();
        List<TaxLot> activeLots = taxLotRepo.findBySchemeAndStatusOrderByBuyDateAsc(sellTx.getScheme(), "OPEN");

        BigDecimal sellPrice = sellTx.getAmount().abs().divide(sellTx.getUnits().abs(), 4, RoundingMode.HALF_UP);

        for (TaxLot lot : activeLots) {
            if (unitsToRedeem.compareTo(BigDecimal.ZERO) <= 0) break;

            BigDecimal match = unitsToRedeem.min(lot.getRemainingUnits());
            BigDecimal gain = sellPrice.subtract(lot.getCostBasisPerUnit()).multiply(match);

            String taxCat = CommonUtils.DETERMINE_TAX_CATEGORY.apply(
                lot.getBuyDate(), 
                sellTx.getDate(), 
                category 
            );

            auditRepo.save(CapitalGainAudit.builder()
                .sellTransaction(sellTx)
                .taxLot(lot)
                .unitsMatched(match)
                .realizedGain(gain)
                .taxCategory(taxCat)
                .holdingPeriodDays(ChronoUnit.DAYS.between(lot.getBuyDate(), sellTx.getDate()))
                .build());

            lot.setRemainingUnits(lot.getRemainingUnits().subtract(match));
            if (lot.getRemainingUnits().compareTo(BigDecimal.ZERO) == 0) lot.setStatus("CLOSED");
            taxLotRepo.save(lot);

            unitsToRedeem = unitsToRedeem.subtract(match);
        }
    }
}