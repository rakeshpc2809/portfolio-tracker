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
import com.oreki.cas_injector.core.utils.CommonUtils;
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
                    amount, t.sipPct(), t.targetPortfolioPct(), t.status(), flag, note);
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

        // ENHANCEMENT: Also fetch metrics for accumulator/strategy funds not yet held
        for (StrategyTarget t : targets) {
            String amfi = amfiCodeFor(t);
            if (!amfi.isEmpty() && !metricsMap.containsKey(amfi)) {
                String sql = """
                    SELECT conviction_score, sortino_ratio, cvar_5, win_rate, max_drawdown,
                           nav_percentile_3yr, drawdown_from_ath, return_z_score
                    FROM fund_conviction_metrics
                    WHERE amfi_code = ?
                    AND calculation_date = (SELECT MAX(calculation_date) FROM fund_conviction_metrics)
                    """;
                try {
                    List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, amfi);
                    if (!rows.isEmpty()) {
                        Map<String, Object> r = rows.get(0);
                        metricsMap.put(amfi, new MarketMetrics(
                            getSafeInt(r.get("conviction_score")),
                            getSafeDouble(r.get("sortino_ratio")),
                            getSafeDouble(r.get("cvar_5")),
                            getSafeDouble(r.get("win_rate")),
                            getSafeDouble(r.get("max_drawdown")),
                            getSafeDouble(r.get("nav_percentile_3yr")),
                            getSafeDouble(r.get("drawdown_from_ath")),
                            getSafeDouble(r.get("return_z_score")),
                            LocalDate.of(1970, 1, 1)
                        ));
                    }
                } catch (Exception e) {
                    log.debug("No conviction metrics found for amfi {}", amfi);
                }
            }
        }

        List<TaxLot> allLots = taxLotRepository.findByStatusAndSchemeFolioInvestorPan("OPEN", pan);
        List<AggregatedHolding> holdings = aggregateLots(allLots);
        
        double totalPortfolioValue = holdings.stream().mapToDouble(AggregatedHolding::getCurrentValue).sum();
        
        // Replace the arbitrageValue calculation with hardened rebalancerValue:
        double rebalancerValue = holdings.stream()
            .filter(h -> {
                // Match by asset category OR by strategy status from sheet
                String cat = h.getAssetCategory() != null ? h.getAssetCategory().toUpperCase() : "";
                boolean isArbitrageByCategory = cat.contains("ARBITRAGE");
                // Also match if the holding's scheme name matches a "rebalancer" status fund
                boolean isRebalancerBySheet = targets.stream()
                    .anyMatch(t -> "rebalancer".equalsIgnoreCase(t.status()) 
                        && t.schemeName().equalsIgnoreCase(h.getSchemeName()));
                return isArbitrageByCategory || isRebalancerBySheet;
            })
            .mapToDouble(AggregatedHolding::getCurrentValue)
            .sum();

        List<TacticalSignal> opportunisticDrafts = new ArrayList<>();

        for (StrategyTarget target : targets) {
            AggregatedHolding holding = findHolding(holdings, target);
            MarketMetrics metrics = metricsMap.getOrDefault(amfiCodeFor(target), defaultMetrics());
            double actualPct = totalPortfolioValue > 0 ? (holding.getCurrentValue() / totalPortfolioValue) * 100 : 0;

            // 1. ACCUMULATOR LOGIC (Buy on dips)
            if ("accumulator".equalsIgnoreCase(target.status())) {
                boolean isUnderTarget = actualPct < (target.targetPortfolioPct() > 0 ? target.targetPortfolioPct() : 100);
                
                // Primary gate: NAV percentile signal
                boolean isNearLow = metrics.navPercentile3yr() < 0.45; // Slightly more permissive than 0.40

                // Fallback gate: if no NAV percentile data, use ATH drawdown as proxy
                // A fund down > 15% from ATH is likely at a dip
                boolean isAtAthDiscount = metrics.drawdownFromAth() < -0.15;
                
                boolean entrySignalPresent = isNearLow || isAtAthDiscount;

                if (isUnderTarget && entrySignalPresent) {
                    String reason = isNearLow 
                        ? String.format("NAV at %d%% of 3yr range — near historical low.", Math.round(metrics.navPercentile3yr() * 100))
                        : String.format("NAV down %.1f%% from all-time high — discounted entry.", Math.abs(metrics.drawdownFromAth()) * 100);
                    
                    List<String> justs = List.of("Accumulator entry: " + reason);
                    
                    double baseAmount = Math.max(10000, 
                        (target.targetPortfolioPct() - actualPct) / 100.0 * totalPortfolioValue * 0.5);

                    opportunisticDrafts.add(new TacticalSignal(
                        target.schemeName(), amfiCodeFor(target), "BUY", String.valueOf(baseAmount),
                        target.targetPortfolioPct(), actualPct, 0, target.status(),
                        metrics.convictionScore() > 0 ? metrics.convictionScore() : 40, 
                        metrics.sortinoRatio(), metrics.maxDrawdown(), metrics.navPercentile3yr(),
                        metrics.drawdownFromAth(), metrics.returnZScore(),
                        metrics.lastBuyDate(), justs));
                }
            }

            // 2. REBALANCER DEPLOY (Core/Strategy significant drift)
            if ("core".equalsIgnoreCase(target.status()) || "strategy".equalsIgnoreCase(target.status())) {
                double drift = actualPct - target.targetPortfolioPct();
                if (drift < -5.0 && metrics.navPercentile3yr() < 0.60 && rebalancerValue > 10000) {
                    double deployFromRebalancer = Math.min(rebalancerValue * 0.4, Math.abs(drift/100.0) * totalPortfolioValue);
                    List<String> justs = List.of(String.format("⚖️ Rebalancer Deploy: Fund is %.1f%% underweight. Redeploying ₹%,.0f from Rebalancer/Arbitrage parking.", 
                        Math.abs(drift), deployFromRebalancer));
                    
                    opportunisticDrafts.add(new TacticalSignal(target.schemeName(), amfiCodeFor(target), "BUY", String.valueOf(deployFromRebalancer), 
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
        
        // Build a case-insensitive lookup of dropped ISINs and names from sheet
        Set<String> droppedIsins = targets.stream()
            .filter(t -> "dropped".equalsIgnoreCase(t.status()))
            .map(t -> t.isin().toUpperCase())
            .collect(Collectors.toSet());
        Set<String> droppedNames = targets.stream()
            .filter(t -> "dropped".equalsIgnoreCase(t.status()))
            .map(t -> t.schemeName().toUpperCase().trim())
            .collect(Collectors.toSet());

        List<TaxLot> allLots = taxLotRepository.findByStatusAndSchemeFolioInvestorPan("OPEN", pan);
        List<AggregatedHolding> holdings = aggregateLots(allLots);

        // Find realized LTCG so far this FY
        LocalDate fyStart = CommonUtils.getCurrentFyStart();
        String ltcgSql = """
            SELECT COALESCE(SUM(a.realized_gain), 0)
            FROM capital_gain_audit a
            JOIN transaction t ON a.sell_transaction_id = t.id
            JOIN scheme s ON t.scheme_id = s.id
            JOIN folio f ON s.folio_id = f.id
            WHERE a.tax_category LIKE '%LTCG%'
            AND t.transaction_date >= ?
            AND f.investor_pan = ?
            """;
        double realizedLtcg = jdbcTemplate.queryForObject(ltcgSql, Double.class, fyStart, pan);
        double ltcgHeadroom = Math.max(0, 125000 - realizedLtcg);

        List<TacticalSignal> exitPlan = new ArrayList<>();

        for (AggregatedHolding h : holdings) {
            // Check if this holding is marked dropped — match by ISIN first, then name
            boolean isDropped = droppedIsins.contains(h.getIsin() != null ? h.getIsin().toUpperCase() : "")
                || droppedNames.contains(h.getSchemeName().toUpperCase().trim())
                // Also include holdings with no entry in strategy sheet at all (orphaned)
                || targets.stream().noneMatch(t -> 
                    t.isin().equalsIgnoreCase(h.getIsin()) || 
                    t.schemeName().equalsIgnoreCase(h.getSchemeName()));

            if (!isDropped) continue;
            if (h.getCurrentValue() < 100) continue; // Skip dust positions

            List<String> justs = new ArrayList<>();
            double exitAmount = 0;
            String category = h.getAssetCategory() != null ? h.getAssetCategory().toUpperCase() : "";

            // --- DEBT / GILT / BOND / LIQUID: Post-April 2023, ALL gains are slab-taxed regardless of holding period.
            boolean isDebt = category.contains("DEBT") || category.contains("GILT")
                || category.contains("BOND") || category.contains("LIQUID")
                || category.contains("BANKING AND PSU") || category.contains("CORPORATE")
                || category.contains("MONEY MARKET");

            if (isDebt) {
                exitAmount = h.getCurrentValue();
                double gain = h.getCurrentValue() - h.getInvestedAmount();
                if (gain > 0) {
                    justs.add(String.format(
                        "Priority exit: Debt fund taxed at slab rate (post-Apr 2023 rules). " +
                        "No benefit in waiting. Gain of ₹%,.0f will be taxed at your income bracket.", gain));
                } else {
                    justs.add(String.format(
                        "Priority exit: Debt fund at a loss of ₹%,.0f. " +
                        "Exit now to book the loss — it offsets other income at slab rate.", Math.abs(gain)));
                }
            }
            // --- EQUITY: Sequence by tax efficiency
            else {
                // Case 1: Has LTCG-eligible units — harvest within headroom
                if (h.getLtcgValue() > 0) {
                    double ltcgGainInHolding = h.getLtcgAmount();
                    if (ltcgGainInHolding > 0 && ltcgHeadroom > 0) {
                        double gainRatio = ltcgGainInHolding / h.getLtcgValue(); 
                        double maxSellableUnderHeadroom = gainRatio > 0 ? ltcgHeadroom / gainRatio : h.getLtcgValue();
                        exitAmount = Math.min(h.getLtcgValue(), maxSellableUnderHeadroom);
                        ltcgHeadroom -= Math.min(ltcgGainInHolding, ltcgHeadroom);
                        justs.add(String.format(
                            "Tax-efficient exit: Selling ₹%,.0f of LTCG-eligible units " +
                            "(profit ₹%,.0f) within ₹1.25L annual tax-free limit.", exitAmount, ltcgGainInHolding));
                    } else if (ltcgGainInHolding <= 0) {
                        exitAmount = h.getLtcgValue();
                        justs.add(String.format(
                            "Exit at no tax: LTCG lots are at a loss of ₹%,.0f. No capital gains tax applies.",
                            Math.abs(ltcgGainInHolding)));
                    } else {
                        exitAmount = h.getLtcgValue();
                        justs.add(String.format(
                            "LTCG limit reached for this FY. Exiting ₹%,.0f anyway — " +
                            "excess gains (₹%,.0f) taxed at 12.5%%.",
                            exitAmount, Math.max(0, ltcgGainInHolding - (125000 - realizedLtcg))));
                    }
                }

                // Case 2: Only STCG lots — defer if close to threshold, else recommend immediate exit
                if (h.getStcgValue() > 0) {
                    int daysToLtcg = h.getDaysToNextLtcg();
                    if (daysToLtcg > 0 && daysToLtcg <= 45) {
                        justs.add(String.format(
                            "Deferred: %d days until next lot becomes LTCG-eligible. " +
                            "Waiting saves 20%% STCG tax on ₹%,.0f.", daysToLtcg, h.getStcgValue()));
                    } else if (daysToLtcg > 45) {
                        if (h.getStcgValue() < 15000) {
                            exitAmount += h.getStcgValue();
                            double stcgTax = Math.max(0, h.getStcgAmount()) * 0.20;
                            justs.add(String.format(
                                "Small position (₹%,.0f): Exiting despite %d days remaining. " +
                                "STCG tax of ₹%,.0f is acceptable on this position size.", 
                                h.getStcgValue(), daysToLtcg, stcgTax));
                        } else {
                            justs.add(String.format(
                                "Deferred: %d days until LTCG. Holding ₹%,.0f to avoid " +
                                "₹%,.0f in STCG tax (20%%).", 
                                daysToLtcg, h.getStcgValue(), Math.max(0, h.getStcgAmount()) * 0.20));
                        }
                    }
                }

                // Generic fallback
                if (exitAmount == 0 && justs.isEmpty() && h.getCurrentValue() > 0) {
                    exitAmount = h.getCurrentValue();
                    justs.add(String.format(
                        "Exit queued: Fund dropped from strategy. " +
                        "Current value ₹%,.0f. Verify lot details for tax treatment.", h.getCurrentValue()));
                }
            }

            if (!justs.isEmpty() || exitAmount > 0) {
                double totalVal = holdings.stream().mapToDouble(AggregatedHolding::getCurrentValue).sum();
                exitPlan.add(new TacticalSignal(
                    h.getSchemeName(), amfiCodeFor(h), "EXIT",
                    String.format("%.2f", exitAmount),
                    0, (h.getCurrentValue() / (totalVal / 100.0)),
                    0, "DROPPED", 0, 0, 0, 0, 0, 0,
                    LocalDate.now(), justs));
            }
        }

        // Sort: debt funds first (immediate exits), then by exit amount descending
        exitPlan.sort(Comparator
            .comparing((TacticalSignal s) -> {
                AggregatedHolding h = holdings.stream()
                    .filter(x -> x.getSchemeName().equalsIgnoreCase(s.schemeName()))
                    .findFirst().orElse(null);
                String cat = h != null && h.getAssetCategory() != null ? h.getAssetCategory().toUpperCase() : "";
                return cat.contains("DEBT") || cat.contains("GILT") || cat.contains("BOND") || cat.contains("LIQUID") ? 0 : 1;
            })
            .thenComparing(Comparator.comparingDouble(
                (TacticalSignal s) -> Double.parseDouble(s.amount())).reversed())
        );

        log.info("🚀 Exit queue computed: {} funds to exit", exitPlan.size());
        return exitPlan;
    }

    // Helper: Legacy compatibility wrapper
    public List<TacticalSignal> generateDailySignals(String investorPan, double monthlySip, double lumpsum) {
        return computeOpportunisticSignals(investorPan, lumpsum);
    }

    private List<TacticalSignal> weightSignalsByConviction(List<TacticalSignal> signals, double cash) {
        if (signals.isEmpty()) return signals;

        // Cooldown check BEFORE the cash weighting
        List<TacticalSignal> activeDrafts = signals.stream().map(sig -> {
            long daysSinceLast = ChronoUnit.DAYS.between(
                sig.lastBuyDate() != null ? sig.lastBuyDate() : LocalDate.of(1970, 1, 1),
                LocalDate.now());
            if (daysSinceLast < 21) {
                List<String> justs = new ArrayList<>(sig.justifications());
                justs.add(String.format("Cooldown: Last bought %d days ago. Wait %d more days.", 
                    daysSinceLast, 21 - (int)daysSinceLast));
                return createSignal(sig, "WATCH", "0", justs);
            }
            return sig;
        }).collect(Collectors.toList());

        if (cash <= 0) {
            return activeDrafts.stream()
                .filter(s -> !"WATCH".equals(s.action()))
                .map(s -> createSignal(s, "BUY", s.amount(), s.justifications()))
                .collect(Collectors.toList());
        }

        double totalDemand = activeDrafts.stream()
            .filter(s -> "BUY".equals(s.action()))
            .mapToDouble(s -> Double.parseDouble(s.amount()) * Math.max(0.2, s.convictionScore() / 100.0))
            .sum();

        return activeDrafts.stream().map(sig -> {
            if (!"BUY".equals(sig.action())) return sig;
            
            double baseAmount = Double.parseDouble(sig.amount());
            double scoreMult = Math.max(0.2, sig.convictionScore() / 100.0);
            double allocatedAmount = totalDemand > 0 
                ? (baseAmount * scoreMult / totalDemand) * cash 
                : baseAmount;
            
            List<String> justs = new ArrayList<>(sig.justifications());
            justs.add(String.format("Allocated ₹%,.0f from available capital of ₹%,.0f.", allocatedAmount, cash));
            return createSignal(sig, "BUY", String.format("%.2f", allocatedAmount), justs);
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
                getSafeInt(r.get("conviction_score")),
                getSafeDouble(r.get("sortino_ratio")),
                getSafeDouble(r.get("cvar_5")),
                getSafeDouble(r.get("win_rate")),
                getSafeDouble(r.get("max_drawdown")),
                getSafeDouble(r.get("nav_percentile_3yr")),
                getSafeDouble(r.get("drawdown_from_ath")),
                getSafeDouble(r.get("return_z_score")),
                lastBuyDates.getOrDefault(amfi, LocalDate.of(1970, 1, 1))
            ));
        }
        return map;
    }

    private double getSafeDouble(Object obj) { return obj == null ? 0.0 : ((Number) obj).doubleValue(); }
    private int getSafeInt(Object obj) { return obj == null ? 0 : ((Number) obj).intValue(); }

    private List<AggregatedHolding> aggregateLots(List<TaxLot> lots) {
        Map<Scheme, List<TaxLot>> groupedLots = lots.stream().collect(Collectors.groupingBy(TaxLot::getScheme));
        return groupedLots.entrySet().stream().map(entry -> {
            Scheme scheme = entry.getKey();
            SchemeDetailsDTO details = amfiService.getLatestSchemeDetails(scheme.getAmfiCode());
            double liveNav = (details != null && details.getNav() != null) ? details.getNav().doubleValue() : 0.0;
            String category = scheme.getAssetCategory() != null ? scheme.getAssetCategory() : details.getCategory();

            double units = 0, cost = 0, val = 0, ltcgGains = 0, stcgGains = 0;
            double ltcgVal = 0, stcgVal = 0;
            int minDaysToLtcg = 1095; 
            LocalDate oldest = LocalDate.now();

            for (TaxLot lot : entry.getValue()) {
                double lUnits = lot.getRemainingUnits().doubleValue();
                double lCost = lot.getCostBasisPerUnit().doubleValue() * lUnits;
                double lVal = lUnits * liveNav;
                
                units += lUnits; cost += lCost; val += lVal;
                if (lot.getBuyDate().isBefore(oldest)) oldest = lot.getBuyDate();

                double gain = lVal - lCost;
                
                // Hardened debt lot classification
                boolean isDebtFund = category.contains("DEBT") || category.contains("GILT") 
                    || category.contains("BOND") || category.contains("LIQUID")
                    || category.contains("BANKING AND PSU") || category.contains("CORPORATE")
                    || category.contains("MONEY MARKET");
                if (isDebtFund) {
                    stcgVal += lVal;
                    stcgGains += Math.max(0, gain);
                    continue; 
                }

                String taxCat = CommonUtils.DETERMINE_TAX_CATEGORY.apply(lot.getBuyDate(), LocalDate.now(), category);
                boolean isLtcg = taxCat.contains("LTCG"); 

                if (isLtcg) {
                    ltcgVal += lVal;
                    ltcgGains += Math.max(0, gain);
                } else {
                    stcgVal += lVal;
                    stcgGains += Math.max(0, gain);
                    
                    int waitDays = 0;
                    if (taxCat.contains("EQUITY")) waitDays = 365;
                    else if (taxCat.contains("HYBRID")) waitDays = 730;
                    else if (lot.getBuyDate().isBefore(LocalDate.of(2023, 4, 1))) waitDays = 1095;
                    
                    if (waitDays > 0) {
                        long age = ChronoUnit.DAYS.between(lot.getBuyDate(), LocalDate.now());
                        int daysLeft = waitDays - (int) age;
                        if (daysLeft > 0 && daysLeft < minDaysToLtcg) minDaysToLtcg = daysLeft;
                    }
                }
            }
            
            int finalDaysToNext = (stcgVal > 0 && minDaysToLtcg < 1095) ? minDaysToLtcg : 0;

            return new AggregatedHolding(scheme.getName(), units, val, cost, ltcgVal, ltcgGains, 
                stcgVal, stcgGains, finalDaysToNext, (int)ChronoUnit.DAYS.between(oldest, LocalDate.now()), 
                category, "ACTIVE", scheme.getIsin());
        }).collect(Collectors.toList());
    }
}
