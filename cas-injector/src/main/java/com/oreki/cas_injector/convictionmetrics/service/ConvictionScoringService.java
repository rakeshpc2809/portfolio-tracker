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

    // 🔬 REVISED 7-FACTOR INSTITUTIONAL WEIGHTS
    private static final double WEIGHT_YIELD = 0.18;      
    private static final double WEIGHT_RISK = 0.20;       
    private static final double WEIGHT_VALUE = 0.20;      
    private static final double WEIGHT_PAIN_RECOVERY = 0.15; // Blended MDD + OU Half-life
    private static final double WEIGHT_REGIME = 0.12;     // HMM Bear Probability
    private static final double WEIGHT_FRICTION = 0.10;   // Updated for 20% STCG base
    private static final double WEIGHT_EXPENSE = 0.05;    // Expense Ratio + AUM Band

    @Transactional
    public void calculateAndSaveFinalScores(String investorPan) {
        log.info("🚀 [1/3] Starting Revised 7-Factor Conviction Scoring for PAN: {}", investorPan);

        Double slab = convictionMetricsRepository.getJdbcTemplate().queryForObject(
            "SELECT tax_slab FROM investor WHERE pan = ?", Double.class, investorPan);
        double slabRate = (slab != null) ? slab : 0.30;

        List<TaxLot> allLots = taxLotRepository.findByStatusAndSchemeFolioInvestorPan("OPEN", investorPan);
        log.info("📦 Found {} total open lots for PAN: {}", allLots.size(), investorPan);
        
        Map<String, List<TaxLot>> lotsByAmfi = allLots.stream()
                .collect(Collectors.groupingBy(lot -> CommonUtils.SANITIZE_AMFI.apply(lot.getScheme().getAmfiCode())));

        List<Map<String, Object>> metrics = convictionMetricsRepository.findMetricsForInvestor(investorPan);
        log.info("📊 [2/3] Found {} funds in fund_conviction_metrics to score.", metrics.size());

        double maxCagrFound = metrics.stream()
            .mapToDouble(m -> {
                String amfi = CommonUtils.SANITIZE_AMFI.apply((String) m.get("amfi_code"));
                return Math.max(0, calculatePersonalCagr(lotsByAmfi.get(amfi)));
            }).max().orElse(35.0);
        
        if (maxCagrFound <= 5.0) maxCagrFound = 35.0;

        for (Map<String, Object> fund : metrics) {
            String amfi = CommonUtils.SANITIZE_AMFI.apply((String) fund.get("amfi_code"));
            List<TaxLot> fundLots = lotsByAmfi.get(amfi);
            if (fundLots == null || fundLots.isEmpty()) continue;

            // 1. YIELD SCORE (Personal CAGR relative to portfolio max)
            double cagr = calculatePersonalCagr(fundLots);
            double yieldScore = (cagr > 0) ? Math.min(100, (cagr / maxCagrFound) * 100) : 0;
            
            // 2. RISK SCORE (Sortino Ratio - Continuous proposal)
            // proposal: 50 + (sortino * 25), clamped [0, 100]
            double sortino = safeDouble(fund.get("sortino_ratio"));
            double riskScore = Math.max(0, Math.min(100, 50 + (sortino * 25)));

            // 3. VALUE SCORE (Z-Score based cheapness)
            double zScore = safeDouble(fund.get("rolling_z_score_252"));
            double valueScore = Math.max(5, Math.min(95, 50 - (zScore * 22.5))); 

            // 4. PAIN + RECOVERY (MDD blended with OU Half-life)
            double mdd = Math.abs(safeDouble(fund.get("max_drawdown")));
            double painScore = Math.max(0, 100 - (mdd * 2.5));
            
            double recoveryScore = 0;
            if (safeDouble(fund.get("ou_valid")) > 0) {
                double halfLife = safeDouble(fund.get("ou_half_life"));
                recoveryScore = Math.max(0, Math.min(100, 100 * Math.exp(-halfLife / 30.0)));
            } else {
                recoveryScore = painScore; // Fallback if OU not valid
            }
            double painRecoveryScore = (painScore * 0.6) + (recoveryScore * 0.4);

            // 5. REGIME SCORE (HMM Bear Prob)
            // bear_prob 0 -> 100, bear_prob 1.0 -> 0
            double bearProb = safeDouble(fund.get("hmm_bear_prob"));
            double regimeScore = Math.max(0, 100 - (bearProb * 100));

            // 6. FRICTION SCORE (Tax Efficiency)
            String amfiCode = CommonUtils.SANITIZE_AMFI.apply((String) fund.get("amfi_code"));
            String category = navService.getLatestSchemeDetails(amfiCode).getCategory();
            TaxSimulationResult taxResult = taxSimulator.simulateHifoExit(fundLots, category, slabRate);
            double taxPctOfValue = (taxResult.sellAmount() > 0) ? (taxResult.estimatedTax() / taxResult.sellAmount()) * 100 : 0;
            // 100/20 = 5.0 multiplier for 20% ceiling
            double frictionScore = Math.max(0, 100 - (taxPctOfValue * 5.0));

            // 7. EXPENSE + AUM BANDS
            double expRatio = safeDouble(fund.get("expense_ratio"));
            double expenseDragScore = Math.max(0, 100 - expRatio * 50);
            
            double aumCr = safeDouble(fund.get("aum_cr"));
            double aumScore = (aumCr < 100) ? (aumCr / 100.0) * 50 : (aumCr > 50000) ? Math.max(50, 100 - (aumCr - 50000) / 5000.0) : 100;
            double combinedExpAum = (expenseDragScore * 0.7) + (aumScore * 0.3);

            // --- FINAL CALCULATION ---
            double finalScore = (yieldScore * WEIGHT_YIELD) +
                               (riskScore * WEIGHT_RISK) +
                               (valueScore * WEIGHT_VALUE) +
                               (painRecoveryScore * WEIGHT_PAIN_RECOVERY) +
                               (regimeScore * WEIGHT_REGIME) +
                               (frictionScore * WEIGHT_FRICTION) +
                               (combinedExpAum * WEIGHT_EXPENSE);

            convictionMetricsRepository.updateConvictionBreakdown(
                (int) finalScore, yieldScore, riskScore, valueScore, painRecoveryScore, regimeScore, frictionScore, combinedExpAum, amfi
            );
        }
        log.info("🏁 [3/3] Revised 7-Factor Scoring completed.");
    }

    private double calculatePersonalCagr(List<TaxLot> lots) {
        if (lots == null || lots.isEmpty()) return 0.0;

        String amfiCode = lots.get(0).getScheme().getAmfiCode();
        double currentNav = navService.getLatestSchemeDetails(amfiCode).getNav().doubleValue();
        
        double totalCost = 0;
        double totalValue = 0;
        double weightedDays = 0;

        for (TaxLot lot : lots) {
            double cost = lot.getRemainingUnits().doubleValue() * lot.getCostBasisPerUnit().doubleValue();
            long days = Math.max(1, ChronoUnit.DAYS.between(lot.getBuyDate(), LocalDate.now()));
            
            // fallback to cost if NAV is 0
            double lotValue = lot.getRemainingUnits().doubleValue() * (currentNav > 0 ? currentNav : lot.getCostBasisPerUnit().doubleValue());
            
            totalCost += cost;
            totalValue += lotValue;
            weightedDays += (cost * days);
        }

        if (totalCost <= 0 || weightedDays <= 0) return 0.0;
        double avgDays = weightedDays / totalCost;
        double absoluteReturn = (totalValue / totalCost) - 1;
        return (Math.pow(1 + absoluteReturn, 365.0 / avgDays) - 1) * 100;
    }

    private double safeDouble(Object o) {
        if (o == null) return 0.0;
        return ((Number) o).doubleValue();
    }
}
