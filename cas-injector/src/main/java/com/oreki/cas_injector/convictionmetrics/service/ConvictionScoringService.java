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
    private static final double WEIGHT_VALUE = 0.25;      // 🚀 INCREASED: Now powered by Z-Score
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
            String benchName = (String) fund.get("benchmark_index");
            
            try {
                double sortino = fund.get("sortino_ratio") != null ? ((Number) fund.get("sortino_ratio")).doubleValue() : 0.0;
                double maxDrawdown = fund.get("max_drawdown") != null ? Math.abs(((Number) fund.get("max_drawdown")).doubleValue()) : 0.0;
                double coveragePct = fund.get("coverage_pct") != null ? ((Number) fund.get("coverage_pct")).doubleValue() : 0.0;
                
                double fundPe = fund.get("fund_pe") != null ? ((Number) fund.get("fund_pe")).doubleValue() : 0.0;
                double benchPe = fund.get("bench_pe") != null ? ((Number) fund.get("bench_pe")).doubleValue() : 25.0; // Default Nifty PE approx
                double fundPb = fund.get("fund_pb") != null ? ((Number) fund.get("fund_pb")).doubleValue() : 0.0;
                double benchPb = fund.get("bench_pb") != null ? ((Number) fund.get("bench_pb")).doubleValue() : 3.5;

                // 🚀 A. CALCULATE Z-SCORE (1-Year Rolling Window)
                double zScore = calculateZScore(amfiCode, benchName, fundPe, benchPe);

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

                // 🚀 REVISED VALUE SCORE: Incorporating Z-Score with Fallback
                double peRelative = fundPe > 0 ? benchPe / fundPe : 1.0;
                double relativePart = normalize(peRelative, 0.7, 1.5);
                
                double valueScore;
                if (zScore != 0.0) {
                    // If Z-Score is available, use it as the primary signal (70/30 split)
                    double zScorePart = invertNormalize(zScore, -2.0, 2.0); 
                    valueScore = ((zScorePart * 0.7) + (relativePart * 0.3)) * 100;
                } else {
                    // If Z-Score is 0 (new fund/no history), rely 100% on relative PE vs benchmark
                    valueScore = relativePart * 100;
                }

                String valStatus = determineValuationStatus(zScore, peRelative);

                double baseConviction = (yieldScore * WEIGHT_YIELD) + (riskScore * WEIGHT_RISK) + 
                                        (valueScore * WEIGHT_VALUE) +
                                        (painScore * WEIGHT_PAIN) + (frictionScore * WEIGHT_FRICTION);

                // 🚀 4. THE VALUE HUNTER (REBOUND ALPHA BONUS)
                double reboundBonus = 0;
                if (sortino > 1.2 && maxDrawdown > 0.15) {
                    double discountDepth = normalize(maxDrawdown, 0.15, 0.35);
                    reboundBonus = discountDepth * 20.0; 
                }

                // 5. Final Calculation
                int finalScore = (int) Math.round(baseConviction + reboundBonus);
                finalScore = Math.max(1, Math.min(100, finalScore)); 

                // 6. Repository Update
                convictionMetricsRepository.updateConvictionScore(finalScore, fundPe, fundPb, zScore, coveragePct, valStatus, amfiCode);

            } catch (Exception e) {
                log.error("❌ Crash scoring AMFI {}", amfiCode, e);
            }
        }
        log.info("🏁 [3/3] Dynamic Conviction Scoring completed.");
    }

    private String determineValuationStatus(double zScore, double peRelative) {
        if (zScore != 0.0) {
            if (zScore < -1.5) return "DEEP VALUE";
            if (zScore < -0.5) return "CHEAP";
            if (zScore > 1.5) return "EXPENSIVE";
            if (zScore > 0.5) return "PREMIUM";
            return "FAIR";
        } else {
            // Fallback to relative PE if no history for Z-Score
            if (peRelative > 1.3) return "CHEAP (REL)";
            if (peRelative > 1.1) return "VALUE (REL)";
            if (peRelative < 0.7) return "EXPENSIVE (REL)";
            if (peRelative < 0.9) return "PREMIUM (REL)";
            return "FAIR (REL)";
        }
    }

    private double calculateZScore(String amfiCode, String benchmarkIndex, double currentFundPe, double currentBenchPe) {
        if (currentFundPe <= 0 || currentBenchPe <= 0) return 0.0;

        List<Double> spreads = convictionMetricsRepository.findHistoricalSpread(benchmarkIndex, amfiCode);
        
        if (spreads.size() < 10) return 0.0; // Need minimum history for meaningful Z-Score

        double currentSpread = currentFundPe - currentBenchPe;
        double mean = spreads.stream().mapToDouble(d -> d).average().orElse(0.0);
        double variance = spreads.stream().mapToDouble(d -> Math.pow(d - mean, 2)).average().orElse(0.0);
        double stdDev = Math.sqrt(variance);

        if (stdDev == 0) return 0.0;
        return (currentSpread - mean) / stdDev;
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