package com.oreki.cas_injector.rebalancing.service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
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
import com.oreki.cas_injector.core.service.LotAggregationService;
import com.oreki.cas_injector.core.service.SystemicRiskMonitorService;
import com.oreki.cas_injector.core.utils.CommonUtils;
import com.oreki.cas_injector.core.utils.SignalType;
import com.oreki.cas_injector.rebalancing.dto.SipLineItem;
import com.oreki.cas_injector.rebalancing.dto.StrategyTarget;
import com.oreki.cas_injector.rebalancing.dto.TacticalSignal;
import com.oreki.cas_injector.taxmanagement.dto.TaxSimulationResult;
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
    private final TaxLotRepository taxLotRepository; 
    private final SchemeRepository schemeRepository;
    private final TaxSimulatorService taxSimulator;
    private final JdbcTemplate jdbcTemplate;
    @SuppressWarnings("unused")
    private final SystemicRiskMonitorService systemicRiskMonitor;
    private final ConvictionScoringService convictionScoringService;
    private final PositionSizingService positionSizingService;
    private final LotAggregationService lotAggregationService;

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
        
        List<StrategyTarget> targets = strategyService.fetchLatestStrategy();
        Map<String, MarketMetrics> metricsMap = fetchLiveMetricsMap(pan);

        // ENHANCEMENT: Also fetch metrics for accumulator/strategy funds not yet held
        for (StrategyTarget t : targets) {
            String amfi = amfiCodeFor(t);
            if (!amfi.isEmpty() && !metricsMap.containsKey(amfi)) {
                Map<String, MarketMetrics> fetched = fetchMetricsForAmfi(amfi);
                if (fetched.containsKey(amfi)) {
                    metricsMap.put(amfi, fetched.get(amfi));
                }
            }
        }

        List<TaxLot> allLots = taxLotRepository.findByStatusAndSchemeFolioInvestorPan("OPEN", pan);
        List<AggregatedHolding> holdings = lotAggregationService.aggregate(allLots);
        
        double totalPortfolioValue = holdings.stream().mapToDouble(AggregatedHolding::getCurrentValue).sum();
        
        // Rebalancer/Arbitrage "dry powder" identification
        double rebalancerValue = holdings.stream()
            .filter(h -> {
                String cat = h.getAssetCategory() != null ? h.getAssetCategory().toUpperCase() : "";
                boolean isArbitrageByCategory = cat.contains("ARBITRAGE");
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
            String amfi = amfiCodeFor(target);
            MarketMetrics metrics = metricsMap.getOrDefault(amfi, defaultMetrics());
            double actualPct = totalPortfolioValue > 0 ? (holding.getCurrentValue() / totalPortfolioValue) * 100 : 0;

            // 1. ACCUMULATOR LOGIC (Buy on dips)
            if ("accumulator".equalsIgnoreCase(target.status())) {
                boolean isUnderTarget = actualPct < (target.targetPortfolioPct() > 0 ? target.targetPortfolioPct() : 100);
                
                // NEW: Three-tier logic
                boolean isNearLow        = metrics.navPercentile3yr() > 0 && metrics.navPercentile3yr() < 0.45;
                boolean isAtAthDiscount  = metrics.drawdownFromAth() < -0.15;
                boolean hasNoNavHistory  = metrics.navPercentile3yr() == 0.0 && metrics.drawdownFromAth() == 0.0;
                // If no NAV history at all, allow entry if conviction score is decent (new fund being initiated)
                boolean isNewFundEntry   = hasNoNavHistory && metrics.convictionScore() >= 45;

                boolean entrySignalPresent = isNearLow || isAtAthDiscount || isNewFundEntry;

                if (isUnderTarget && entrySignalPresent) {
                    String reason;
                    if (isNearLow)
                        reason = String.format("NAV at %d%% of 3yr range — near historical low.", Math.round(metrics.navPercentile3yr() * 100));
                    else if (isAtAthDiscount)
                        reason = String.format("NAV down %.1f%% from all-time high.", Math.abs(metrics.drawdownFromAth()) * 100);
                    else
                        reason = "New position initiation — insufficient NAV history, using conviction score as proxy.";
                    
                    List<String> justs = new ArrayList<>();
                    justs.add("Accumulator entry: " + reason);
                    
                    double driftAmount = (target.targetPortfolioPct() - actualPct) / 100.0 * totalPortfolioValue;
                    // Using Half-Kelly sizing
                    double baseAmount = Math.max(10000, 
                        positionSizingService.calculateExecutionAmount(driftAmount, lumpsum, metrics));

                    // ── CONCENTRATION GUARD ──────────────────────────────────────────
                    if (wouldBreachConcentration(holding, baseAmount, totalPortfolioValue)) {
                        double maxDeploy = (MAX_SINGLE_FUND_CONCENTRATION * totalPortfolioValue) - holding.getCurrentValue();
                        if (maxDeploy < 5000) {
                            log.info("⛔ Concentration guard: {} already near 30% cap, skipping signal.", target.schemeName());
                            continue; // skip this fund entirely
                        }
                        baseAmount = Math.max(0, maxDeploy);
                        justs.add(String.format(
                            "⚠️ Concentration capped: Deploy limited to ₹%,.0f to stay under 30%% single-fund limit.",
                            baseAmount));
                    }

                    opportunisticDrafts.add(buildSignal(target, amfi, SignalType.BUY, baseAmount, 
                        target.targetPortfolioPct(), actualPct, metrics, justs));
                }
            }

            // 2. REBALANCER DEPLOY (Core/Strategy significant drift)
            if ("core".equalsIgnoreCase(target.status()) || "strategy".equalsIgnoreCase(target.status())) {
                double drift = actualPct - target.targetPortfolioPct();
                if (drift < -5.0 && metrics.navPercentile3yr() < 0.60 && rebalancerValue > 10000) {
                    double deployFromRebalancer = Math.min(rebalancerValue * 0.4, Math.abs(drift/100.0) * totalPortfolioValue);
                    List<String> justs = List.of(String.format("⚖️ Rebalancer Deploy: Fund is %.1f%% underweight. Redeploying ₹%,.0f from Rebalancer/Arbitrage parking.", 
                        Math.abs(drift), deployFromRebalancer));
                    
                    opportunisticDrafts.add(buildSignal(target, amfi, SignalType.BUY, deployFromRebalancer, 
                        target.targetPortfolioPct(), actualPct, metrics, justs));
                }
            }
        }

        // Apply conviction-based weighting to lumpsum if multiple signals exist
        return weightSignalsByConviction(opportunisticDrafts, lumpsum);
    }

    private static final double MAX_SINGLE_FUND_CONCENTRATION = 0.30; // 30% cap

    private boolean wouldBreachConcentration(AggregatedHolding holding, double deployAmount,
                                              double totalPortfolioValue) {
        if (totalPortfolioValue <= 0) return false;
        double newValue      = holding.getCurrentValue() + deployAmount;
        double newConcentration = newValue / totalPortfolioValue;
        return newConcentration > MAX_SINGLE_FUND_CONCENTRATION;
    }

    // ==========================================
    // 🌟 MODE 3: EXIT QUEUE (Dropped Funds)
    // ==========================================
    public List<TacticalSignal> computeExitQueue(String pan) {
        List<StrategyTarget> targets = strategyService.fetchLatestStrategy();
        
        Set<String> droppedIsins = targets.stream()
            .filter(t -> "dropped".equalsIgnoreCase(t.status()))
            .map(t -> t.isin().toUpperCase())
            .collect(Collectors.toSet());
        Set<String> droppedNames = targets.stream()
            .filter(t -> "dropped".equalsIgnoreCase(t.status()))
            .map(t -> t.schemeName().toUpperCase().trim())
            .collect(Collectors.toSet());

        List<TaxLot> allLots = taxLotRepository.findByStatusAndSchemeFolioInvestorPan("OPEN", pan);
        List<AggregatedHolding> holdings = lotAggregationService.aggregate(allLots);

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
            boolean isDropped = droppedIsins.contains(h.getIsin() != null ? h.getIsin().toUpperCase() : "")
                || droppedNames.contains(h.getSchemeName().toUpperCase().trim())
                || targets.stream().noneMatch(t -> 
                    t.isin().equalsIgnoreCase(h.getIsin()) || 
                    t.schemeName().equalsIgnoreCase(h.getSchemeName()));

            if (!isDropped) continue;
            if (h.getCurrentValue() < 100) continue; 

            List<String> justs = new ArrayList<>();
            double exitAmount = 0;
            String category = h.getAssetCategory() != null ? h.getAssetCategory().toUpperCase() : "";

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
                        "Exit now to book the loss.", Math.abs(gain)));
                }
            } else {
                if (h.getLtcgValue() > 0) {
                    double ltcgGainInHolding = h.getLtcgAmount();
                    if (ltcgGainInHolding > 0 && ltcgHeadroom > 0) {
                        double gainRatio = ltcgGainInHolding / h.getLtcgValue(); 
                        double maxSellableUnderHeadroom = gainRatio > 0 ? ltcgHeadroom / gainRatio : h.getLtcgValue();
                        exitAmount = Math.min(h.getLtcgValue(), maxSellableUnderHeadroom);
                        ltcgHeadroom -= Math.min(ltcgGainInHolding, ltcgHeadroom);
                        justs.add(String.format(
                            "Tax-efficient exit: Selling ₹%,.0f of LTCG-eligible units " +
                            "(profit ₹%,.0f) within annual tax-free limit.", exitAmount, ltcgGainInHolding));
                    } else if (ltcgGainInHolding <= 0) {
                        exitAmount = h.getLtcgValue();
                        justs.add(String.format(
                            "Exit at no tax: LTCG lots are at a loss of ₹%,.0f.",
                            Math.abs(ltcgGainInHolding)));
                    } else {
                        exitAmount = h.getLtcgValue();
                        justs.add(String.format(
                            "LTCG limit reached for this FY. Exiting ₹%,.0f anyway — " +
                            "excess gains taxed at 12.5%%.",
                            exitAmount));
                    }
                }

                if (h.getStcgValue() > 0) {
                    int daysToLtcg = h.getDaysToNextLtcg();
                    if (daysToLtcg > 0 && daysToLtcg <= 45) {
                        justs.add(String.format(
                            "Deferred: %d days until next lot becomes LTCG-eligible.", daysToLtcg));
                    } else if (daysToLtcg > 45) {
                        if (h.getStcgValue() < 15000) {
                            exitAmount += h.getStcgValue();
                            justs.add(String.format(
                                "Small position (₹%,.0f): Exiting despite %d days remaining.", 
                                h.getStcgValue(), daysToLtcg));
                        } else {
                            justs.add(String.format(
                                "Deferred: %d days until LTCG. Holding ₹%,.0f to avoid STCG tax.", 
                                daysToLtcg, h.getStcgValue()));
                        }
                    }
                }

                if (exitAmount == 0 && justs.isEmpty() && h.getCurrentValue() > 0) {
                    exitAmount = h.getCurrentValue();
                    justs.add(String.format(
                        "Exit queued: Fund dropped from strategy. " +
                        "Current value ₹%,.0f.", h.getCurrentValue()));
                }
            }

            if (!justs.isEmpty() || exitAmount > 0) {
                double totalVal = holdings.stream().mapToDouble(AggregatedHolding::getCurrentValue).sum();
                exitPlan.add(new TacticalSignal(
                    h.getSchemeName(), amfiCodeFor(h), SignalType.EXIT,
                    String.format("%.2f", exitAmount),
                    0, (h.getCurrentValue() / (totalVal / 100.0)),
                    0, "DROPPED", 0, 0, 0, 0, 0, 0,
                    LocalDate.now(), justs));
            }
        }

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

        return exitPlan;
    }

    /**
     * GATE B: Proactive SELL/HOLD for active (non-dropped) funds.
     */
    public List<TacticalSignal> computeActiveSellSignals(String pan) {
        final double DRIFT_SELL_THRESHOLD = 2.5;      // % overweight to trigger
        final double CQS_DETERIORATION_FLOOR = 35;    // CQS below this = quantitative failure
        final double TAX_DRAG_HARD_OVERRIDE = 0.08;   // 8% drag = sell regardless (Gate C)
        final double INVESTMENT_HORIZON_YEARS = 5.0;  // Investor's assumed time horizon

        List<StrategyTarget> targets = strategyService.fetchLatestStrategy();
        Map<String, MarketMetrics> metricsMap = fetchLiveMetricsMap(pan);
        Map<String, Integer> cqsMap = loadCqsMap();

        List<TaxLot> allLots = taxLotRepository.findByStatusAndSchemeFolioInvestorPan("OPEN", pan);
        List<AggregatedHolding> holdings = lotAggregationService.aggregate(allLots);
        double totalValue = holdings.stream().mapToDouble(AggregatedHolding::getCurrentValue).sum();

        List<TacticalSignal> sellSignals = new ArrayList<>();

        for (StrategyTarget target : targets) {
            if ("dropped".equalsIgnoreCase(target.status())) continue; 
            
            AggregatedHolding holding = findHolding(holdings, target);
            if (holding.getCurrentValue() < 5000) continue; 

            String amfi = amfiCodeFor(target);
            MarketMetrics metrics = metricsMap.getOrDefault(amfi, defaultMetrics());
            int cqs = cqsMap.getOrDefault(amfi, 50);

            double actualPct = totalValue > 0 ? (holding.getCurrentValue() / totalValue) * 100 : 0;
            double drift = actualPct - target.targetPortfolioPct(); // positive = overweight

            boolean isOverweight = drift > DRIFT_SELL_THRESHOLD;
            boolean isQuantDeterioration = cqs < CQS_DETERIORATION_FLOOR;

            if (!isOverweight || !isQuantDeterioration) continue;

            // --- TAX-ALPHA HURDLE RATE ---
            double sellAmount = (drift / 100.0) * totalValue; 
            TaxSimulationResult taxResult = taxSimulator.simulateSellOrder(
                holding.getSchemeName(), sellAmount, getCurrentNav(amfi), pan);
            
            double netRealizable = sellAmount - taxResult.estimatedTax();
            double taxDragPct = taxResult.taxDragPercentage();

            List<String> justs = new ArrayList<>();
            justs.add(String.format("Overweight by %.1f%% (actual: %.1f%%, target: %.1f%%).", 
                drift, actualPct, target.targetPortfolioPct()));
            justs.add(String.format("Peer CQS: %d/100 — below floor of %d (quantitative deterioration).", 
                cqs, CQS_DETERIORATION_FLOOR));

            if (metrics.cvar5() < -5.0 || taxDragPct > TAX_DRAG_HARD_OVERRIDE) {
                justs.add(String.format(
                    "⚠️ Risk override: CVaR=%.2f%% or tax drag %.1f%% exceeds hard limits.",
                    metrics.cvar5(), taxDragPct * 100));
                sellSignals.add(buildSignal(target, amfi, SignalType.SELL, sellAmount, 
                    target.targetPortfolioPct(), actualPct, metrics, justs));
                continue;
            }

            double currentFundExpectedReturn = sortinoToExpectedReturn(metrics.sortinoRatio());
            double replacementExpectedReturn = estimateBestBucketReturn(pan, target.status(), cqsMap, metricsMap);
            
            double holdFutureValue    = sellAmount    * Math.pow(1 + currentFundExpectedReturn, INVESTMENT_HORIZON_YEARS);
            double switchFutureValue  = netRealizable * Math.pow(1 + replacementExpectedReturn, INVESTMENT_HORIZON_YEARS);

            boolean hurdleCleared = switchFutureValue > holdFutureValue;

            int daysToLtcg = holding.getDaysToNextLtcg();
            if (!hurdleCleared && daysToLtcg > 0 && daysToLtcg <= 45 && taxResult.hasStcg()) {
                double stcgSavings = taxResult.stcgProfit() * 0.075; 
                justs.add(String.format(
                    "⏳ Tax-Locked: %d days until LTCG. Waiting saves ~₹%,.0f in STCG tax.",
                    daysToLtcg, stcgSavings));
                sellSignals.add(buildSignal(target, amfi, SignalType.HOLD, 0, 
                    target.targetPortfolioPct(), actualPct, metrics, justs));
                continue;
            }

            if (!hurdleCleared) {
                justs.add(String.format(
                    "Tax-Locked: Net realizable ₹%,.0f after ₹%,.0f tax. Expected switch benefit " +
                    "doesn't clear hold value over %d-yr horizon.",
                    netRealizable, taxResult.estimatedTax(), (int)INVESTMENT_HORIZON_YEARS));
                sellSignals.add(buildSignal(target, amfi, SignalType.HOLD, 0,
                    target.targetPortfolioPct(), actualPct, metrics, justs));
            } else {
                justs.add(String.format(
                    "Tax-Alpha cleared: Switch value ₹%,.0f vs hold ₹%,.0f (net of ₹%,.0f tax, %.1f%% drag).",
                    (long)switchFutureValue, (long)holdFutureValue, taxResult.estimatedTax(), taxDragPct * 100));
                sellSignals.add(buildSignal(target, amfi, SignalType.SELL, sellAmount,
                    target.targetPortfolioPct(), actualPct, metrics, justs));
            }
        }

        return sellSignals;
    }

    private double sortinoToExpectedReturn(double sortino) {
        double MAR = 0.07;
        return MAR + Math.max(0, (sortino - 1.0) * 0.03); 
    }

    private double estimateBestBucketReturn(String pan, String bucket, 
            Map<String, Integer> cqsMap, Map<String, MarketMetrics> metricsMap) {
        return metricsMap.values().stream()
            .mapToDouble(m -> sortinoToExpectedReturn(m.sortinoRatio()))
            .max().orElse(0.09);
    }

    private Map<String, Integer> loadCqsMap() {
        String sql = """
            SELECT amfi_code, composite_quant_score 
            FROM fund_conviction_metrics
            WHERE calculation_date = (SELECT MAX(calculation_date) FROM fund_conviction_metrics)
            """;
        Map<String, Integer> map = new HashMap<>();
        jdbcTemplate.query(sql, rs -> {
            map.put(rs.getString("amfi_code"), rs.getInt("composite_quant_score"));
        });
        return map;
    }

    private double getCurrentNav(String amfi) {
        return amfiService.getLatestSchemeDetails(amfi).getNav().doubleValue();
    }

    private TacticalSignal buildSignal(StrategyTarget t, String amfi, SignalType action, double amount, 
                                     double targetPct, double actualPct, MarketMetrics m, List<String> justs) {
        return new TacticalSignal(t.schemeName(), amfi, action, String.format("%.2f", amount),
            targetPct, actualPct, t.sipPct(), t.status(), m.convictionScore(), 
            m.sortinoRatio(), m.maxDrawdown(), m.navPercentile3yr(), 
            m.drawdownFromAth(), m.returnZScore(), m.lastBuyDate(), justs);
    }

    public List<TacticalSignal> generateDailySignals(String investorPan, double monthlySip, double lumpsum) {
        return computeOpportunisticSignals(investorPan, lumpsum);
    }

    private List<TacticalSignal> weightSignalsByConviction(List<TacticalSignal> signals, double cash) {
        if (signals.isEmpty()) return signals;

        List<TacticalSignal> activeDrafts = signals.stream().map(sig -> {
            long daysSinceLast = ChronoUnit.DAYS.between(
                sig.lastBuyDate() != null ? sig.lastBuyDate() : LocalDate.of(1970, 1, 1),
                LocalDate.now());
            if (daysSinceLast < 21) {
                List<String> justs = new ArrayList<>(sig.justifications());
                justs.add(String.format("Cooldown: Last bought %d days ago. Wait %d more days.", 
                    daysSinceLast, 21 - (int)daysSinceLast));
                return createSignal(sig, SignalType.WATCH, "0", justs);
            }
            return sig;
        }).collect(Collectors.toList());

        if (cash <= 0) {
            return activeDrafts.stream()
                .filter(s -> SignalType.WATCH != s.action())
                .map(s -> createSignal(s, SignalType.BUY, s.amount(), s.justifications()))
                .collect(Collectors.toList());
        }

        double totalDemand = activeDrafts.stream()
            .filter(s -> SignalType.BUY == s.action())
            .mapToDouble(s -> Double.parseDouble(s.amount()) * Math.max(0.2, s.convictionScore() / 100.0))
            .sum();

        return activeDrafts.stream().map(sig -> {
            if (SignalType.BUY != sig.action()) return sig;
            
            double baseAmount = Double.parseDouble(sig.amount());
            double scoreMult = Math.max(0.2, sig.convictionScore() / 100.0);
            double allocatedAmount = totalDemand > 0 
                ? (baseAmount * scoreMult / totalDemand) * cash 
                : baseAmount;
            
            List<String> justs = new ArrayList<>(sig.justifications());
            justs.add(String.format("Allocated ₹%,.0f from available capital of ₹%,.0f.", allocatedAmount, cash));
            return createSignal(sig, SignalType.BUY, String.format("%.2f", allocatedAmount), justs);
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

    private MarketMetrics defaultMetrics() {
        return new MarketMetrics(0,0,0,0,0,0.5,0,0, LocalDate.of(1970,1,1));
    }

    private TacticalSignal createSignal(TacticalSignal s, SignalType action, String amt, List<String> justs) {
        return new TacticalSignal(s.schemeName(), s.amfiCode(), action, amt, s.plannedPercentage(), s.actualPercentage(), s.sipPercentage(), s.fundStatus(), s.convictionScore(), s.sortinoRatio(), s.maxDrawdown(), s.navPercentile3yr(), s.drawdownFromAth(), s.returnZScore(), s.lastBuyDate(), justs);
    }

    private Map<String, MarketMetrics> fetchMetricsForAmfi(String amfi) {
        String sql = """
            SELECT amfi_code, sortino_ratio, cvar_5, win_rate, max_drawdown,
                   conviction_score, nav_percentile_3yr, drawdown_from_ath, return_z_score
            FROM fund_conviction_metrics
            WHERE amfi_code = ?
            AND calculation_date = (SELECT MAX(calculation_date) FROM fund_conviction_metrics)
            """;
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, amfi);
        Map<String, MarketMetrics> map = new HashMap<>();
        if (!rows.isEmpty()) {
            Map<String, Object> r = rows.get(0);
            map.put(amfi, new MarketMetrics(
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
        return map;
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
}
