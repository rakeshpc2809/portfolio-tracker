package com.oreki.cas_injector.convictionmetrics.service;


import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.oreki.cas_injector.backfill.service.NavService;
import com.oreki.cas_injector.convictionmetrics.repository.ConvictionMetricsRepository;
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

    public void calculateAndSaveFinalScores(String investorPan) {
        log.info("🚀 [1/3] Starting Conviction Scoring for PAN: {}", investorPan);

        List<TaxLot> allLots = taxLotRepository.findByStatusAndSchemeFolioInvestorPan("OPEN", investorPan);
        Map<String, List<TaxLot>> lotsByAmfi = allLots.stream()
                .collect(Collectors.groupingBy(lot -> lot.getScheme().getAmfiCode()));

        var fundMetrics = convictionMetricsRepository.findMetricsForScoring(investorPan);
        log.info("📊 [2/3] Found {} funds to score.", fundMetrics.size());

        for (Map<String, Object> fund : fundMetrics) {
            String amfiCode = (String) fund.get("amfi_code");
            try {
                String assetCategory = safeStr(fund.get("asset_category"));
                boolean isDebtLike = isDebtLikeCategory(assetCategory);

                double sortino     = safeNum(fund.get("sortino_ratio"));
                double maxDrawdown = Math.abs(safeNum(fund.get("max_drawdown")));
                double navPercentile = safeNumDefault(fund.get("nav_percentile_3yr"), 0.5);
                double athDrawdown   = safeNum(fund.get("drawdown_from_ath"));
                double returnZScore  = safeNum(fund.get("return_z_score"));
                double cqs           = safeNumDefault(fund.get("composite_quant_score"), -1.0);

                double dynamicPersonalCagr = 0.0;
                double dynamicTaxDrag = 0.0;

                List<TaxLot> fundLots = lotsByAmfi.get(amfiCode);
                if (fundLots != null && !fundLots.isEmpty()) {
                    var details = navService.getLatestSchemeDetails(amfiCode);
                    double currentNav = (details != null && details.getNav() != null) ? details.getNav().doubleValue() : 0.0;
                    if (currentNav > 0) {
                        double totalCost = 0, totalValue = 0;
                        LocalDate oldestDate = LocalDate.now();
                        for (TaxLot lot : fundLots) {
                            double lCost = lot.getRemainingUnits().doubleValue() * lot.getCostBasisPerUnit().doubleValue();
                            totalCost  += lCost;
                            totalValue += lot.getRemainingUnits().doubleValue() * currentNav;
                            if (lot.getBuyDate().isBefore(oldestDate)) oldestDate = lot.getBuyDate();
                        }
                        double absoluteReturn = totalCost > 0 ? (totalValue - totalCost) / totalCost : 0;
                        double yearsInvested = Math.max(0.5, ChronoUnit.DAYS.between(oldestDate, LocalDate.now()) / 365.0);
                        dynamicPersonalCagr = Math.pow(1 + absoluteReturn, 1.0 / yearsInvested) - 1;
                        try {
                            String schemeName = fundLots.get(0).getScheme().getName();
                            TaxSimulationResult taxFriction = taxSimulator.simulateSellOrder(schemeName, totalValue, currentNav, investorPan);
                            dynamicTaxDrag = taxFriction.taxDragPercentage();
                        } catch (Exception ignored) {}

                    }
                }

                double yieldScore, riskScore, valueScore, painScore, frictionScore;

                if (isDebtLike) {
                    // ══════════════════════════════════════════════════════
                    // DEBT/ARBITRAGE SCORING MODEL
                    // These funds: monotonically increase, low drawdown is expected,
                    // their value is in stability + yield vs MAR, not price dips.
                    // ══════════════════════════════════════════════════════

                    // Yield: calibrated for debt returns (3–9% CAGR range)
                    yieldScore = normalize(dynamicPersonalCagr, 0.03, 0.09) * 100;

                    // Risk: Sortino still valid — a stable debt fund should have VERY high Sortino
                    // calibrate for 1–5 range (debt funds can have extremely high Sortino)
                    riskScore = (cqs >= 0) ? cqs : normalize(sortino, 0.5, 5.0) * 100;

                    // Value score for debt: Reward CONSISTENCY, not price dips.
                    // A high NAV percentile means the fund has been steadily compounding — that's GOOD.
                    // Use return Z-score instead: positive Z-score = outperforming peers = good.
                    double debtReturnConsistency = returnZScore > 0
                        ? Math.min(100, 50 + (returnZScore * 15))   // positive Z = above peer average
                        : Math.max(0,  50 + (returnZScore * 15));
                    valueScore = debtReturnConsistency;

                    // Pain: debt funds rarely have significant drawdown. Reward low MDD heavily.
                    // A debt fund with 0% drawdown should score 100 on this.
                    painScore = invertNormalize(maxDrawdown, 0.0, 0.05) * 100; // 0–5% range for debt

                    // Friction: same tax drag model, but debt funds have SLAB taxation post-Apr 2023
                    // so friction naturally higher — but the system already simulates this correctly.
                    frictionScore = invertNormalize(dynamicTaxDrag, 0.0, 0.30) * 100; // 0–30% slab drag range

                } else {
                    // ══════════════════════════════════════════════════════
                    // EQUITY / HYBRID SCORING MODEL (unchanged)
                    // ══════════════════════════════════════════════════════
                    yieldScore    = normalize(dynamicPersonalCagr, 0.05, 0.25) * 100;
                    riskScore     = (cqs >= 0) ? cqs : normalize(sortino, 0.5, 2.5) * 100;
                    valueScore    = calculateValueScore(navPercentile, athDrawdown, returnZScore, assetCategory);
                    painScore     = invertNormalize(maxDrawdown, 0.05, 0.35) * 100;
                    frictionScore = invertNormalize(dynamicTaxDrag, 0.0, 0.15) * 100;
                }

                double baseConviction = (yieldScore * WEIGHT_YIELD) + (riskScore * WEIGHT_RISK) +
                                        (valueScore * WEIGHT_VALUE) + (painScore * WEIGHT_PAIN) +
                                        (frictionScore * WEIGHT_FRICTION);

                int finalScore = (int) Math.round(baseConviction);
                finalScore = Math.max(1, Math.min(100, finalScore));

                convictionMetricsRepository.updateConvictionBreakdown(
                    finalScore, yieldScore, riskScore, valueScore, painScore, frictionScore, amfiCode);

                log.debug("Score [{}] cat={} debtLike={} → {}/100 (Y:{:.0f} R:{:.0f} V:{:.0f} P:{:.0f} F:{:.0f})",
                    amfiCode, assetCategory, isDebtLike, finalScore,
                    yieldScore, riskScore, valueScore, painScore, frictionScore);

            } catch (Exception e) {
                log.error("❌ Crash scoring AMFI {}", amfiCode, e);
            }
        }
        log.info("🏁 [3/3] Dynamic Conviction Scoring completed.");
    }

    /**
     * Returns true for debt, gilt, bond, liquid, money market, banking&psu, arbitrage categories.
     * These all share monotonically-increasing NAV behaviour and require different scoring.
     */
    private boolean isDebtLikeCategory(String assetCategory) {
        if (assetCategory == null) return false;
        String upper = assetCategory.toUpperCase();
        return upper.contains("DEBT") || upper.contains("GILT") || upper.contains("BOND")
            || upper.contains("LIQUID") || upper.contains("ARBITRAGE")
            || upper.contains("MONEY MARKET") || upper.contains("BANKING AND PSU")
            || upper.contains("CORPORATE") || upper.contains("OVERNIGHT")
            || upper.contains("ULTRA SHORT") || upper.contains("LOW DURATION");
    }

    // Safe helpers to replace repetitive null checks
    private double safeNum(Object o) { return o == null ? 0.0 : ((Number) o).doubleValue(); }
    private double safeNumDefault(Object o, double def) { return o == null ? def : ((Number) o).doubleValue(); }
    private String safeStr(Object o) { return o == null ? "" : o.toString(); }

    private double calculateValueScore(double navPercentile, double athDrawdown,
                                   double returnZScore, String assetCategory) {
        String cat = assetCategory != null ? assetCategory.toUpperCase() : "";

        // Debt, gilt, bond, gold — PE signals are meaningless. Return neutral.
        if (cat.contains("DEBT") || cat.contains("GILT") || cat.contains("BOND")
                || cat.contains("GOLD") || cat.contains("LIQUID")) {
            return 50.0;
        }

        // High navPercentile = near 3yr high = expensive. Invert it.
        double percentileScore = invertNormalize(navPercentile, 0.1, 0.9) * 100;

        // Deep ATH drawdown = opportunity. athDrawdown is negative (e.g. -0.22).
        // Math.abs gives the magnitude; higher magnitude = more of an opportunity.
        double athScore = normalize(Math.abs(athDrawdown), 0.0, 0.40) * 100;

        // Negative returnZScore = fund has had a poor recent year vs its own history = cheap.
        // Invert: lower Z → higher score.
        double returnZNorm = invertNormalize(returnZScore, -2.0, 2.0) * 100;

        // All active equity (index and active alike)
        return (percentileScore * 0.50) + (athScore * 0.30) + (returnZNorm * 0.20);
    }

    // Mathematical Helpers
    private double normalize(double value, double minBound, double maxBound) {
        if (value <= minBound) return 0;
        if (value >= maxBound) return 1;
        return (value - minBound) / (maxBound - minBound);
    }

    private double invertNormalize(double value, double minBound, double maxBound) {
        if (value <= minBound) return 1; 
        if (value >= maxBound) return 0;   
        return 1.0 - ((value - minBound) / (maxBound - minBound));
    }
}
