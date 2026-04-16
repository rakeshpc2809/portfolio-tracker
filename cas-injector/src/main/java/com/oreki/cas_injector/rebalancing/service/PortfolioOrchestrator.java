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
import java.math.BigDecimal;

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
import com.oreki.cas_injector.core.utils.FundStatus;

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
    private final LotAggregationService aggregationService;
    private final ConvictionMetricsRepository metricsRepo;
    private final ConvictionScoringService scoringService;
    private final HierarchicalRiskParityService hrpService;
    private final SystemicRiskMonitorService riskMonitor;
    private final TaxLossHarvestingService taxLossHarvestingService;
    private final com.oreki.cas_injector.transactions.repository.TransactionRepository txnRepo;
    private final RebalanceEngine rebalanceEngine;

    public GoogleSheetService getStrategyService() {
        return strategyService;
    }

    @Value("${hrp.blend.ratio:0.5}")
    private double hrpBlendRatio;

    private static final double MAX_SINGLE_FUND_CONCENTRATION = 0.30;

    public List<SipLineItem> computeSipPlan(String pan, double monthlySip) {
        log.info("🎯 Computing SIP Plan for {}: ₹{}", pan, monthlySip);
        
        List<TaxLot> openLots = taxLotRepository.findByStatusAndSchemeFolioInvestorPan("OPEN", pan);
        List<AggregatedHolding> holdings = aggregationService.aggregate(openLots);
        
        Map<String, AggregatedHolding> holdingsByIsin = holdings.stream()
            .collect(Collectors.toMap(AggregatedHolding::getIsin, h -> h));

        // Map ISIN to AMFI Code and Name to ISIN from pre-fetched lots
        Map<String, String> isinToAmfiMap = openLots.stream()
            .collect(Collectors.toMap(
                l -> l.getScheme().getIsin(),
                l -> CommonUtils.SANITIZE_AMFI.apply(l.getScheme().getAmfiCode()),
                (a, b) -> a));

        Map<String, MarketMetrics> metricsMap = metricsRepo.fetchLiveMetricsMap(pan);
        List<StrategyTarget> targets = strategyService.fetchLatestStrategy();
        double totalValue = holdings.stream().mapToDouble(AggregatedHolding::getCurrentValue).sum();

        // Compute HRP weights for held assets
        List<String> heldAmfis = isinToAmfiMap.values().stream().filter(s -> !s.isEmpty()).toList();
        Map<String, Double> hrpWeights = hrpService.computeHrpWeights(heldAmfis).weights();

        List<SipLineItem> plan = new ArrayList<>();
        for (StrategyTarget target : targets) {
            // HARD CONSTRAINT: Skip DROPPED or EXIT status funds
            if ("DROPPED".equalsIgnoreCase(target.status()) || "EXIT".equalsIgnoreCase(target.status())) {
                continue;
            }

            String isin = target.isin();
            String amfi = isinToAmfiMap.getOrDefault(isin, "");
            
            if (amfi.isEmpty()) {
                amfi = schemeRepository.findByIsin(isin)
                    .map(s -> CommonUtils.SANITIZE_AMFI.apply(s.getAmfiCode()))
                    .orElse("");
            }
            
            double targetPct = target.targetPortfolioPct();
            if (!amfi.isEmpty() && hrpWeights.containsKey(amfi)) {
                double hrpW = hrpWeights.get(amfi);
                targetPct = (targetPct * (1 - hrpBlendRatio)) + (hrpW * 100 * hrpBlendRatio);
            }

            AggregatedHolding h = holdingsByIsin.get(isin);
            double currentVal = (h != null) ? h.getCurrentValue() : 0.0;

            double cappedTargetPct = Math.min(targetPct, MAX_SINGLE_FUND_CONCENTRATION * 100);
            double targetValue = (cappedTargetPct / 100.0) * (totalValue + monthlySip);

            double gap = targetValue - currentVal;
            double sipAllocation = Math.max(0, Math.min(monthlySip, gap));

            StringBuilder infoNote = new StringBuilder();
            if (!amfi.isEmpty()) {
                txnRepo.findLatestBuyBySchemeAmfiCodeAndPan(amfi, pan)
                    .ifPresent(t -> infoNote.append(" Last buy: ").append(t.getDate()).append("."));
                txnRepo.findLatestSellBySchemeAmfiCodeAndPan(amfi, pan)
                    .ifPresent(t -> infoNote.append(" Last sell: ").append(t.getDate()).append("."));
            }

            if (sipAllocation > 100) {
                double standingAmount = (target.sipPct() / 100.0) * monthlySip;
                String mode = (sipAllocation <= standingAmount + 1.0) ? "SIP_STANDING" : "SIP_ADDITIONAL";
                String note = (mode.equals("SIP_STANDING") 
                    ? "STANDING: Deploy as per your existing instruction to " + target.schemeName() + "." 
                    : "ADDITIONAL: Gap of ₹" + Math.round(gap) + ". Consider adding beyond your SIP if you have the budget.") 
                    + infoNote.toString();

                plan.add(new SipLineItem(
                    target.schemeName(),
                    CommonUtils.NORMALIZE_NAME.apply(target.schemeName()),
                    target.isin(),
                    amfi,
                    sipAllocation,
                    target.sipPct(),
                    cappedTargetPct,
                    mode,
                    "DEPLOY",
                    note
                ));
            }
        }
        return plan;
    }

    public List<TacticalSignal> generateDailySignals(String pan, double monthlySip, double lumpsum) {
        List<TacticalSignal> all = new ArrayList<>();
        all.addAll(computeOpportunisticSignals(pan, lumpsum));
        all.addAll(computeActiveSellSignals(pan));
        all.addAll(computeExitQueue(pan));
        return all;
    }

    public List<com.oreki.cas_injector.rebalancing.dto.RebalancingTrade> computeRebalancingTrades(String pan) {
        List<TacticalSignal> sells = computeActiveSellSignals(pan);
        List<TacticalSignal> buys  = evaluateAll(pan).stream()
            .filter(s -> s.action() == SignalType.BUY)
            .sorted(Comparator.comparingDouble(
                s -> -(s.convictionScore() * Math.abs(s.returnZScore()))))
            .collect(Collectors.toList());

        List<com.oreki.cas_injector.rebalancing.dto.RebalancingTrade> trades = new ArrayList<>();

        for (TacticalSignal sell : sells) {
            double sellAmt = parseSignalAmount(sell.amount());
            double taxRate  = 0.125; 
            double taxCost  = sellAmt * taxRate * 0.5; 
            double proceeds = sellAmt - taxCost;

            if (!buys.isEmpty()) {
                TacticalSignal bestBuy = buys.get(0);
                buys.remove(0); 
                double buyAmt = Math.min(parseSignalAmount(bestBuy.amount()), proceeds);
                double convDelta = bestBuy.convictionScore() - sell.convictionScore();
                double zDelta    = Math.abs(bestBuy.returnZScore()) - Math.abs(sell.returnZScore());

                trades.add(new com.oreki.cas_injector.rebalancing.dto.RebalancingTrade(
                    sell.schemeName(), sell.amfiCode(),
                    sellAmt, taxCost, proceeds,
                    "Overweight by " + String.format("%.1f", sell.actualPercentage() - sell.plannedPercentage()) + "%",
                    bestBuy.schemeName(), bestBuy.amfiCode(),
                    buyAmt,
                    "Underweight by " + String.format("%.1f", bestBuy.plannedPercentage() - bestBuy.actualPercentage()) + "%",
                    convDelta, zDelta,
                    String.format("Rotating ₹%.0f from %s → %s (conviction delta: %+.0f)",
                        buyAmt,
                        CommonUtils.NORMALIZE_NAME.apply(sell.schemeName()),
                        CommonUtils.NORMALIZE_NAME.apply(bestBuy.schemeName()),
                        convDelta)
                ));
            }
        }
        return trades;
    }

    private double parseSignalAmount(String amount) {
        try {
            return Double.parseDouble(amount.replace(",", ""));
        } catch (Exception e) {
            return 0.0;
        }
    }

    public List<TacticalSignal> computeOpportunisticSignals(String pan, double lumpsum) {
        return evaluateAll(pan).stream()
            .filter(s -> s.action() == SignalType.BUY || (s.action() == SignalType.WATCH && "ACCUMULATOR".equals(s.fundStatus())))
            .sorted(Comparator.comparing(TacticalSignal::returnZScore))
            .collect(Collectors.toList());
    }

    public List<TacticalSignal> computeActiveSellSignals(String pan) {
        return evaluateAll(pan).stream()
            .filter(s -> s.action() == SignalType.SELL && !"DROPPED".equals(s.fundStatus()))
            .collect(Collectors.toList());
    }

    public List<TacticalSignal> computeExitQueue(String pan) {
        return evaluateAll(pan).stream()
            .filter(s -> "DROPPED".equals(s.fundStatus()) || "EXIT".equals(s.fundStatus()) || s.action() == SignalType.EXIT)
            .collect(Collectors.toList());
    }

    private List<TacticalSignal> evaluateAll(String pan) {
        List<TaxLot> openLots = taxLotRepository.findByStatusAndSchemeFolioInvestorPan("OPEN", pan);
        List<AggregatedHolding> holdings = aggregationService.aggregate(openLots);
        Map<String, MarketMetrics> metricsMap = metricsRepo.fetchLiveMetricsMap(pan);
        List<StrategyTarget> targets = strategyService.fetchLatestStrategy();
        double totalValue = holdings.stream().mapToDouble(AggregatedHolding::getCurrentValue).sum();

        Double fyLtcgRealized = jdbcTemplate.queryForObject("""
            SELECT COALESCE(SUM(cg.realized_gain), 0)
            FROM capital_gain_audit cg
            JOIN "transaction" t ON cg.sell_transaction_id = t.id
            JOIN scheme s ON t.scheme_id = s.id
            JOIN folio f ON s.folio_id = f.id
            WHERE f.investor_pan = ?
            AND cg.tax_category IN ('EQUITY_LTCG', 'HYBRID_LTCG', 'NON_EQUITY_LTCG_OLD')
            AND t.transaction_date >= ?
            """,
            Double.class, pan, CommonUtils.getCurrentFyStart());
        if (fyLtcgRealized == null) fyLtcgRealized = 0.0;

        Map<String, String> nameToAmfiMap = holdings.stream()
            .collect(Collectors.toMap(AggregatedHolding::getSchemeName, h -> {
                Scheme s = schemeRepository.findByName(h.getSchemeName()).orElse(null);
                return (s != null) ? CommonUtils.SANITIZE_AMFI.apply(s.getAmfiCode()) : "";
            }, (a, b) -> a));

        Map<String, StrategyTarget> targetByIsin = targets.stream()
            .collect(Collectors.toMap(StrategyTarget::isin, t -> t, (a, b) -> a));

        List<TacticalSignal> results = new ArrayList<>();

        // Process existing holdings
        for (AggregatedHolding h : holdings) {
            StrategyTarget t = targetByIsin.get(h.getIsin());
            if (t == null) {
                // If held but not in strategy, treat as DROPPED
                t = new StrategyTarget(h.getIsin(), h.getSchemeName(), 0.0, 0.0, "DROPPED", "OTHERS");
            }
            String amfi = nameToAmfiMap.get(h.getSchemeName());
            MarketMetrics m = metricsMap.getOrDefault(amfi, MarketMetrics.fromLegacy(50, 0, 0, 0, 0, 0.5, 0, 0, LocalDate.of(1970, 1, 1)));
            
            results.add(rebalanceEngine.evaluate(h, t, m, totalValue, amfi, holdings, nameToAmfiMap, t.targetPortfolioPct(), fyLtcgRealized));
        }

        // Process new entries (target > 0 but not held)
        Set<String> heldIsins = holdings.stream().map(AggregatedHolding::getIsin).collect(Collectors.toSet());
        for (StrategyTarget t : targets) {
            if (!heldIsins.contains(t.isin()) && t.targetPortfolioPct() > 0) {
                AggregatedHolding emptyH = new AggregatedHolding(t.schemeName(), 0, 0, 0, 0, 0, 0, 0, 0, 0, "UNKNOWN", "NEW_ENTRY", t.isin(), 0.0);
                String amfi = schemeRepository.findByIsin(t.isin()).map(s -> CommonUtils.SANITIZE_AMFI.apply(s.getAmfiCode())).orElse("");
                MarketMetrics m = metricsMap.getOrDefault(amfi, MarketMetrics.fromLegacy(50, 0, 0, 0, 0, 0.5, 0, 0, LocalDate.of(1970, 1, 1)));
                results.add(rebalanceEngine.evaluate(emptyH, t, m, totalValue, amfi, holdings, nameToAmfiMap, t.targetPortfolioPct(), fyLtcgRealized));
            }
        }

        return results;
    }
}
