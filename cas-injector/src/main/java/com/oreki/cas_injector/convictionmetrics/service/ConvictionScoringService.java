package com.oreki.cas_injector.convictionmetrics.service;


import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.oreki.cas_injector.backfill.service.NavService;
import com.oreki.cas_injector.convictionmetrics.repository.ConvictionMetricsRepository;
import com.oreki.cas_injector.core.utils.CommonUtils;
import com.oreki.cas_injector.taxmanagement.dto.TaxSimulationResult;
import com.oreki.cas_injector.taxmanagement.service.TaxSimulatorService;
import com.oreki.cas_injector.transactions.model.TaxLot;
import com.oreki.cas_injector.transactions.repository.TaxLotRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
public class ConvictionScoringService {

    private final ConvictionMetricsRepository convictionMetricsRepository;
    private final TaxLotRepository taxLotRepository;
    private final NavService navService;
    private final TaxSimulatorService taxSimulator;

    // 🔬 REVISED INSTITUTIONAL WEIGHTS
    private static final double WEIGHT_YIELD = 0.20;      
    private static final double WEIGHT_RISK = 0.25;       
    private static final double WEIGHT_VALUE = 0.25;      // 🚀 Powered by NAV Signals (Percentile, ATH, Z-Score)
    private static final double WEIGHT_PAIN = 0.15;       
    private static final double WEIGHT_FRICTION = 0.15;   

    @Transactional
    public void calculateAndSaveFinalScores(String investorPan) {
        log.info("🚀 [1/3] Starting Conviction Scoring for PAN: {}", investorPan);

        Double slab = convictionMetricsRepository.getJdbcTemplate().queryForObject(
            "SELECT tax_slab FROM investor WHERE pan = ?", Double.class, investorPan);
        double slabRate = (slab != null) ? slab : 0.30;

        List<TaxLot> allLots = taxLotRepository.findByStatusAndSchemeFolioInvestorPan("OPEN", investorPan);
        log.info("📦 Found {} total open lots for PAN: {}", allLots.size(), investorPan);
        
        Map<String, List<TaxLot>> lotsByAmfi = allLots.stream()
                .collect(Collectors.groupingBy(lot -> CommonUtils.SANITIZE_AMFI.apply(lot.getScheme().getAmfiCode())));
        log.info("📦 Grouped into {} unique AMFI codes from tax lots.", lotsByAmfi.size());

        List<Map<String, Object>> metrics = convictionMetricsRepository.findMetricsForInvestor(investorPan);
        log.info("📊 [2/3] Found {} funds in fund_conviction_metrics to score.", metrics.size());

        // We want to normalize the yieldScore relative to the portfolio's own max CAGR to make it a 0-100 scale
        double maxCagrFound = metrics.stream()
            .mapToDouble(m -> {
                String amfi = CommonUtils.SANITIZE_AMFI.apply((String) m.get("amfi_code"));
                return Math.max(0, calculatePersonalCagr(lotsByAmfi.get(amfi)));
            }).max().orElse(35.0);
        
        if (maxCagrFound <= 5.0) maxCagrFound = 35.0; // Floor for normalization to avoid division by zero or skew

        log.info("🎯 Dynamic Yield Upper Bound set to {}%", String.format("%.2f", maxCagrFound));

        for (Map<String, Object> fund : metrics) {
            String amfi = CommonUtils.SANITIZE_AMFI.apply((String) fund.get("amfi_code"));
            List<TaxLot> fundLots = lotsByAmfi.get(amfi);
            if (fundLots == null || fundLots.isEmpty()) continue;

            log.info("🔎 Scoring fund: {} (AMFI: {})", fundLots.get(0).getScheme().getName(), amfi);
            
            // 1. YIELD SCORE (Personal CAGR relative to portfolio max)
            double cagr = calculatePersonalCagr(fundLots);
            double yieldScore = (cagr > 0) ? Math.min(100, (cagr / maxCagrFound) * 100) : 0;
            
            // 2. RISK SCORE (Sortino Ratio)
            // Normalizing Sortino: < 0 is 0, 0 is 10, 1.0 is 50, 2.0 is 90, > 2.5 is 100
            double sortino = safeDouble(fund.get("sortino_ratio"));
            double riskScore = (sortino <= 0) ? 10 : Math.min(100, 10 + (sortino * 40));

            // 3. VALUE SCORE (Z-Score based cheapness)
            // z < -2 = cheap (90+), z = 0 = fair (50), z > +2 = expensive (10-)
            double zScore = safeDouble(fund.get("rolling_z_score_252"));
            double valueScore = Math.max(5, Math.min(95, 50 - (zScore * 22.5))); 

            // 4. PAIN SCORE (Max Drawdown)
            // Low drawdown = High score (less pain). 0% DD = 100, 40% DD = 0
            double mdd = Math.abs(safeDouble(fund.get("max_drawdown")));
            double painScore = Math.max(0, 100 - (mdd * 2.5));

            // 5. FRICTION SCORE (Tax Efficiency)
            // Simulate exit friction
            String amfiCode = CommonUtils.SANITIZE_AMFI.apply((String) fund.get("amfi_code"));
            String category = navService.getLatestSchemeDetails(amfiCode).getCategory();
            TaxSimulationResult taxResult = taxSimulator.simulateHifoExit(fundLots, category, slabRate);
            
            double taxPctOfValue = (taxResult.sellAmount() > 0) 
                ? (taxResult.estimatedTax() / taxResult.sellAmount()) * 100 
                : 0;
            
            // Score is 100 if tax is 0%, 0 if tax is 15% of total value
            double frictionScore = Math.max(0, 100 - (taxPctOfValue * 6.66));
            
            // --- FINAL CALCULATION ---
            double finalScore = (yieldScore * WEIGHT_YIELD) +
                               (riskScore * WEIGHT_RISK) +
                               (valueScore * WEIGHT_VALUE) +
                               (painScore * WEIGHT_PAIN) +
                               (frictionScore * WEIGHT_FRICTION);

            // Ensure we don't accidentally save 0 if it was a calculation error, 
            // but here we want the actual computed values.
            convictionMetricsRepository.updateConvictionBreakdown(
                (int) finalScore, 
                Math.max(1, yieldScore), 
                Math.max(1, riskScore), 
                Math.max(1, valueScore), 
                Math.max(1, painScore), 
                Math.max(1, frictionScore), 
                amfi
            );
            
            log.info("  ├─ CAGR: {}% -> Yield: {}", String.format("%.2f", cagr), (int)yieldScore);
            log.info("  ├─ Sortino: {} -> Risk: {}", String.format("%.2f", sortino), (int)riskScore);
            log.info("  ├─ Z-Score: {} -> Value: {}", String.format("%.2f", zScore), (int)valueScore);
            log.info("  └─ FINAL: {}", (int)finalScore);
        }

        log.info("🏁 [3/3] Dynamic Conviction Scoring completed.");
    }

    private double calculatePersonalCagr(List<TaxLot> lots) {
        if (lots == null || lots.isEmpty()) return 0.0;

        double totalCost = 0;
        double totalValue = 0;
        double weightedDays = 0;

        for (TaxLot lot : lots) {
            double cost = lot.getRemainingUnits().doubleValue() * lot.getCostBasisPerUnit().doubleValue();
            long days = Math.max(1, ChronoUnit.DAYS.between(lot.getBuyDate(), LocalDate.now()));
            
            double currentNav = navService.getLatestSchemeDetails(lot.getScheme().getAmfiCode()).getNav().doubleValue();
            
            // If currentNav is missing from cache, fallback to cost to avoid -100% skew
            if (currentNav <= 0) currentNav = lot.getCostBasisPerUnit().doubleValue();
            
            double value = lot.getRemainingUnits().doubleValue() * currentNav;
            
            totalCost += cost;
            totalValue += value;
            weightedDays += (cost * days);
        }

        if (totalCost <= 0 || weightedDays <= 0) return 0.0;

        double avgDays = weightedDays / totalCost;
        double absoluteReturn = (totalValue / totalCost) - 1;
        
        // Annualize
        return (Math.pow(1 + absoluteReturn, 365.0 / avgDays) - 1) * 100;
    }

    private double safeDouble(Object o) {
        if (o == null) return 0.0;
        return ((Number) o).doubleValue();
    }
}
