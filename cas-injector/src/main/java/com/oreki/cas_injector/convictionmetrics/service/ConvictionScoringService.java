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
import com.oreki.cas_injector.core.service.StrategyService;
import com.oreki.cas_injector.core.utils.CommonUtils;
import com.oreki.cas_injector.rebalancing.dto.StrategyTarget;
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
    private final StrategyService strategyService;

    @Transactional
    public void calculateAndSaveFinalScores(String investorPan) {
        log.info("🚀 [1/3] Starting Conviction Scoring in Java for PAN: {}", investorPan);

        Double slab = convictionMetricsRepository.getJdbcTemplate().queryForObject(
            "SELECT tax_slab FROM investor WHERE pan = ?", Double.class, investorPan);
        double slabRate = (slab != null) ? slab : 0.30;

        List<TaxLot> allLots = taxLotRepository.findByStatusAndSchemeFolioInvestorPan("OPEN", investorPan);
        log.info("📦 Found {} total open lots for PAN: {}", allLots.size(), investorPan);

        Map<String, List<TaxLot>> lotsByAmfi = allLots.stream()
                .collect(Collectors.groupingBy(lot -> CommonUtils.SANITIZE_AMFI.apply(lot.getScheme().getAmfiCode())));

        List<StrategyTarget> strategy = strategyService.fetchLatestStrategy(investorPan);
        Map<String, String> isinToStatus = strategy.stream()
            .collect(Collectors.toMap(s -> s.isin(), StrategyTarget::status, (a, b) -> a));

        List<Map<String, Object>> metrics = convictionMetricsRepository.findMetricsForInvestor(investorPan);
        log.info("📊 [2/3] Found {} funds to score.", metrics.size());

        // Pre-fetch latest NAVs for all funds in the portfolio universe
        Map<String, Double> currentNavMap = metrics.stream()
            .map(m -> CommonUtils.SANITIZE_AMFI.apply((String) m.get("amfi_code")))
            .distinct()
            .collect(Collectors.toMap(
                amfi -> amfi,
                amfi -> {
                    var details = navService.getLatestSchemeDetails(amfi);
                    return (details != null && details.getNav() != null) ? details.getNav().doubleValue() : 0.0;
                },
                (a, b) -> a
            ));

        double maxCagrFound = metrics.stream()
            .mapToDouble(m -> {
                String amfi = CommonUtils.SANITIZE_AMFI.apply((String) m.get("amfi_code"));
                return Math.max(0, calculatePersonalCagr(lotsByAmfi.get(amfi), currentNavMap.getOrDefault(amfi, 0.0)));
            }).max().orElse(35.0);

        if (maxCagrFound <= 5.0) maxCagrFound = 35.0;
        if (maxCagrFound > 150.0) maxCagrFound = 150.0;

        for (Map<String, Object> fund : metrics) {
            String amfi = CommonUtils.SANITIZE_AMFI.apply((String) fund.get("amfi_code"));
            double currentNav = currentNavMap.getOrDefault(amfi, 0.0);
            List<TaxLot> fundLots = lotsByAmfi.get(amfi);
            if (fundLots == null || fundLots.isEmpty()) continue;

            String isin = fundLots.get(0).getScheme().getIsin();
            String philStatus = isinToStatus.getOrDefault(isin, "ACTIVE").toUpperCase();

            double personalCagr = calculatePersonalCagr(fundLots, currentNav);

            var schemeDetails = navService.getLatestSchemeDetails(amfi);
            String category = (schemeDetails != null && schemeDetails.getCategory() != null) ? schemeDetails.getCategory().toUpperCase() : "OTHER";

            // simulate HIFO exit
            TaxSimulationResult taxResult = taxSimulator.simulateHifoExit(fundLots, category, slabRate, currentNav);
            double taxPctOfValue = (taxResult.sellAmount() > 0) ? (taxResult.estimatedTax() / taxResult.sellAmount()) * 100 : 0;

            double sortino = safeDouble(fund.get("sortino_ratio"));
            double rollingZScore252 = safeDouble(fund.get("rolling_z_score_252"));
            double maxDrawdown = safeDouble(fund.get("max_drawdown"));
            boolean ouValid = safeBoolean(fund.get("ou_valid"));
            double ouHalfLife = safeDouble(fund.get("ou_half_life"));
            double hmmBearProb = safeDouble(fund.get("hmm_bear_prob"));
            double expenseRatio = safeDouble(fund.get("expense_ratio"));
            double aumCr = safeDouble(fund.get("aum_cr"));
            double navPercentile1yr = safeDouble(fund.get("nav_percentile_1yr"));

            // 1. YIELD SCORE
            double yieldScore = 0.0;
            if (personalCagr > 0 && maxCagrFound > 0) {
                yieldScore = Math.min(100.0, (personalCagr / maxCagrFound) * 100.0);
            }

            // 2. RISK SCORE (Continuous Sortino)
            double riskScore = Math.max(0.0, Math.min(100.0, 50.0 + (sortino * 25.0)));

            // 3. VALUE SCORE (Z-Score cheapness, Sigmoid)
            double valueScore = 100.0 / (1.0 + Math.exp(rollingZScore252 * 1.2));

            // 4. PAIN + RECOVERY (MDD blended with OU Half-life)
            double mdd = Math.abs(maxDrawdown);
            double painScore = Math.max(0.0, 100.0 - (mdd * 1.5));
            double recoveryScore = painScore;
            if (ouValid && mdd > 5.0) {
                recoveryScore = Math.max(0.0, Math.min(100.0, 100.0 * Math.exp(-ouHalfLife / 30.0)));
            }
            double painRecoveryScore = (painScore * 0.6) + (recoveryScore * 0.4);

            // 5. REGIME SCORE (HMM Bear Prob)
            double regimeScore = Math.max(0.0, 100.0 - (hmmBearProb * 100.0));

            // 6. FRICTION SCORE (Tax Efficiency, 20% ceiling -> multiplier 5.0)
            double frictionScore = Math.max(0.0, 100.0 - (taxPctOfValue * 5.0));

            // 7. EXPENSE + AUM BANDS
            double expenseDragScore = Math.max(0.0, 100.0 - (expenseRatio * 50.0));
            double aumScore = 100.0;
            if (aumCr < 100.0) {
                aumScore = (aumCr / 100.0) * 50.0;
            } else if (aumCr > 50000.0) {
                aumScore = Math.max(50.0, 100.0 - (aumCr - 50000.0) / 5000.0);
            }
            double combinedExpAum = (expenseDragScore * 0.7) + (aumScore * 0.3);

            // Base Score
            double baseScore = 0.0;
            if ("REBALANCER".equals(philStatus)) {
                baseScore = 65.0;
            } else if ("ACCUMULATOR".equals(philStatus)) {
                double navRangeScore = (1.0 - navPercentile1yr) * 100.0;
                baseScore = (navRangeScore * 0.50) + (painScore * 0.30) + (expenseDragScore * 0.20);
            } else if (category.contains("DEBT") || category.contains("LIQUID")) {
                baseScore = (riskScore * 0.40) + (frictionScore * 0.35) + (expenseDragScore * 0.25);
            } else {
                baseScore = (yieldScore * 0.18) +
                            (riskScore * 0.20) +
                            (valueScore * 0.20) +
                            (painRecoveryScore * 0.15) +
                            (regimeScore * 0.12) +
                            (frictionScore * 0.10) +
                            (combinedExpAum * 0.05);
            }

            // Apply regime multiplier directly to base score
            double finalScore = baseScore * (1.0 - (hmmBearProb * 0.25));

            // Add AUM quality checks
            if (aumCr < 100.0 && aumCr > 0) {
                finalScore -= 15.0;
            } else if (aumCr > 50000.0) {
                finalScore -= 10.0;
            }
            finalScore = Math.max(0.0, finalScore);

            convictionMetricsRepository.updateConvictionBreakdown(
                (int) Math.round(finalScore),
                yieldScore,
                riskScore,
                valueScore,
                painRecoveryScore,
                regimeScore,
                frictionScore,
                combinedExpAum,
                amfi
            );
        }
        log.info("🏁 [3/3] Conviction Scoring completed.");
    }

    private double calculatePersonalCagr(List<TaxLot> lots, double currentNav) {
        if (lots == null || lots.isEmpty()) return 0.0;
        
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

    private boolean safeBoolean(Object o) {
        if (o == null) return false;
        if (o instanceof Boolean b) return b;
        if (o instanceof Number n) return n.doubleValue() > 0;
        return "true".equalsIgnoreCase(String.valueOf(o));
    }
}
