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
                double sortino = fund.get("sortino_ratio") != null ? ((Number) fund.get("sortino_ratio")).doubleValue() : 0.0;
                double maxDrawdown = fund.get("max_drawdown") != null ? Math.abs(((Number) fund.get("max_drawdown")).doubleValue()) : 0.0;
                
                double navPercentile  = fund.get("nav_percentile_3yr")  != null
                    ? ((Number) fund.get("nav_percentile_3yr")).doubleValue()  : 0.5;
                double athDrawdown    = fund.get("drawdown_from_ath")    != null
                    ? ((Number) fund.get("drawdown_from_ath")).doubleValue()   : 0.0;
                double returnZScore   = fund.get("return_z_score")       != null
                    ? ((Number) fund.get("return_z_score")).doubleValue()      : 0.0;
                String assetCategory  = (String) fund.get("asset_category");

                double valueScore = calculateValueScore(navPercentile, athDrawdown, returnZScore, assetCategory);

                double dynamicPersonalCagr = 0.0;
                double dynamicTaxDrag = 0.0;
                
                List<TaxLot> fundLots = lotsByAmfi.get(amfiCode);
                if (fundLots != null && !fundLots.isEmpty()) {
                    var details = navService.getLatestSchemeDetails(amfiCode);
                    double currentNav = (details != null && details.getNav() != null) ? details.getNav().doubleValue() : 0.0;
                    
                    if (currentNav > 0) {
                        double totalCost = 0;
                        double totalValue = 0;
                        LocalDate oldestDate = LocalDate.now();
                        
                        for (TaxLot lot : fundLots) {
                            double lotCost = lot.getRemainingUnits().doubleValue() * lot.getCostBasisPerUnit().doubleValue();
                            totalCost += lotCost;
                            totalValue += lot.getRemainingUnits().doubleValue() * currentNav;
                            if (lot.getBuyDate().isBefore(oldestDate)) oldestDate = lot.getBuyDate();
                        }
                        
                        double absoluteReturn = (totalValue - totalCost) / totalCost;
                        double yearsInvested = Math.max(0.5, ChronoUnit.DAYS.between(oldestDate, LocalDate.now()) / 365.0);
                        dynamicPersonalCagr = Math.pow(1 + absoluteReturn, 1 / yearsInvested) - 1; 

                        try {
                            String schemeName = fundLots.get(0).getScheme().getName();
                            TaxSimulationResult taxFriction = taxSimulator.simulateSellOrder(schemeName, totalValue, currentNav);
                            dynamicTaxDrag = taxFriction.taxDragPercentage();
                        } catch (Exception e) {
                            // Ignored
                        }
                    }
                }

                // 3. BASE SCORING MATH
                double yieldScore = normalize(dynamicPersonalCagr, 0.05, 0.25) * 100; 
                double riskScore = normalize(sortino, 0.5, 2.5) * 100; 
                double painScore = invertNormalize(maxDrawdown, 0.05, 0.35) * 100; 
                double frictionScore = invertNormalize(dynamicTaxDrag, 0.0, 0.15) * 100; 

                double baseConviction = (yieldScore * WEIGHT_YIELD) + (riskScore * WEIGHT_RISK) + 
                                        (valueScore * WEIGHT_VALUE) +
                                        (painScore * WEIGHT_PAIN) + (frictionScore * WEIGHT_FRICTION);

                // 5. Final Calculation
                int finalScore = (int) Math.round(baseConviction);
                finalScore = Math.max(1, Math.min(100, finalScore)); 

                // 6. Repository Update (Enhanced Breakdown for Design 5)
                convictionMetricsRepository.updateConvictionBreakdown(finalScore, yieldScore, riskScore, 
                    valueScore, painScore, frictionScore, amfiCode);

            } catch (Exception e) {
                log.error("❌ Crash scoring AMFI {}", amfiCode, e);
            }
        }
        log.info("🏁 [3/3] Dynamic Conviction Scoring completed.");
    }

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
