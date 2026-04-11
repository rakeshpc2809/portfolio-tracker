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

        List<TaxLot> allLots = taxLotRepository.findByStatusAndSchemeFolioInvestorPan("OPEN", investorPan);
        log.info("📦 Found {} total open lots for PAN: {}", allLots.size(), investorPan);
        
        Map<String, List<TaxLot>> lotsByAmfi = allLots.stream()
                .collect(Collectors.groupingBy(lot -> sanitizeAmfi(lot.getScheme().getAmfiCode())));
        log.info("📦 Grouped into {} unique AMFI codes from tax lots.", lotsByAmfi.size());

        var fundMetrics = convictionMetricsRepository.findMetricsForScoring(investorPan);
        log.info("📊 [2/3] Found {} funds in fund_conviction_metrics to score.", fundMetrics.size());

        if (fundMetrics.isEmpty()) {
            log.warn("⚠️ No metrics found for scoring! Check if nightly engine has run and AMFI codes match.");
        }

        for (Map<String, Object> fund : fundMetrics) {
            String amfiCode = sanitizeAmfi((String) fund.get("amfi_code"));
            log.info("🔎 Scoring fund: {} (AMFI: {})", fund.get("amfi_code"), amfiCode);
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
                    log.info("  ├─ Found {} lots for this fund.", fundLots.size());
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
                        log.info("  ├─ CAGR: {:.2f}%, Abs Return: {:.2f}%", dynamicPersonalCagr * 100, absoluteReturn * 100);
                        try {
                            String schemeName = fundLots.get(0).getScheme().getName();
                            TaxSimulationResult taxFriction = taxSimulator.simulateSellOrder(schemeName, totalValue, currentNav, investorPan);
                            dynamicTaxDrag = taxFriction.taxDragPercentage();
                            log.info("  ├─ Tax Drag: {:.2f}%", dynamicTaxDrag * 100);
                        } catch (Exception ignored) {}

                    }
                } else {
                    log.warn("  ├─ ⚠️ No lots found for AMFI {} in the lotsByAmfi map!", amfiCode);
                }

                double yieldScore, riskScore, valueScore, painScore, frictionScore;

                if (isDebtLike) {
                    yieldScore = normalize(dynamicPersonalCagr, 0.03, 0.09) * 100;
                    riskScore = (cqs >= 0) ? cqs : normalize(sortino, 0.5, 5.0) * 100;
                    double debtReturnConsistency = returnZScore > 0
                        ? Math.min(100, 50 + (returnZScore * 15))
                        : Math.max(0,  50 + (returnZScore * 15));
                    valueScore = debtReturnConsistency;
                    painScore = invertNormalize(maxDrawdown, 0.0, 0.05) * 100;
                    frictionScore = invertNormalize(dynamicTaxDrag, 0.0, 0.30) * 100;
                } else {
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

    private boolean isDebtLikeCategory(String assetCategory) {
        if (assetCategory == null) return false;
        String upper = assetCategory.toUpperCase();
        return upper.contains("DEBT") || upper.contains("GILT") || upper.contains("BOND")
            || upper.contains("LIQUID") || upper.contains("ARBITRAGE")
            || upper.contains("MONEY MARKET") || upper.contains("BANKING AND PSU")
            || upper.contains("CORPORATE") || upper.contains("OVERNIGHT")
            || upper.contains("ULTRA SHORT") || upper.contains("LOW DURATION");
    }

    private double safeNum(Object o) { return o == null ? 0.0 : ((Number) o).doubleValue(); }
    private double safeNumDefault(Object o, double def) { return o == null ? def : ((Number) o).doubleValue(); }
    private String safeStr(Object o) { return o == null ? "" : o.toString(); }

    private double calculateValueScore(double navPercentile, double athDrawdown,
                                   double returnZScore, String assetCategory) {
        String cat = assetCategory != null ? assetCategory.toUpperCase() : "";
        if (cat.contains("DEBT") || cat.contains("GILT") || cat.contains("BOND")
                || cat.contains("GOLD") || cat.contains("LIQUID")) {
            return 50.0;
        }
        double percentileScore = invertNormalize(navPercentile, 0.1, 0.9) * 100;
        double athScore = normalize(Math.abs(athDrawdown), 0.0, 0.40) * 100;
        double returnZNorm = invertNormalize(returnZScore, -2.0, 2.0) * 100;
        return (percentileScore * 0.50) + (athScore * 0.30) + (returnZNorm * 0.20);
    }

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

    private String sanitizeAmfi(String amfi) {
        if (amfi == null) return "";
        String s = amfi.trim();
        return s.replaceFirst("^0+(?!$)", "");
    }
}
