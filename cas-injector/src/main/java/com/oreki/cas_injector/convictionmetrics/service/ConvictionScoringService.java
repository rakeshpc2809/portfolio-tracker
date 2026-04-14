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
                return calculatePersonalCagr(lotsByAmfi.get(amfi));
            }).max().orElse(35.0);

        log.info("🎯 Dynamic Yield Upper Bound set to {}%", String.format("%.2f", maxCagrFound));

        for (Map<String, Object> fund : metrics) {
            String amfi = CommonUtils.SANITIZE_AMFI.apply((String) fund.get("amfi_code"));
            List<TaxLot> fundLots = lotsByAmfi.get(amfi);
            if (fundLots == null || fundLots.isEmpty()) continue;

            log.info("🔎 Scoring fund: {} (AMFI: {})", fundLots.get(0).getScheme().getName(), amfi);
            
            // 1. YIELD SCORE (Personal CAGR relative to portfolio max)
            double cagr = calculatePersonalCagr(fundLots);
            double yieldScore = Math.max(0, Math.min(100, (cagr / maxCagrFound) * 100));
            log.info("  ├─ Found {} lots for this fund.", fundLots.size());
            log.info("  ├─ CAGR: {}%", String.format("%.2f", cagr));

            // 2. RISK SCORE (Sortino Ratio)
            // Normalizing Sortino: 0 is poor, 2.0 is excellent.
            double sortino = (double) fund.getOrDefault("sortino_ratio", 0.0);
            double riskScore = Math.max(0, Math.min(100, (sortino / 2.0) * 100));

            // 3. VALUE SCORE (Z-Score based cheapness)
            // z < -2 = cheap, z > +2 = expensive
            double zScore = (double) fund.getOrDefault("rolling_z_score_252", 0.0);
            double valueScore = Math.max(0, Math.min(100, 50 - (zScore * 20))); // z=-2 -> 90, z=0 -> 50, z=+2 -> 10
            log.info("  ├─ Z-Score: {} (Value: {})", String.format("%.2f", zScore), String.format("%.0f", valueScore));

            // 4. PAIN SCORE (Max Drawdown)
            // Low drawdown = High score (less pain)
            double mdd = Math.abs((double) fund.getOrDefault("max_drawdown", 0.0));
            double painScore = Math.max(0, 100 - (mdd * 2.5)); // 40% DD = 0 score

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
            log.info("  ├─ Tax Drag: {}%", String.format("%.2f", taxPctOfValue));

            // --- FINAL CALCULATION ---
            double finalScore = (yieldScore * WEIGHT_YIELD) +
                               (riskScore * WEIGHT_RISK) +
                               (valueScore * WEIGHT_VALUE) +
                               (painScore * WEIGHT_PAIN) +
                               (frictionScore * WEIGHT_FRICTION);

            convictionMetricsRepository.updateConvictionBreakdown(
                (int) finalScore, yieldScore, riskScore, valueScore, painScore, frictionScore, amfi
            );
        }

        log.info("🏁 [3/3] Dynamic Conviction Scoring completed.");
    }

    private double calculatePersonalCagr(List<TaxLot> lots) {
        if (lots == null || lots.isEmpty()) return 0.0;

        double totalCost = 0;
        double totalGain = 0;
        double weightedDays = 0;

        for (TaxLot lot : lots) {
            double cost = lot.getRemainingUnits().doubleValue() * lot.getCostBasisPerUnit().doubleValue();
            long days = ChronoUnit.DAYS.between(lot.getBuyDate(), LocalDate.now());
            
            // Simple absolute gain for this lot (unrealized)
            double currentNav = navService.getLatestSchemeDetails(lot.getScheme().getAmfiCode()).getNav().doubleValue();
            double value = lot.getRemainingUnits().doubleValue() * currentNav;
            
            totalCost += cost;
            totalGain += (value - cost);
            weightedDays += (cost * days);
        }

        if (totalCost == 0 || weightedDays == 0) return 0.0;

        double avgDays = weightedDays / totalCost;
        double absoluteReturn = totalGain / totalCost;
        
        // Annualize
        if (avgDays < 1) return 0.0;
        return (Math.pow(1 + absoluteReturn, 365.0 / avgDays) - 1) * 100;
    }
}
