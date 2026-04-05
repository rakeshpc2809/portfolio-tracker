package com.oreki.cas_injector.rebalancing.service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
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
import com.oreki.cas_injector.core.dto.SchemeDetailsDTO;
import com.oreki.cas_injector.core.model.Scheme;
import com.oreki.cas_injector.core.repository.SchemeRepository;
import com.oreki.cas_injector.core.service.SystemicRiskMonitorService;
import com.oreki.cas_injector.rebalancing.dto.SipLineItem;
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
    private final ConvictionScoringService convictionScoringService;

    // ==========================================
    // 🌟 MODE 1: MONTHLY SIP PLAN (Pure Sheet)
    // ==========================================
    public List<SipLineItem> computeSipPlan(String pan, double sipAmount) {
        List<StrategyTarget> targets = strategyService.fetchLatestStrategy();
        Map<String, MarketMetrics> metrics = fetchLiveMetricsMap(pan);

        return targets.stream()
            .filter(t -> t.sipPct() > 0 && !"dropped".equalsIgnoreCase(t.status()))
            .map(t -> {
                double amount = (t.sipPct() / 100.0) * sipAmount;
                Scheme scheme = schemeRepository.findByIsin(t.isin()).orElse(null);
                String amfiCode = scheme != null ? scheme.getAmfiCode() : "";
                MarketMetrics m = metrics.getOrDefault(amfiCode, defaultMetrics());

                String flag = m.navPercentile3yr() > 0.85 ? "CAUTION_EXPENSIVE" : "DEPLOY";
                String note = flag.equals("CAUTION_EXPENSIVE")
                    ? "Fund at " + Math.round(m.navPercentile3yr()*100) + "% of 3yr range. Consider deferring."
                    : "Deploy as per strategy.";

                return new SipLineItem(t.schemeName(), t.isin(), amfiCode,
                    amount, t.sipPct(), t.status(), flag, note);
            })
            .sorted(Comparator.comparingDouble(SipLineItem::amount).reversed())
            .collect(Collectors.toList());
    }

    // ==========================================
    // 🌟 MODE 2: OPPORTUNISTIC (Accumulators & Extra)
    // ==========================================
    public List<TacticalSignal> computeOpportunisticSignals(String pan, double lumpsum) {
        convictionScoringService.calculateAndSaveFinalScores(pan); 
        
        List<StrategyTarget> targets = strategyService.fetchLatestStrategy();
        Map<String, MarketMetrics> metricsMap = fetchLiveMetricsMap(pan);
        List<TaxLot> allLots = taxLotRepository.findByStatusAndSchemeFolioInvestorPan("OPEN", pan);
        List<AggregatedHolding> holdings = aggregateLots(allLots);
        
        double totalPortfolioValue = holdings.stream().mapToDouble(AggregatedHolding::getCurrentValue).sum();
        double deployableCash = lumpsum;
        
        // Add Arbitrage/Rebalancer value to deployable pool if entry points are good
        double arbitrageValue = holdings.stream()
            .filter(h -> h.getAssetCategory().contains("ARBITRAGE"))
            .mapToDouble(AggregatedHolding::getCurrentValue)
            .sum();

        List<TacticalSignal> opportunisticDrafts = new ArrayList<>();

        for (StrategyTarget target : targets) {
            AggregatedHolding holding = findHolding(holdings, target);
            MarketMetrics metrics = metricsMap.getOrDefault(amfiCodeFor(target), defaultMetrics());
            double actualPct = (holding.getCurrentValue() / totalPortfolioValue) * 100;

            // 1. ACCUMULATOR LOGIC (Buy on dips)
            if ("accumulator".equalsIgnoreCase(target.status())) {
                boolean isUnderTarget = actualPct < target.targetPortfolioPct();
                boolean isNearLow = metrics.navPercentile3yr() < 0.40;
                
                if (isUnderTarget && isNearLow) {
                    List<String> justs = List.of(String.format("🎯 Accumulator Entry: Fund at %d%% of 3yr range. Good opportunistic dip.", 
                        Math.round(metrics.navPercentile3yr() * 100)));
                    
                    // Base amount is drift gap or 2x SIP default
                    double baseAmount = Math.max(sipAmountFromTarget(target, 75000) * 2, (target.targetPortfolioPct() - actualPct) / 100.0 * totalPortfolioValue);
                    opportunisticDrafts.add(new TacticalSignal(target.schemeName(), amfiCodeFor(target), "BUY", String.valueOf(baseAmount), 
                        target.targetPortfolioPct(), actualPct, target.sipPct(), target.status(), metrics.convictionScore(), 
                        metrics.sortinoRatio(), metrics.maxDrawdown(), metrics.navPercentile3yr(), metrics.drawdownFromAth(), 
                        metrics.returnZScore(), metrics.lastBuyDate(), justs));
                }
            }

            // 2. REBALANCER DEPLOY (Core/Strategy significant drift)
            if ("core".equalsIgnoreCase(target.status()) || "strategy".equalsIgnoreCase(target.status())) {
                double drift = actualPct - target.targetPortfolioPct();
                if (drift < -5.0 && metrics.navPercentile3yr() < 0.60 && arbitrageValue > 10000) {
                    double deployFromArb = Math.min(arbitrageValue * 0.4, Math.abs(drift/100.0) * totalPortfolioValue);
                    List<String> justs = List.of(String.format("⚖️ Rebalancer Deploy: Fund is %.1f%% underweight. Redeploying ₹%,.0f from Arbitrage parking.", 
                        Math.abs(drift), deployFromArb));
                    
                    opportunisticDrafts.add(new TacticalSignal(target.schemeName(), amfiCodeFor(target), "BUY", String.valueOf(deployFromArb), 
                        target.targetPortfolioPct(), actualPct, target.sipPct(), target.status(), metrics.convictionScore(), 
                        metrics.sortinoRatio(), metrics.maxDrawdown(), metrics.navPercentile3yr(), metrics.drawdownFromAth(), 
                        metrics.returnZScore(), metrics.lastBuyDate(), justs));
                }
            }
        }

        // Apply conviction-based weighting to lumpsum if multiple signals exist
        return weightSignalsByConviction(opportunisticDrafts, lumpsum);
    }

    // ==========================================
    // 🌟 MODE 3: EXIT QUEUE (Dropped Funds)
    // ==========================================
    public List<TacticalSignal> computeExitQueue(String pan) {
        List<StrategyTarget> targets = strategyService.fetchLatestStrategy();
        List<TaxLot> allLots = taxLotRepository.findByStatusAndSchemeFolioInvestorPan("OPEN", pan);
        List<AggregatedHolding> holdings = aggregateLots(allLots);
        
        // Find realized LTCG so far this FY
        double realizedLtcg = jdbcTemplate.queryForObject(
            "SELECT COALESCE(SUM(realized_gain), 0) FROM capital_gain_audit WHERE tax_category LIKE '%LTCG%' AND calculation_date >= '2025-04-01'", 
            Double.class);
        double ltcgHeadroom = Math.max(0, 125000 - realizedLtcg);

        List<TacticalSignal> exitPlan = new ArrayList<>();

        for (AggregatedHolding h : holdings) {
            StrategyTarget target = targets.stream()
                .filter(t -> t.schemeName().equalsIgnoreCase(h.getSchemeName()))
                .findFirst().orElse(null);

            if (target == null || "dropped".equalsIgnoreCase(target.status())) {
                List<String> justs = new ArrayList<>();
                double exitAmount = 0;
                
                // Debt funds: Exit immediately (no LTCG benefit post-2023)
                if (h.getAssetCategory().contains("DEBT") || h.getAssetCategory().contains("GILT")) {
                    exitAmount = h.getCurrentValue();
                    justs.add("🚀 Priority Exit: Debt fund dropped from strategy. Slab-taxed anyway, exit now.");
                } 
                // Equity: Sequence by tax efficiency
                else {
                    if (h.getLtcgValue() > 0 && ltcgHeadroom > 0) {
                        double amountToHarvest = Math.min(h.getLtcgValue(), ltcgHeadroom * 5); // Rough proxy
                        exitAmount = amountToHarvest;
                        justs.add(String.format("✅ Tax-Efficient: Exiting ₹%,.0f in LTCG lots (using remaining headroom).", exitAmount));
                    } else if (h.getStcgValue() > 0) {
                        justs.add(String.format("⏳ Deferred: %d days until next lot becomes LTCG. Holding to avoid 20%% tax.", h.getDaysToNextLtcg()));
                    }
                }

                if (exitAmount > 0 || !justs.isEmpty()) {
                    exitPlan.add(new TacticalSignal(h.getSchemeName(), amfiCodeFor(h), "EXIT", String.valueOf(exitAmount), 
                        0, (h.getCurrentValue()/1000000), 0, "DROPPED", 0, 0, 0, 0, 0, 0, LocalDate.now(), justs));
                }
            }
        }
        return exitPlan;
    }

    // Helper: Legacy compatibility wrapper
    public List<TacticalSignal> generateDailySignals(String investorPan, double monthlySip, double lumpsum) {
        return computeOpportunisticSignals(investorPan, lumpsum);
    }

    private List<TacticalSignal> weightSignalsByConviction(List<TacticalSignal> signals, double cash) {
        if (signals.isEmpty()) return signals;
        
        double totalDemand = signals.stream()
            .mapToDouble(s -> Double.parseDouble(s.amount()) * (s.convictionScore() / 100.0))
            .sum();

        return signals.stream().map(sig -> {
            double baseAmount = Double.parseDouble(sig.amount());
            double scoreMult = sig.convictionScore() / 100.0;
            double weightedAmount = (totalDemand > 0) ? (baseAmount * scoreMult / totalDemand) * cash : 0;
            
            // Cooldown check
            long daysSinceLast = ChronoUnit.DAYS.between(sig.lastBuyDate(), LocalDate.now());
            if (daysSinceLast < 21) {
                List<String> justs = new ArrayList<>(sig.justifications());
                justs.add("❄️ Cooldown Active (21d). Skipping for now.");
                return createSignal(sig, "HOLD", "0", justs);
            }

            return createSignal(sig, "BUY", String.format("%.2f", weightedAmount), sig.justifications());
        }).collect(Collectors.toList());
    }

    private AggregatedHolding findHolding(List<AggregatedHolding> holdings, StrategyTarget t) {
        return holdings.stream()
            .filter(h -> h.getSchemeName().equalsIgnoreCase(t.schemeName()))
            .findFirst().orElse(new AggregatedHolding(t.schemeName(), 0,0,0,0,0,0,0,0,0,"UNKNOWN","DROPPED", t.isin()));
    }

    private String amfiCodeFor(StrategyTarget t) {
        return schemeRepository.findByIsin(t.isin()).map(Scheme::getAmfiCode).orElse("");
    }

    private String amfiCodeFor(AggregatedHolding h) {
        return schemeRepository.findByNameIgnoreCase(h.getSchemeName()).map(Scheme::getAmfiCode).orElse("");
    }

    private double sipAmountFromTarget(StrategyTarget t, double totalSip) {
        return (t.sipPct() / 100.0) * totalSip;
    }

    private MarketMetrics defaultMetrics() {
        return new MarketMetrics(0,0,0,0,0,0.5,0,0, LocalDate.of(1970,1,1));
    }

    private TacticalSignal createSignal(TacticalSignal s, String action, String amt, List<String> justs) {
        return new TacticalSignal(s.schemeName(), s.amfiCode(), action, amt, s.plannedPercentage(), s.actualPercentage(), s.sipPercentage(), s.fundStatus(), s.convictionScore(), s.sortinoRatio(), s.maxDrawdown(), s.navPercentile3yr(), s.drawdownFromAth(), s.returnZScore(), s.lastBuyDate(), justs);
    }

   private Map<String, MarketMetrics> fetchLiveMetricsMap(String pan) {
        // ... (existing implementation from previous turn)
        String sql = """
            SELECT m.amfi_code, m.sortino_ratio, m.cvar_5, m.win_rate, m.max_drawdown, 
                   m.conviction_score, m.nav_percentile_3yr, m.drawdown_from_ath, m.return_z_score
            FROM fund_conviction_metrics m
            JOIN scheme s ON m.amfi_code = s.amfi_code 
            JOIN folio f ON s.folio_id = f.id
            WHERE f.investor_pan = ?
            AND m.calculation_date = (SELECT MAX(calculation_date) FROM fund_conviction_metrics)
            """;
        
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
            SchemeDetailsDTO details = amfiService.getLatestSchemeDetails(scheme.getAmfiCode());
            double liveNav = (details != null && details.getNav() != null) ? details.getNav().doubleValue() : 0.0;

            double units = 0, cost = 0, val = 0, ltcgGains = 0, stcgGains = 0;
            double ltcgVal = 0, stcgVal = 0;
            int minDaysToLtcg = 365; 
            LocalDate oldest = LocalDate.now();

            for (TaxLot lot : entry.getValue()) {
                double lUnits = lot.getRemainingUnits().doubleValue();
                double lCost = lot.getCostBasisPerUnit().doubleValue() * lUnits;
                double lVal = lUnits * liveNav;
                
                units += lUnits; cost += lCost; val += lVal;
                if (lot.getBuyDate().isBefore(oldest)) oldest = lot.getBuyDate();

                long age = ChronoUnit.DAYS.between(lot.getBuyDate(), LocalDate.now());
                double gain = lVal - lCost;
                
                boolean isLtcg = (age > 365); 

                if (isLtcg) {
                    ltcgVal += lVal;
                    ltcgGains += Math.max(0, gain);
                } else {
                    stcgVal += lVal;
                    stcgGains += Math.max(0, gain);
                    int daysLeft = 365 - (int) age;
                    if (daysLeft < minDaysToLtcg) minDaysToLtcg = daysLeft;
                }
            }
            
            int finalDaysToNext = (stcgVal > 0) ? minDaysToLtcg : 0;

            return new AggregatedHolding(scheme.getName(), units, val, cost, ltcgVal, ltcgGains, 
                stcgVal, stcgGains, finalDaysToNext, (int)ChronoUnit.DAYS.between(oldest, LocalDate.now()), 
                scheme.getAssetCategory(), "ACTIVE", scheme.getIsin());
        }).collect(Collectors.toList());
    }
}
