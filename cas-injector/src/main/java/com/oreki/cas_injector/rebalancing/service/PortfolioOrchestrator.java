package com.oreki.cas_injector.rebalancing.service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.oreki.cas_injector.backfill.service.NavService;
import com.oreki.cas_injector.convictionmetrics.dto.MarketMetrics;
import com.oreki.cas_injector.convictionmetrics.service.ConvictionScoringService;
import com.oreki.cas_injector.core.GoogleSheetService;
import com.oreki.cas_injector.core.dto.AggregatedHolding;
import com.oreki.cas_injector.core.model.Scheme;
import com.oreki.cas_injector.core.repository.SchemeRepository;
import com.oreki.cas_injector.core.service.SystemicRiskMonitorService;
import com.oreki.cas_injector.rebalancing.dto.StrategyTarget;
import com.oreki.cas_injector.rebalancing.dto.TacticalSignal;
import com.oreki.cas_injector.taxmanagement.service.TaxSimulatorService;
import com.oreki.cas_injector.transactions.model.TaxLot;
import com.oreki.cas_injector.transactions.repository.TaxLotRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
public class PortfolioOrchestrator {
    
    private final GoogleSheetService strategyService;
    private final NavService amfiService; 
    private final RebalanceEngine engine;
    private final TaxLotRepository taxLotRepository; 
    private final SchemeRepository schemeRepository;
    private final TaxSimulatorService taxSimulator;
    private final JdbcTemplate jdbcTemplate;
    private final SystemicRiskMonitorService systemicRiskMonitor;
    private final PositionSizingService positionSizingService;
    private final ConvictionScoringService convictionScoringService;
 

 public List<TacticalSignal> generateDailySignals(String investorPan, double monthlySip, double lumpsum) {
        
        // 1. Prime the Database
        convictionScoringService.calculateAndSaveFinalScores(investorPan); 
        
        List<StrategyTarget> targets = strategyService.fetchLatestStrategy();
        Map<String, MarketMetrics> liveMetricsMap = fetchLiveMetricsMap(investorPan);
        List<TaxLot> allLots = taxLotRepository.findByStatusAndSchemeFolioInvestorPan("OPEN", investorPan);
        List<AggregatedHolding> myHoldings = aggregateLots(allLots);
        
        double totalPortfolioValue = myHoldings.stream().mapToDouble(AggregatedHolding::getCurrentValue).sum();

        // 🌟 THE FIX: MERGE TARGETS AND HOLDINGS
        // We must evaluate everything you own AND everything you plan to buy
        Set<String> allSchemeNames = new HashSet<>();
        myHoldings.forEach(h -> allSchemeNames.add(h.getSchemeName().toLowerCase().trim()));
        targets.forEach(t -> allSchemeNames.add(t.schemeName().toLowerCase().trim()));

        // ==========================================
        // 🌟 PASS 1: THE HARVESTER (Pool Capital)
        // ==========================================
        double totalDeployableCash = monthlySip + lumpsum;
        List<TacticalSignal> draftSignals = new ArrayList<>();
        
        for (String normalizedName : allSchemeNames) {
            
            // Get Holding (if you own it) or create empty holding
          AggregatedHolding holding = myHoldings.stream()
                .filter(h -> h.getSchemeName().toLowerCase().trim().equals(normalizedName))
                .findFirst().orElse(new AggregatedHolding(
                    targets.stream()
                           .filter(t -> t.schemeName().toLowerCase().trim().equals(normalizedName))
                           .findFirst()
                           .map(StrategyTarget::schemeName)
                           .orElse(normalizedName), // 1. schemeName
                    0.0,        // 2. units
                    0.0,        // 3. currentValue
                    0.0,        // 4. investedAmount
                    0.0,        // 5. ltcgAmount
                    0.0,        // 6. stcgAmount
                    0.0,
                    0.0,
                    0,
                    0,          // 7. oldestAgeDays
                    "UNKNOWN",  // 8. assetCategory
                    "DROPPED",   // 9. status
                    "UNKNOWN" // 8. assetCategory

                ));
            // Get Target (if it's in the strategy sheet) or create 0% target
           StrategyTarget target = targets.stream()
    .filter(t -> t.schemeName().toLowerCase().trim().equals(normalizedName))
    .findFirst()
    .orElse(new StrategyTarget(
        "", 
        holding.getSchemeName(), 
        0.0, 
        0.0, 
        "UNTRACKED" // 🚀 Change from "DROPPED" to "UNTRACKED"
    ));      
            // Fetch Metrics
            Scheme scheme = schemeRepository.findByNameIgnoreCase(holding.getSchemeName()).orElse(null);
            String amfiCode = scheme != null ? scheme.getAmfiCode() : "";
            MarketMetrics metrics = liveMetricsMap.getOrDefault(amfiCode, new MarketMetrics(0,0,0,0,0,0.5,0,0, LocalDate.of(1970,1,1)));
            
            // Run Base V1 Engine
            TacticalSignal rawSignal = engine.evaluate(holding, target, metrics, totalPortfolioValue, amfiCode);
            List<String> justifications = new ArrayList<>(rawSignal.justifications());
            
            // 🛡️ OFFENSIVE TAX STRATEGY & EXITS
        // 🛡️ UPGRADED TAX STRATEGY: LTCG HARVESTING & STCG LOCK
// Check if the engine wants to EXIT or if the Google Sheet says it's DROPPED
boolean isDropped = "DROPPED".equalsIgnoreCase(target.status());

if ("EXIT".equalsIgnoreCase(rawSignal.action()) || "DROPPED".equalsIgnoreCase(target.status())) {
    
    double ltcgValue = holding.getLtcgValue(); // 🚀 Sum of values of lots > 365 days
    double stcgValue = holding.getStcgValue(); // 🚀 Sum of values of lots <= 365 days
    double harvestableGains = holding.getLtcgAmount(); // The actual profit in the LTCG lots

    if (ltcgValue > 0) {
        justifications.add(String.format("✅ Surgical Exit: Harvesting ₹%,.0f in LTCG units (Profit: ₹%,.0f).", 
                          ltcgValue, harvestableGains));
        totalDeployableCash += ltcgValue;
    }

    if (stcgValue > 0) {
        int daysToNextMaturing = holding.getDaysToNextLtcg(); 
        justifications.add(String.format("🛡️ STCG Shield: Locking ₹%,.0f in units to avoid 20%% tax. Next harvest in %d days.", 
                          stcgValue, daysToNextMaturing));
        // We DO NOT add stcgValue to totalDeployableCash
    }

    // The final signal amount is ONLY the LTCG portion
    String finalAmount = String.valueOf(ltcgValue);
    String action = ltcgValue > 0 ? "EXIT" : "HOLD";
    
    draftSignals.add(createSignal(rawSignal, action, finalAmount, justifications));
}
            // 💡 TAX LOSS HARVESTING
            else if ("HOLD".equalsIgnoreCase(rawSignal.action()) && holding.getStcgAmount() < -10000) {
                 justifications.add(String.format("💡 Tax Opportunity: You have a ₹%,.0f short-term loss. Consider harvesting.", Math.abs(holding.getStcgAmount())));
                 draftSignals.add(createSignal(rawSignal, "HOLD", "0", justifications));
            }
            else {
                draftSignals.add(createSignal(rawSignal, rawSignal.action(), rawSignal.amount(), justifications));
            }
        }

        // ==========================================
        // 🌟 PASS 2: THE SNIPER (Distribute Capital)
        // ==========================================
        double totalRiskAdjustedDemand = 0.0;
        
        for (TacticalSignal sig : draftSignals) {
            if ("BUY".equalsIgnoreCase(sig.action())) {
                double baseDeficit = Double.parseDouble(sig.amount().replace(",", ""));
                double scoreMult = Math.max(0.2, sig.convictionScore() / 100.0);
                totalRiskAdjustedDemand += (baseDeficit * scoreMult);
            }
        }

        double finalCashPool = totalDeployableCash;
        double finalDemand = totalRiskAdjustedDemand;

        return draftSignals.stream().map(sig -> {
            if (!"BUY".equalsIgnoreCase(sig.action())) return sig;

            List<String> justs = new ArrayList<>(sig.justifications());
            double baseDeficit = Double.parseDouble(sig.amount().replace(",", ""));
            double scoreMult = Math.max(0.2, sig.convictionScore() / 100.0);
            double riskAdjustedDemand = baseDeficit * scoreMult;
            
            double allocatedAmount = 0.0;
            
            if (finalCashPool > 0 && finalDemand > 0) {
                double shareOfPool = riskAdjustedDemand / finalDemand;
                allocatedAmount = finalCashPool * shareOfPool;
                
                // 🐛 THE FIX: Strictly cap the allocation at the Risk-Adjusted Demand. 
                // Never dump excess cash into a weak fund just because the cash is available.
                allocatedAmount = Math.min(allocatedAmount, riskAdjustedDemand);
                
            } else if (finalCashPool <= 0) {
                // If you have 0 cash, convert BUYs to HOLDs
                justs.add("⚠️ Capital Exhausted: No liquidity available to execute this buy.");
                return createSignal(sig, "HOLD", "0", justs);
            }

            justs.add(String.format("💰 Capital Pool: Drawing from ₹%,.0f total available liquidity.", finalCashPool));
            justs.add(String.format("⚖️ Risk-Sized: Base Deficit ₹%,.0f. Applied %.2fx Conviction Multiplier. Final Allocation: ₹%,.0f.", 
                      baseDeficit, scoreMult, allocatedAmount));

            return createSignal(sig, "BUY", String.format("%.2f", allocatedAmount), justs);
            
        }).collect(Collectors.toList());
    }

    private TacticalSignal createSignal(TacticalSignal s, String action, String amt, List<String> justs) {
        return new TacticalSignal(s.schemeName(), s.amfiCode(), action, amt, s.plannedPercentage(), s.actualPercentage(), s.sipPercentage(), s.fundStatus(), s.convictionScore(), s.sortinoRatio(), s.maxDrawdown(), s.navPercentile3yr(), s.drawdownFromAth(), s.returnZScore(), s.lastBuyDate(), justs);
    }

   private Map<String, MarketMetrics> fetchLiveMetricsMap(String pan) {
        // 1. Fetch Conviction Metrics (NAV Signals replaces PE/PB)
        String sql = """
            SELECT m.amfi_code, m.sortino_ratio, m.cvar_5, m.win_rate, m.max_drawdown, 
                   m.conviction_score, m.nav_percentile_3yr, m.drawdown_from_ath, m.return_z_score
            FROM fund_conviction_metrics m
            JOIN scheme s ON m.amfi_code = s.amfi_code 
            JOIN folio f ON s.folio_id = f.id
            WHERE f.investor_pan = ?
            AND m.calculation_date = (SELECT MAX(calculation_date) FROM fund_conviction_metrics)
            """;
        
        // 2. Fetch Last Buy Date per Scheme
        String lastBuySql = """
            SELECT s.amfi_code, MAX(t.transaction_date) as last_buy
            FROM transaction t
            JOIN scheme s ON t.scheme_id = s.id
            JOIN folio f ON s.folio_id = f.id
            WHERE f.investor_pan = ? AND t.transaction_type = 'BUY'
            GROUP BY s.amfi_code
        """;

        Map<String, LocalDate> lastBuyDates = jdbcTemplate.queryForList(lastBuySql, pan).stream()
            .collect(Collectors.toMap(
                r -> (String) r.get("amfi_code"),
                r -> r.get("last_buy") != null ? ((java.sql.Date) r.get("last_buy")).toLocalDate() : LocalDate.of(1970, 1, 1)
            ));

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, pan);
        Map<String, MarketMetrics> map = new HashMap<>();
        for (Map<String, Object> r : rows) {
            String amfi = (String) r.get("amfi_code");
            map.put(amfi, new MarketMetrics(
                r.get("conviction_score") != null ? ((Number)r.get("conviction_score")).intValue() : 0,
                r.get("sortino_ratio") != null ? ((Number)r.get("sortino_ratio")).doubleValue() : 0.0,
                r.get("cvar_5") != null ? ((Number)r.get("cvar_5")).doubleValue() : 0.0,
                r.get("win_rate") != null ? ((Number)r.get("win_rate")).doubleValue() : 0.0,
                r.get("max_drawdown") != null ? ((Number)r.get("max_drawdown")).doubleValue() : 0.0,
                r.get("nav_percentile_3yr") != null ? ((Number)r.get("nav_percentile_3yr")).doubleValue() : 0.5,
                r.get("drawdown_from_ath") != null ? ((Number)r.get("drawdown_from_ath")).doubleValue() : 0.0,
                r.get("return_z_score") != null ? ((Number)r.get("return_z_score")).doubleValue() : 0.0,
                lastBuyDates.getOrDefault(amfi, LocalDate.of(1970, 1, 1))
            ));
        }
        return map;
    }

    private List<AggregatedHolding> aggregateLots(List<TaxLot> lots) {
        Map<Scheme, List<TaxLot>> groupedLots = lots.stream().collect(Collectors.groupingBy(TaxLot::getScheme));
        return groupedLots.entrySet().stream().map(entry -> {
            Scheme scheme = entry.getKey();
            double liveNav = amfiService.getLatestSchemeDetails(scheme.getAmfiCode()) != null ? amfiService.getLatestSchemeDetails(scheme.getAmfiCode()).getNav().doubleValue() : 0.0;

         double units = 0, cost = 0, val = 0, ltcgGains = 0, stcgGains = 0;
            double ltcgVal = 0, stcgVal = 0;
            int minDaysToLtcg = 365; // Start high and find the minimum
            LocalDate oldest = LocalDate.now();

            for (TaxLot lot : entry.getValue()) {
                double lUnits = lot.getRemainingUnits().doubleValue();
                double lCost = lot.getCostBasisPerUnit().doubleValue() * lUnits;
                double lVal = lUnits * liveNav;
                
                units += lUnits; cost += lCost; val += lVal;
                if (lot.getBuyDate().isBefore(oldest)) oldest = lot.getBuyDate();

                long age = ChronoUnit.DAYS.between(lot.getBuyDate(), LocalDate.now());
                double gain = lVal - lCost;
                
                // Asset Category Logic
                String cat = (scheme.getAssetCategory() != null) ? scheme.getAssetCategory().toUpperCase() : "EQUITY";
                boolean isLtcg = (age > 365); // Simplified for Equity/ELSS

                if (isLtcg) {
                    ltcgVal += lVal;
                    ltcgGains += Math.max(0, gain);
                } else {
                    stcgVal += lVal;
                    stcgGains += Math.max(0, gain);
                    // 🚀 FIX: Calculate days remaining for this specific lot
                    int daysLeft = 365 - (int) age;
                    if (daysLeft < minDaysToLtcg) minDaysToLtcg = daysLeft;
                }
            }
            
            // If there are no STCG lots, days to next should be 0
            int finalDaysToNext = (stcgVal > 0) ? minDaysToLtcg : 0;

            // 🚀 MATCH THE 13-ARGUMENT CONSTRUCTOR EXACTLY
            return new AggregatedHolding(
                scheme.getName(),       // 1
                units,                  // 2
                val,                    // 3
                cost,                   // 4
                ltcgVal,                // 5
                ltcgGains,              // 6
                stcgVal,                // 7
                stcgGains,              // 8
                finalDaysToNext,        // 9 (Calculated!)
                (int)ChronoUnit.DAYS.between(oldest, LocalDate.now()), // 10
                scheme.getAssetCategory(), // 11
                "ACTIVE",               // 12
                scheme.getIsin()        // 13
            );
        }).collect(Collectors.toList());
    }
}
