package com.oreki.cas_injector.convictionmetrics.service;


import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.oreki.cas_injector.backfill.service.NavService;
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

    private final JdbcTemplate jdbcTemplate;
    private final TaxLotRepository taxLotRepository;
    private final NavService navService;
    private final TaxSimulatorService taxSimulator;

    // 🔬 REVISED INSTITUTIONAL WEIGHTS
    private static final double WEIGHT_YIELD = 0.35;      // Reduced slightly to make room for Value
    private static final double WEIGHT_RISK = 0.35;       // Keeps quality standards high
    private static final double WEIGHT_PAIN = 0.15;       // Base structural penalty
    private static final double WEIGHT_FRICTION = 0.15;   // Increased tax awareness

    public void calculateAndSaveFinalScores(String investorPan) {
        log.info("🚀 [1/3] Starting Conviction Scoring for PAN: {}", investorPan);

        List<TaxLot> allLots = taxLotRepository.findByStatusAndSchemeFolioInvestorPan("OPEN", investorPan);
        Map<String, List<TaxLot>> lotsByAmfi = allLots.stream()
                .collect(Collectors.groupingBy(lot -> lot.getScheme().getAmfiCode()));

        String fetchSql = """
           SELECT m.amfi_code, m.sortino_ratio, m.max_drawdown, m.calculation_date
           FROM fund_conviction_metrics m
           WHERE m.calculation_date = (SELECT MAX(calculation_date) FROM fund_conviction_metrics)
           AND m.amfi_code IN (
               SELECT s.amfi_code 
               FROM scheme s 
               JOIN folio f ON s.folio_id = f.id 
               WHERE f.investor_pan = ?
           )
        """;

        var fundMetrics = jdbcTemplate.queryForList(fetchSql, investorPan);
        log.info("📊 [2/3] Found {} funds to score.", fundMetrics.size());

        for (Map<String, Object> fund : fundMetrics) {
            String amfiCode = (String) fund.get("amfi_code");
            
            try {
                double sortino = fund.get("sortino_ratio") != null ? ((Number) fund.get("sortino_ratio")).doubleValue() : 0.0;
                double maxDrawdown = fund.get("max_drawdown") != null ? Math.abs(((Number) fund.get("max_drawdown")).doubleValue()) : 0.0;

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
                double riskScore = normalize(sortino, 0.5, 2.5) * 100; // Raised floor to demand higher quality
                double painScore = invertNormalize(maxDrawdown, 0.05, 0.35) * 100; // Normal penalty for drops
                double frictionScore = invertNormalize(dynamicTaxDrag, 0.0, 0.15) * 100; 

                double baseConviction = (yieldScore * WEIGHT_YIELD) + (riskScore * WEIGHT_RISK) + 
                                        (painScore * WEIGHT_PAIN) + (frictionScore * WEIGHT_FRICTION);

                // 🚀 4. THE VALUE HUNTER (REBOUND ALPHA BONUS)
                // If the fund is historically High Quality (Sortino > 1.2) 
                // BUT it is currently bleeding (Drawdown > 15%), it is a premium asset on sale.
                double reboundBonus = 0;
                if (sortino > 1.2 && maxDrawdown > 0.15) {
                    // Calculate how deep the discount is (Max 20 bonus points)
                    // A 15% drop gives small bonus, a 30% drop gives max bonus.
                    double discountDepth = normalize(maxDrawdown, 0.15, 0.35);
                    reboundBonus = discountDepth * 20.0; 
                    log.info("💎 Deep Value Detected for {}: Sortino {}, Drawdown {}%. Awarding +{} Rebound Bonus.", 
                             amfiCode, sortino, maxDrawdown * 100, Math.round(reboundBonus));
                }

                // 5. Final Calculation
                int finalScore = (int) Math.round(baseConviction + reboundBonus);
                finalScore = Math.max(1, Math.min(100, finalScore)); // Clamp between 1 and 100

                // 6. SQL Update
                String updateSql = """
                    UPDATE fund_conviction_metrics 
                    SET conviction_score = ? 
                    WHERE amfi_code = ? 
                    AND calculation_date = (SELECT MAX(calculation_date) FROM fund_conviction_metrics)
                """;
                
                jdbcTemplate.update(updateSql, finalScore, amfiCode);

            } catch (Exception e) {
                log.error("❌ Crash scoring AMFI {}", amfiCode, e);
            }
        }
        log.info("🏁 [3/3] Dynamic Conviction Scoring completed.");
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