package com.oreki.cas_injector.rebalancing.service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.oreki.cas_injector.backfill.service.NavService;
import com.oreki.cas_injector.convictionmetrics.dto.MarketMetrics;
import com.oreki.cas_injector.convictionmetrics.repository.ConvictionMetricsRepository;
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
import com.oreki.cas_injector.taxmanagement.dto.TlhOpportunity;
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
    private final ConvictionMetricsRepository metricsRepo;

    @Value("${hrp.blend.ratio:0.5}")
    private double hrpBlendRatio;

    public List<SipLineItem> computeSipPlan(String pan, double sipAmount) {
        List<StrategyTarget> targets = strategyService.fetchLatestStrategy();
        Map<String, MarketMetrics> metrics = metricsRepo.fetchLiveMetricsMap(pan);
        List<TlhOpportunity> tlhOpps = tlhService.scanForOpportunities(pan);

        List<Scheme> allSchemes = schemeRepository.findAll();
        Map<String, String> isinToAmfi = allSchemes.stream()
            .filter(s -> s.getIsin() != null)
            .collect(Collectors.toMap(Scheme::getIsin, Scheme::getAmfiCode, (a, b) -> a));

        return targets.stream()
            .filter(t -> t.sipPct() > 0 && !"dropped".equalsIgnoreCase(t.status()))
            .map(t -> {
                double amount = (t.sipPct() / 100.0) * sipAmount;
                String amfiCode = isinToAmfi.getOrDefault(t.isin(), "");
                MarketMetrics m = metrics.getOrDefault(amfiCode, defaultMetrics());

                String flag = m.navPercentile3yr() > 0.85 ? "CAUTION_EXPENSIVE" : "DEPLOY";
                String note = flag.equals("CAUTION_EXPENSIVE")
                    ? "Fund at " + Math.round(m.navPercentile3yr()*100) + "% of 3yr range. Consider deferring."
                    : "Deploy as per strategy.";

                for (TlhOpportunity opp : tlhOpps) {
                    if (opp.type() == TlhOpportunity.OpportunityType.SIP_REDIRECT && opp.amfiCode().equals(amfiCode)) {
                        flag = "SIP_REDIRECT_ADVISED";
                        note = opp.recommendation();
                        break;
                    }
                }

                return new SipLineItem(t.schemeName(), CommonUtils.NORMALIZE_NAME.apply(t.schemeName()), t.isin(), amfiCode,
                    amount, t.sipPct(), t.targetPortfolioPct(), t.status(), flag, note);
            })
            .sorted(Comparator.comparingDouble(SipLineItem::amount).reversed())
            .collect(Collectors.toList());
    }

    public List<TacticalSignal> computeOpportunisticSignals(String pan, double lumpsum) {
        List<StrategyTarget> targets = strategyService.fetchLatestStrategy();
        Map<String, MarketMetrics> metricsMap = metricsRepo.fetchLiveMetricsMap(pan);

        List<Scheme> allSchemes = schemeRepository.findAll();
        Map<String, String> isinToAmfi = allSchemes.stream()
            .filter(s -> s.getIsin() != null)
            .collect(Collectors.toMap(Scheme::getIsin, Scheme::getAmfiCode, (a, b) -> a));
        Map<String, String> nameToAmfiMap_Global = allSchemes.stream()
            .collect(Collectors.toMap(s -> s.getName().toLowerCase(), Scheme::getAmfiCode, (a, b) -> a));

        for (StrategyTarget t : targets) {
            String amfi = isinToAmfi.getOrDefault(t.isin(), "");
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
            .collect(Collectors.toMap(AggregatedHolding::getSchemeName, h -> nameToAmfiMap_Global.getOrDefault(h.getSchemeName().toLowerCase(), ""), (a, b) -> a));

        List<String> heldAmfiCodes = holdings.stream()
            .map(h -> nameToAmfiMap.get(h.getSchemeName()))
            .filter(c -> c != null && !c.isEmpty())
            .collect(Collectors.toList());
        
        HierarchicalRiskParityService.HrpResult hrpResult = hrpService.computeHrpWeights(heldAmfiCodes);
        Map<String, Double> hrpWeights = hrpResult.weights();

        Map<String, Double> actualWeights = holdings.stream()
            .filter(h -> nameToAmfiMap.get(h.getSchemeName()) != null)
            .collect(Collectors.toMap(h -> nameToAmfiMap.get(h.getSchemeName()), h -> h.getCurrentValue() / Math.max(totalPortfolioValue, 1.0)));
        List<String> concentrationRisks = hrpService.computeHercConcentrationSignal(hrpWeights, actualWeights);

        for (StrategyTarget target : targets) {
            if ("dropped".equalsIgnoreCase(target.status())) continue;

            AggregatedHolding holding = findHolding(holdings, target);
            String amfi = isinToAmfi.getOrDefault(target.isin(), "");
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
            TacticalSignal signal = rebalanceEngine.evaluate(holding, adjustedTarget, metrics, totalPortfolioValue, amfi, holdings, nameToAmfiMap, target.targetPortfolioPct());
            
            if (hrpActive) signal = setHrpActive(signal);

            if (concentrationRisks.contains(amfi)) {
                List<String> justs = new ArrayList<>(signal.justifications());
                justs.add("📊 HERC Concentration Alert: This fund's cluster is contributing disproportionate risk to your portfolio.");
                signal = createSignal(signal, signal.action(), signal.amount(), justs);
            }

            if (signal.action() == SignalType.BUY || signal.action() == SignalType.WATCH) {
                if (signal.action() == SignalType.BUY) {
                    double baseAmount = Math.max(10000.0, positionSizingService.calculateExecutionAmount(Double.parseDouble(signal.amount()), lumpsum, metrics));
                    List<String> justs = new ArrayList<>(signal.justifications());
                    if (wouldBreachConcentration(holding, baseAmount, totalPortfolioValue)) {
                        double maxDeploy = (MAX_SINGLE_FUND_CONCENTRATION * totalPortfolioValue) - holding.getCurrentValue();
                        if (maxDeploy < 5000.0) continue; 
                        baseAmount = Math.max(0.0, maxDeploy);
                        justs.add(String.format("⚠️ Concentration capped: Deploy limited to ₹%,.0f.", baseAmount));
                    }
                    signal = createSignal(signal, SignalType.BUY, String.format("%.2f", baseAmount), justs);
                }
                opportunisticDrafts.add(signal);
            }
        }
        return weightSignalsByConviction(opportunisticDrafts, lumpsum);
    }

    private static final double MAX_SINGLE_FUND_CONCENTRATION = 0.30;

    private boolean wouldBreachConcentration(AggregatedHolding holding, double deployAmount, double totalPortfolioValue) {
        if (totalPortfolioValue <= 0.0) return false;
        return (holding.getCurrentValue() + deployAmount) / totalPortfolioValue > MAX_SINGLE_FUND_CONCENTRATION;
    }

    public List<TacticalSignal> computeExitQueue(String pan) {
        List<StrategyTarget> targets = strategyService.fetchLatestStrategy();
        Map<String, MarketMetrics> metricsMap = metricsRepo.fetchLiveMetricsMap(pan);

        List<Scheme> allSchemes = schemeRepository.findAll();
        Map<String, String> nameToAmfiMap_Global = allSchemes.stream()
            .collect(Collectors.toMap(s -> s.getName().toLowerCase(), Scheme::getAmfiCode, (a, b) -> a));

        List<TaxLot> allLots = taxLotRepository.findByStatusAndSchemeFolioInvestorPan("OPEN", pan);
        List<AggregatedHolding> holdings = lotAggregationService.aggregate(allLots);
        double totalPortfolioValue = holdings.stream().mapToDouble(AggregatedHolding::getCurrentValue).sum();

        List<TacticalSignal> exitPlan = new ArrayList<>();
        Map<String, String> nameToAmfiMap = holdings.stream()
            .collect(Collectors.toMap(AggregatedHolding::getSchemeName, h -> nameToAmfiMap_Global.getOrDefault(h.getSchemeName().toLowerCase(), ""), (a, b) -> a));

        for (AggregatedHolding h : holdings) {
            StrategyTarget target = targets.stream()
                .filter(t -> t.isin().equalsIgnoreCase(h.getIsin()) || t.schemeName().equalsIgnoreCase(h.getSchemeName()))
                .findFirst()
                .orElse(new StrategyTarget(h.getIsin() != null ? h.getIsin() : "", h.getSchemeName(), 0.0, 0.0, "dropped", "DROPPED"));

            if (!"dropped".equalsIgnoreCase(target.status())) continue;
            if (h.getCurrentValue() < 100.0) continue; 

            String amfi = nameToAmfiMap.getOrDefault(h.getSchemeName(), "");
            MarketMetrics metrics = metricsMap.getOrDefault(amfi, defaultMetrics());
            TacticalSignal signal = rebalanceEngine.evaluate(h, target, metrics, totalPortfolioValue, amfi, holdings, nameToAmfiMap, target.targetPortfolioPct());
            if (signal.action() == SignalType.EXIT || (signal.action() == SignalType.HOLD && "DROPPED".equals(signal.fundStatus()))) {
                exitPlan.add(signal);
            }
        }

        exitPlan.sort(Comparator.comparing((TacticalSignal s) -> {
            AggregatedHolding h = holdings.stream().filter(x -> x.getSchemeName().equalsIgnoreCase(s.schemeName())).findFirst().orElse(null);
            String cat = h != null && h.getAssetCategory() != null ? h.getAssetCategory().toUpperCase() : "";
            return cat.contains("DEBT") || cat.contains("GILT") || cat.contains("BOND") || cat.contains("LIQUID") ? 0 : 1;
        }).thenComparing(Comparator.comparingDouble((TacticalSignal s) -> Double.parseDouble(s.amount())).reversed()));

        return exitPlan;
    }

    public List<TacticalSignal> computeActiveSellSignals(String pan) {
        List<StrategyTarget> targets = strategyService.fetchLatestStrategy();
        Map<String, MarketMetrics> metricsMap = metricsRepo.fetchLiveMetricsMap(pan);

        List<Scheme> allSchemes = schemeRepository.findAll();
        Map<String, String> isinToAmfi = allSchemes.stream()
            .filter(s -> s.getIsin() != null)
            .collect(Collectors.toMap(Scheme::getIsin, Scheme::getAmfiCode, (a, b) -> a));
        Map<String, String> nameToAmfiMap_Global = allSchemes.stream()
            .collect(Collectors.toMap(s -> s.getName().toLowerCase(), Scheme::getAmfiCode, (a, b) -> a));

        List<TaxLot> allLots = taxLotRepository.findByStatusAndSchemeFolioInvestorPan("OPEN", pan);
        List<AggregatedHolding> holdings = lotAggregationService.aggregate(allLots);
        double totalValue = holdings.stream().mapToDouble(AggregatedHolding::getCurrentValue).sum();

        List<TacticalSignal> sellSignals = new ArrayList<>();
        Map<String, String> nameToAmfiMap = holdings.stream()
            .collect(Collectors.toMap(AggregatedHolding::getSchemeName, h -> nameToAmfiMap_Global.getOrDefault(h.getSchemeName().toLowerCase(), ""), (a, b) -> a));

        for (StrategyTarget target : targets) {
            if ("dropped".equalsIgnoreCase(target.status())) continue; 
            AggregatedHolding holding = findHolding(holdings, target);
            if (holding.getCurrentValue() < 5000.0) continue; 

            String amfi = isinToAmfi.getOrDefault(target.isin(), "");
            MarketMetrics metrics = metricsMap.getOrDefault(amfi, defaultMetrics());
            TacticalSignal signal = rebalanceEngine.evaluate(holding, target, metrics, totalValue, amfi, holdings, nameToAmfiMap, target.targetPortfolioPct());
            if (signal.action() == SignalType.SELL || signal.action() == SignalType.HOLD) {
                if (holding.getCurrentValue() > (target.targetPortfolioPct() / 100.0 * totalValue) + (totalValue * 0.025)) {
                    sellSignals.add(signal);
                }
            }
        }
        return sellSignals;
    }

    public List<TacticalSignal> generateDailySignals(String investorPan, double monthlySip, double lumpsum) {
        return computeOpportunisticSignals(investorPan, lumpsum);
    }

    private List<TacticalSignal> weightSignalsByConviction(List<TacticalSignal> signals, double cash) {
        if (signals.isEmpty()) return signals;
        List<TacticalSignal> activeDrafts = signals.stream().map(sig -> {
            long daysSinceLast = ChronoUnit.DAYS.between(sig.lastBuyDate() != null ? sig.lastBuyDate() : LocalDate.of(1970, 1, 1), LocalDate.now());
            if (daysSinceLast < 21) {
                List<String> justs = new ArrayList<>(sig.justifications());
                justs.add(String.format("Cooldown: Wait %d more days.", 21 - (int)daysSinceLast));
                return createSignal(sig, SignalType.WATCH, "0", justs);
            }
            return sig;
        }).collect(Collectors.toList());

        if (cash <= 0.0) return activeDrafts.stream().filter(s -> SignalType.WATCH != s.action()).map(s -> createSignal(s, SignalType.BUY, s.amount(), s.justifications())).collect(Collectors.toList());

        double totalDemand = activeDrafts.stream().filter(s -> SignalType.BUY == s.action()).mapToDouble(s -> Double.parseDouble(s.amount()) * Math.max(0.2, s.convictionScore() / 100.0)).sum();
        return activeDrafts.stream().map(sig -> {
            if (SignalType.BUY != sig.action()) return sig;
            double baseAmount = Double.parseDouble(sig.amount());
            double allocatedAmount = totalDemand > 0.0 ? (baseAmount * Math.max(0.2, sig.convictionScore() / 100.0) / totalDemand) * cash : baseAmount;
            List<String> justs = new ArrayList<>(sig.justifications());
            justs.add(String.format("Allocated ₹%,.0f.", allocatedAmount));
            return createSignal(sig, SignalType.BUY, String.format("%.2f", allocatedAmount), justs);
        }).collect(Collectors.toList());
    }

    private AggregatedHolding findHolding(List<AggregatedHolding> holdings, StrategyTarget t) {
        return holdings.stream().filter(h -> h.getSchemeName().equalsIgnoreCase(t.schemeName())).findFirst().orElse(new AggregatedHolding(t.schemeName(), 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0, 0, "UNKNOWN", "DROPPED", t.isin(), 0.0));
    }

    private MarketMetrics defaultMetrics() { return MarketMetrics.fromLegacy(0, 0.0, 0.0, 0.0, 0.0, 0.5, 0.0, 0.0, LocalDate.of(1970, 1, 1)); }

    private TacticalSignal createSignal(TacticalSignal s, SignalType action, String amt, List<String> justs) {
        return new TacticalSignal(s.schemeName(), s.simpleName(), s.amfiCode(), action, amt, s.plannedPercentage(), s.actualPercentage(), s.sipPercentage(), s.fundStatus(), s.convictionScore(), s.sortinoRatio(), s.maxDrawdown(), s.navPercentile3yr(), s.drawdownFromAth(), s.returnZScore(), s.lastBuyDate(), justs, s.reasoningMetadata(), s.hurst20d(), s.hurst60d(), s.multiScaleRegime(), s.ouHalfLife(), s.ouValid(), s.ouBuyThreshold(), s.ouSellThreshold(), s.hrpOverrideActive());
    }

    private TacticalSignal setHrpActive(TacticalSignal s) {
        return new TacticalSignal(s.schemeName(), s.simpleName(), s.amfiCode(), s.action(), s.amount(), s.plannedPercentage(), s.actualPercentage(), s.sipPercentage(), s.fundStatus(), s.convictionScore(), s.sortinoRatio(), s.maxDrawdown(), s.navPercentile3yr(), s.drawdownFromAth(), s.returnZScore(), s.lastBuyDate(), s.justifications(), s.reasoningMetadata(), s.hurst20d(), s.hurst60d(), s.multiScaleRegime(), s.ouHalfLife(), s.ouValid(), s.ouBuyThreshold(), s.ouSellThreshold(), true);
    }

    private Map<String, MarketMetrics> fetchMetricsForAmfi(String amfi) {
        String sql = "SELECT * FROM fund_conviction_metrics WHERE amfi_code = ? AND calculation_date = (SELECT MAX(calculation_date) FROM fund_conviction_metrics)";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, amfi);
        Map<String, MarketMetrics> map = new HashMap<>();
        if (!rows.isEmpty()) {
            Map<String, Object> r = rows.get(0);
            map.put(amfi, new MarketMetrics(getSafeInt(r.get("conviction_score")), getSafeDouble(r.get("sortino_ratio")), getSafeDouble(r.get("cvar_5")), getSafeDouble(r.get("win_rate")), getSafeDouble(r.get("max_drawdown")), getSafeDouble(r.get("nav_percentile_3yr")), getSafeDouble(r.get("drawdown_from_ath")), getSafeDouble(r.get("return_z_score")), LocalDate.of(1970, 1, 1), getSafeDouble(r.get("rolling_z_score_252")), getSafeDouble(r.get("hurst_exponent")), getSafeDouble(r.get("volatility_tax")), String.valueOf(r.getOrDefault("hurst_regime", "RANDOM_WALK")), getSafeDouble(r.get("historical_rarity_pct")), getSafeDouble(r.get("hurst_20d")), getSafeDouble(r.get("hurst_60d")), String.valueOf(r.getOrDefault("multi_scale_regime", "RANDOM_WALK")), getSafeDouble(r.get("ou_theta")), getSafeDouble(r.get("ou_mu")), getSafeDouble(r.get("ou_sigma")), getSafeDouble(r.get("ou_half_life")), getSafeBoolean(r.get("ou_valid")), getSafeDouble(r.get("ou_buy_threshold")), getSafeDouble(r.get("ou_sell_threshold")), String.valueOf(r.getOrDefault("hmm_state", "STRESSED_NEUTRAL")), getSafeDouble(r.get("hmm_bull_prob")), getSafeDouble(r.get("hmm_bear_prob")), getSafeDouble(r.get("hmm_transition_bear"))));
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
