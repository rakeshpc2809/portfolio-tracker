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

import org.springframework.beans.factory.annotation.Value;
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
import com.oreki.cas_injector.rebalancing.dto.ReasoningMetadata;
import com.oreki.cas_injector.taxmanagement.dto.TlhOpportunity;
import com.oreki.cas_injector.taxmanagement.dto.TaxSimulationResult;
import com.oreki.cas_injector.taxmanagement.service.TaxSimulatorService;
import com.oreki.cas_injector.taxmanagement.service.TaxLossHarvestingService;
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
    private final SystemicRiskMonitorService systemicRiskMonitor;
    private final ConvictionScoringService convictionScoringService;
    private final PositionSizingService positionSizingService;
    private final LotAggregationService lotAggregationService;
    private final RebalanceEngine rebalanceEngine;
    private final HierarchicalRiskParityService hrpService;
    private final TaxLossHarvestingService tlhService;

    @Value("${hrp.blend.ratio:0.5}")
    private double hrpBlendRatio;

    // ==========================================
    // 🌟 MODE 1: MONTHLY SIP PLAN (Pure Sheet)
    // ==========================================
    public List<SipLineItem> computeSipPlan(String pan, double sipAmount) {
        List<StrategyTarget> targets = strategyService.fetchLatestStrategy();
        Map<String, MarketMetrics> metrics = fetchLiveMetricsMap(pan);
        List<TlhOpportunity> tlhOpps = tlhService.scanForOpportunities(pan);

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

                // Check for SIP Redirect
                for (TlhOpportunity opp : tlhOpps) {
                    if (opp.type() == TlhOpportunity.OpportunityType.SIP_REDIRECT && opp.amfiCode().equals(amfiCode)) {
                        flag = "SIP_REDIRECT_ADVISED";
                        note = opp.recommendation();
                        break;
                    }
                }

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
        
        List<TacticalSignal> opportunisticDrafts = new ArrayList<>();

        Map<String, String> nameToAmfiMap = holdings.stream()
            .collect(Collectors.toMap(AggregatedHolding::getSchemeName, h -> amfiCodeFor(h), (a, b) -> a));

        List<String> heldAmfiCodes = holdings.stream()
            .map(h -> nameToAmfiMap.get(h.getSchemeName()))
            .filter(c -> c != null && !c.isEmpty())
            .collect(Collectors.toList());
        Map<String, Double> hrpWeights = hrpService.computeHrpWeights(heldAmfiCodes);
        Map<String, Double> actualWeights = holdings.stream()
            .filter(h -> nameToAmfiMap.get(h.getSchemeName()) != null)
            .collect(Collectors.toMap(h -> nameToAmfiMap.get(h.getSchemeName()), h -> h.getCurrentValue() / Math.max(totalPortfolioValue, 1.0)));
        List<String> concentrationRisks = hrpService.computeHercConcentrationSignal(hrpWeights, actualWeights);

        for (StrategyTarget target : targets) {
            if ("dropped".equalsIgnoreCase(target.status())) continue;

            AggregatedHolding holding = findHolding(holdings, target);
            String amfi = amfiCodeFor(target);
            MarketMetrics metrics = metricsMap.getOrDefault(amfi, defaultMetrics());
            
            double effectiveTargetPct = target.targetPortfolioPct();
            boolean hrpActive = false;
            if (hrpWeights.containsKey(amfi)) {
                double hrpPct = hrpWeights.get(amfi) * 100.0;
                effectiveTargetPct = (1.0 - hrpBlendRatio) * target.targetPortfolioPct() + hrpBlendRatio * hrpPct;
                if (Math.abs(effectiveTargetPct - target.targetPortfolioPct()) > 0.5) {
                    hrpActive = true;
                }
            }

            StrategyTarget adjustedTarget = new StrategyTarget(target.isin(), target.schemeName(), effectiveTargetPct, target.sipPct(), target.status(), target.bucket());

            TacticalSignal signal = rebalanceEngine.evaluate(holding, adjustedTarget, metrics, totalPortfolioValue, amfi, holdings, nameToAmfiMap);
            
            if (hrpActive) {
                signal = setHrpActive(signal);
            }

            if (concentrationRisks.contains(amfi)) {
                List<String> justs = new ArrayList<>(signal.justifications());
                justs.add("📊 HERC Concentration Alert: This fund's cluster is contributing disproportionate risk to your portfolio. Trimming improves diversification even if nominal drift looks small.");
                signal = createSignal(signal, signal.action(), signal.amount(), justs);
            }

            // We only care about BUY/WATCH signals in this mode
            if (signal.action() == SignalType.BUY || signal.action() == SignalType.WATCH) {
                // Apply concentration guard if it's a BUY
                if (signal.action() == SignalType.BUY) {
                    double baseAmount = Math.max(10000.0, 
                        positionSizingService.calculateExecutionAmount(Double.parseDouble(signal.amount()), lumpsum, metrics));
                    
                    List<String> justs = new ArrayList<>(signal.justifications());
                    if (wouldBreachConcentration(holding, baseAmount, totalPortfolioValue)) {
                        double maxDeploy = (MAX_SINGLE_FUND_CONCENTRATION * totalPortfolioValue) - holding.getCurrentValue();
                        if (maxDeploy < 5000.0) {
                            log.info("⛔ Concentration guard: {} already near 30% cap, skipping signal.", target.schemeName());
                            continue; 
                        }
                        baseAmount = Math.max(0.0, maxDeploy);
                        justs.add(String.format(
                            "⚠️ Concentration capped: Deploy limited to ₹%,.0f to stay under 30%% single-fund limit.",
                            baseAmount));
                    }
                    
                    signal = createSignal(signal, SignalType.BUY, String.format("%.2f", baseAmount), justs);
                }
                opportunisticDrafts.add(signal);
            }
        }

        return weightSignalsByConviction(opportunisticDrafts, lumpsum);
    }

    private static final double MAX_SINGLE_FUND_CONCENTRATION = 0.30; // 30% cap

    private boolean wouldBreachConcentration(AggregatedHolding holding, double deployAmount,
                                              double totalPortfolioValue) {
        if (totalPortfolioValue <= 0.0) return false;
        double newValue      = holding.getCurrentValue() + deployAmount;
        double newConcentration = newValue / totalPortfolioValue;
        return newConcentration > MAX_SINGLE_FUND_CONCENTRATION;
    }

    // ==========================================
    // 🌟 MODE 3: EXIT QUEUE (Dropped Funds)
    // ==========================================
    public List<TacticalSignal> computeExitQueue(String pan) {
        List<StrategyTarget> targets = strategyService.fetchLatestStrategy();
        Map<String, MarketMetrics> metricsMap = fetchLiveMetricsMap(pan);

        List<TaxLot> allLots = taxLotRepository.findByStatusAndSchemeFolioInvestorPan("OPEN", pan);
        List<AggregatedHolding> holdings = lotAggregationService.aggregate(allLots);
        double totalPortfolioValue = holdings.stream().mapToDouble(AggregatedHolding::getCurrentValue).sum();

        List<TacticalSignal> exitPlan = new ArrayList<>();

        Map<String, String> nameToAmfiMap = holdings.stream()
            .collect(Collectors.toMap(AggregatedHolding::getSchemeName, h -> amfiCodeFor(h), (a, b) -> a));

        for (AggregatedHolding h : holdings) {
            StrategyTarget target = targets.stream()
                .filter(t -> t.isin().equalsIgnoreCase(h.getIsin()) || t.schemeName().equalsIgnoreCase(h.getSchemeName()))
                .findFirst()
                .orElse(new StrategyTarget(h.getIsin() != null ? h.getIsin() : "", h.getSchemeName(), 0.0, 0.0, "dropped", "DROPPED"));

            if (!"dropped".equalsIgnoreCase(target.status())) continue;
            if (h.getCurrentValue() < 100.0) continue; 

            String amfi = amfiCodeFor(h);
            MarketMetrics metrics = metricsMap.getOrDefault(amfi, defaultMetrics());
            
            TacticalSignal signal = rebalanceEngine.evaluate(h, target, metrics, totalPortfolioValue, amfi, holdings, nameToAmfiMap);
            if (signal.action() == SignalType.EXIT || (signal.action() == SignalType.HOLD && "DROPPED".equals(signal.fundStatus()))) {
                exitPlan.add(signal);
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
        List<StrategyTarget> targets = strategyService.fetchLatestStrategy();
        Map<String, MarketMetrics> metricsMap = fetchLiveMetricsMap(pan);

        List<TaxLot> allLots = taxLotRepository.findByStatusAndSchemeFolioInvestorPan("OPEN", pan);
        List<AggregatedHolding> holdings = lotAggregationService.aggregate(allLots);
        double totalValue = holdings.stream().mapToDouble(AggregatedHolding::getCurrentValue).sum();

        List<TacticalSignal> sellSignals = new ArrayList<>();

        Map<String, String> nameToAmfiMap = holdings.stream()
            .collect(Collectors.toMap(AggregatedHolding::getSchemeName, h -> amfiCodeFor(h), (a, b) -> a));

        for (StrategyTarget target : targets) {
            if ("dropped".equalsIgnoreCase(target.status())) continue; 
            
            AggregatedHolding holding = findHolding(holdings, target);
            if (holding.getCurrentValue() < 5000.0) continue; 

            String amfi = amfiCodeFor(target);
            MarketMetrics metrics = metricsMap.getOrDefault(amfi, defaultMetrics());

            TacticalSignal signal = rebalanceEngine.evaluate(holding, target, metrics, totalValue, amfi, holdings, nameToAmfiMap);
            
            if (signal.action() == SignalType.SELL || signal.action() == SignalType.HOLD) {
                // If it's a SELL, it's an active sell. If it's HOLD but was overweight, the engine decided to override.
                // We show both here as "Active Rebalancing" decisions.
                if (holding.getCurrentValue() > (target.targetPortfolioPct() / 100.0 * totalValue) + (totalValue * 0.025)) {
                    sellSignals.add(signal);
                }
            }
        }

        return sellSignals;
    }

    private double sortinoToExpectedReturn(double sortino) {
        double MAR = 0.07;
        return MAR + Math.max(0.0, (sortino - 1.0) * 0.03); 
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

        if (cash <= 0.0) {
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
            double allocatedAmount = totalDemand > 0.0 
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
            .findFirst().orElse(new AggregatedHolding(t.schemeName(), 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0, 0, "UNKNOWN", "DROPPED", t.isin(), 0.0));
    }

    private String amfiCodeFor(StrategyTarget t) {
        return schemeRepository.findByIsin(t.isin()).map(Scheme::getAmfiCode).orElse("");
    }

    private String amfiCodeFor(AggregatedHolding h) {
        return schemeRepository.findByNameIgnoreCase(h.getSchemeName()).map(Scheme::getAmfiCode).orElse("");
    }

    private MarketMetrics defaultMetrics() {
        return MarketMetrics.fromLegacy(0, 0.0, 0.0, 0.0, 0.0, 0.5, 0.0, 0.0, LocalDate.of(1970, 1, 1));
    }

    private TacticalSignal createSignal(TacticalSignal s, SignalType action, String amt, List<String> justs) {
        return new TacticalSignal(s.schemeName(), s.amfiCode(), action, amt, s.plannedPercentage(), s.actualPercentage(), s.sipPercentage(), s.fundStatus(), s.convictionScore(), s.sortinoRatio(), s.maxDrawdown(), s.navPercentile3yr(), s.drawdownFromAth(), s.returnZScore(), s.lastBuyDate(), justs, s.reasoningMetadata(), s.hurst20d(), s.hurst60d(), s.multiScaleRegime(), s.ouHalfLife(), s.ouValid(), s.ouBuyThreshold(), s.ouSellThreshold(), s.hrpOverrideActive());
    }

    private TacticalSignal setHrpActive(TacticalSignal s) {
        return new TacticalSignal(s.schemeName(), s.amfiCode(), s.action(), s.amount(), s.plannedPercentage(), s.actualPercentage(), s.sipPercentage(), s.fundStatus(), s.convictionScore(), s.sortinoRatio(), s.maxDrawdown(), s.navPercentile3yr(), s.drawdownFromAth(), s.returnZScore(), s.lastBuyDate(), s.justifications(), s.reasoningMetadata(), s.hurst20d(), s.hurst60d(), s.multiScaleRegime(), s.ouHalfLife(), s.ouValid(), s.ouBuyThreshold(), s.ouSellThreshold(), true);
    }

    private Map<String, MarketMetrics> fetchMetricsForAmfi(String amfi) {
        String sql = """
            SELECT amfi_code, sortino_ratio, cvar_5, win_rate, max_drawdown,
                   conviction_score, nav_percentile_3yr, drawdown_from_ath, return_z_score,
                   rolling_z_score_252, hurst_exponent, volatility_tax, hurst_regime, historical_rarity_pct,
                   hurst_20d, hurst_60d, multi_scale_regime,
                   ou_theta, ou_mu, ou_sigma, ou_half_life, ou_valid, ou_buy_threshold, ou_sell_threshold,
                   hmm_state, hmm_bull_prob, hmm_bear_prob, hmm_transition_bear
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
                LocalDate.of(1970, 1, 1),
                getSafeDouble(r.get("rolling_z_score_252")),
                getSafeDouble(r.get("hurst_exponent")),
                getSafeDouble(r.get("volatility_tax")),
                String.valueOf(r.getOrDefault("hurst_regime", "RANDOM_WALK")),
                getSafeDouble(r.get("historical_rarity_pct")),
                getSafeDouble(r.get("hurst_20d")),
                getSafeDouble(r.get("hurst_60d")),
                String.valueOf(r.getOrDefault("multi_scale_regime", "RANDOM_WALK")),
                getSafeDouble(r.get("ou_theta")),
                getSafeDouble(r.get("ou_mu")),
                getSafeDouble(r.get("ou_sigma")),
                getSafeDouble(r.get("ou_half_life")),
                getSafeBoolean(r.get("ou_valid")),
                getSafeDouble(r.get("ou_buy_threshold")),
                getSafeDouble(r.get("ou_sell_threshold")),
                String.valueOf(r.getOrDefault("hmm_state", "STRESSED_NEUTRAL")),
                getSafeDouble(r.get("hmm_bull_prob")),
                getSafeDouble(r.get("hmm_bear_prob")),
                getSafeDouble(r.get("hmm_transition_bear"))
            ));
        }
        return map;
    }

   private Map<String, MarketMetrics> fetchLiveMetricsMap(String pan) {
        String sql = """
            SELECT m.amfi_code, m.sortino_ratio, m.cvar_5, m.win_rate, m.max_drawdown, 
                   m.conviction_score, m.nav_percentile_3yr, m.drawdown_from_ath, m.return_z_score,
                   m.rolling_z_score_252, m.hurst_exponent, m.volatility_tax, m.hurst_regime, m.historical_rarity_pct,
                   m.hurst_20d, m.hurst_60d, m.multi_scale_regime,
                   m.ou_theta, m.ou_mu, m.ou_sigma, m.ou_half_life, m.ou_valid, m.ou_buy_threshold, m.ou_sell_threshold,
                   m.hmm_state, m.hmm_bull_prob, m.hmm_bear_prob, m.hmm_transition_bear
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

        Map<String, LocalDate> lastBuyDates = new HashMap<>();
        jdbcTemplate.query(lastBuySql, rs -> {
            String amfi = rs.getString("amfi_code");
            java.sql.Date d = rs.getDate("last_buy");
            if (d != null) {
                lastBuyDates.put(amfi, d.toLocalDate());
            }
        }, pan);

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
                lastBuyDates.getOrDefault(amfi, LocalDate.of(1970, 1, 1)),
                getSafeDouble(r.get("rolling_z_score_252")),
                getSafeDouble(r.get("hurst_exponent")),
                getSafeDouble(r.get("volatility_tax")),
                String.valueOf(r.getOrDefault("hurst_regime", "RANDOM_WALK")),
                getSafeDouble(r.get("historical_rarity_pct")),
                getSafeDouble(r.get("hurst_20d")),
                getSafeDouble(r.get("hurst_60d")),
                String.valueOf(r.getOrDefault("multi_scale_regime", "RANDOM_WALK")),
                getSafeDouble(r.get("ou_theta")),
                getSafeDouble(r.get("ou_mu")),
                getSafeDouble(r.get("ou_sigma")),
                getSafeDouble(r.get("ou_half_life")),
                getSafeBoolean(r.get("ou_valid")),
                getSafeDouble(r.get("ou_buy_threshold")),
                getSafeDouble(r.get("ou_sell_threshold")),
                String.valueOf(r.getOrDefault("hmm_state", "STRESSED_NEUTRAL")),
                getSafeDouble(r.get("hmm_bull_prob")),
                getSafeDouble(r.get("hmm_bear_prob")),
                getSafeDouble(r.get("hmm_transition_bear"))
            ));
        }
        return map;
    }

    private double getSafeDouble(Object obj) { return obj == null ? 0.0 : ((Number) obj).doubleValue(); }
    private int getSafeInt(Object obj) { return obj == null ? 0 : ((Number) obj).intValue(); }
    private boolean getSafeBoolean(Object obj) {
        if (obj == null) return false;
        if (obj instanceof Boolean) return (Boolean) obj;
        if (obj instanceof Number) return ((Number) obj).intValue() != 0;
        return false;
    }
}
