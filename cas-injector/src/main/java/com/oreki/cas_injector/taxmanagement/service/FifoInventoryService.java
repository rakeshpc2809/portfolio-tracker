package com.oreki.cas_injector.taxmanagement.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.springframework.stereotype.Service;

import com.oreki.cas_injector.core.utils.CommonUtils;
import com.oreki.cas_injector.transactions.model.CapitalGainAudit;
import com.oreki.cas_injector.transactions.model.TaxLot;
import com.oreki.cas_injector.transactions.model.Transaction;
import com.oreki.cas_injector.transactions.repository.CapitalGainAuditRepository;
import com.oreki.cas_injector.transactions.repository.TaxLotRepository;
import com.oreki.cas_injector.transactions.repository.TransactionRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
public class FifoInventoryService {

    private final TaxLotRepository taxLotRepo;
    private final TransactionRepository txnRepo;
    private final CapitalGainAuditRepository auditRepo;

    public void applyInventoryRules(Transaction tx, String category) {
        String type = tx.getTransactionType().toUpperCase();

        if (type.contains("BUY") || type.contains("PURCHASE") || type.contains("SWITCH_IN")) {
            // Skip saving individual TaxLot here since they are batch-inserted in CasProcessingService!
        } 
        else if (type.contains("STAMP_DUTY") || type.contains("STAMP")) {
            List<TaxLot> activeLots = taxLotRepo.findBySchemeAndStatusOrderByBuyDateAsc(tx.getScheme(), "OPEN");
            activeLots.stream()
                .filter(lot -> !lot.getBuyDate().isAfter(tx.getDate()))
                .reduce((first, second) -> second)
                .ifPresent(lot -> {
                    BigDecimal currentTotalCost = lot.getCostBasisPerUnit().multiply(lot.getOriginalUnits());
                    BigDecimal newTotalCost = currentTotalCost.add(tx.getAmount().abs());
                    
                    lot.setCostBasisPerUnit(newTotalCost.divide(lot.getOriginalUnits(), 4, RoundingMode.HALF_UP));
                    taxLotRepo.save(lot);
                    
                    tx.setParent(lot.getBuyTransaction());
                    txnRepo.save(tx);
                });
        } 
        else if (type.contains("SELL") || type.contains("REDEMPTION") || type.contains("SWITCH_OUT")) {
            consumeLotsFIFO(tx, category);
        }
    }

    public void consumeLotsFIFO(Transaction sellTx, String category) {
        BigDecimal unitsToRedeem = sellTx.getUnits().abs();
        List<TaxLot> activeLots = taxLotRepo.findBySchemeAndStatusOrderByBuyDateAsc(sellTx.getScheme(), "OPEN").stream()
            .filter(lot -> !lot.getBuyDate().isAfter(sellTx.getDate()))
            .toList();

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
