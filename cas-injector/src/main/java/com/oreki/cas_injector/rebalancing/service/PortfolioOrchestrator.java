package com.oreki.cas_injector.rebalancing.service;

import com.oreki.cas_injector.dashboard.dto.UnifiedTacticalPayload;
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
import com.oreki.cas_injector.convictionmetrics.service.PythonQuantClient;
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
    private final PythonQuantClient pythonQuantClient;

    public GoogleSheetService getStrategyService() {
        return strategyService;
    }

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

    public List<com.oreki.cas_injector.rebalancing.dto.RebalancingTrade> computeRebalancingTrades(String pan) {
        return computeRebalancingTradesFromSignals(pan, evaluateAll(pan));
    }

    private double parseSignalAmount(String amount) {
        try {
            return amount != null ? Double.parseDouble(amount.replace(",", "")) : 0.0;
        } catch (Exception e) {
            return 0.0;
        }
    }

    public UnifiedTacticalPayload generateUnifiedPayload(String pan, double monthlySip, double lumpsum) {
        log.info("🎯 Generating Unified Tactical Payload for PAN: {}", pan);
        List<TacticalSignal> all = evaluateAll(pan);
        
        // 1. Partition signals using stream filters to prevent redundant computation
        List<TacticalSignal> opportunistic = all.stream()
            .filter(s -> s.action() == SignalType.BUY || (s.action() == SignalType.WATCH && s.fundStatus() == FundStatus.ACCUMULATOR))
            .sorted(Comparator.comparing(TacticalSignal::returnZScore))
            .collect(Collectors.toList());

        List<TacticalSignal> activeSells = all.stream()
            .filter(s -> s.action() == SignalType.SELL && s.fundStatus() != FundStatus.DROPPED)
            .collect(Collectors.toList());

        List<TacticalSignal> exitQueue = all.stream()
            .filter(s -> s.fundStatus() == FundStatus.DROPPED || s.fundStatus() == FundStatus.EXIT || s.action() == SignalType.EXIT)
            .collect(Collectors.toList());

        // 2. Compute SIP Plan (Already optimized or can be passed 'all' if needed)
        List<SipLineItem> sip = computeSipPlan(pan, monthlySip); 

        // 3. Compute Rebalancing Trades using already evaluated signals
        List<com.oreki.cas_injector.rebalancing.dto.RebalancingTrade> rebalancingTrades = computeRebalancingTradesFromSignals(pan, all);

        // 4. TLH Opportunities
        List<com.oreki.cas_injector.taxmanagement.dto.TlhOpportunity> harvest = java.util.Collections.emptyList();
        try {
            harvest = taxLossHarvestingService.scanForOpportunities(pan);
        } catch (Exception e) {
            log.warn("TLH scan failed: {}", e.getMessage());
        }

        double totalHarvest = harvest.stream().mapToDouble(com.oreki.cas_injector.taxmanagement.dto.TlhOpportunity::estimatedTaxSaving).sum();

        double totalExitValue = exitQueue.stream()
            .mapToDouble(s -> parseSignalAmount(s.amount()))
            .sum();

        return UnifiedTacticalPayload.builder()
            .sipPlan(sip)
            .opportunisticSignals(opportunistic)
            .activeSellSignals(activeSells)
            .exitQueue(exitQueue)
            .harvestOpportunities(harvest)
            .rebalancingTrades(rebalancingTrades)
            .totalExitValue(totalExitValue)
            .totalHarvestValue(totalHarvest)
            .droppedFundsCount(exitQueue.size())
            .build();
    }

    public List<com.oreki.cas_injector.rebalancing.dto.RebalancingTrade> computeRebalancingTradesFromSignals(String pan, List<TacticalSignal> allSignals) {
        List<TacticalSignal> sells = allSignals.stream()
            .filter(s -> s.action() == SignalType.SELL && s.fundStatus() != FundStatus.DROPPED)
            .collect(Collectors.toList());
            
        List<TacticalSignal> buys  = allSignals.stream()
            .filter(s -> s.action() == SignalType.BUY)
            .sorted(Comparator.comparingDouble(
                s -> -(s.convictionScore() * Math.abs(s.returnZScore()))))
            .collect(Collectors.toCollection(ArrayList::new)); // Mutable for removal

        List<com.oreki.cas_injector.rebalancing.dto.RebalancingTrade> trades = new ArrayList<>();

        Double slab = metricsRepo.getJdbcTemplate().queryForObject(
            "SELECT tax_slab FROM investor WHERE pan = ?", Double.class, pan);
        double slabRate = (slab != null) ? slab : 0.30;

        for (TacticalSignal sell : sells) {
            double sellAmt = parseSignalAmount(sell.amount());
            
            // Accurate Tax Calculation
            List<TaxLot> fundLots = taxLotRepository.findByStatusAndSchemeAmfiCodeAndSchemeFolioInvestorPan(
                "OPEN", sell.amfiCode(), pan);
            String category = amfiService.getLatestSchemeDetails(sell.amfiCode()).getCategory();
            
            var taxRes = taxSimulator.simulateHifoExit(fundLots, category, slabRate);
            
            double totalValue = fundLots.stream().mapToDouble(l -> l.getRemainingUnits().doubleValue() * 
                amfiService.getLatestSchemeDetails(sell.amfiCode()).getNav().doubleValue()).sum();
            
            double sellRatio = (totalValue > 0) ? Math.min(1.0, sellAmt / totalValue) : 1.0;
            double taxCost  = taxRes.estimatedTax() * sellRatio; 
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

        // Systemic Risk Assessment (Once per portfolio)
        SystemicRiskMonitorService.TailRiskLevel tailRisk = riskMonitor.assessTailRisk(holdings, metricsMap, nameToAmfiMap);

        List<PythonQuantClient.PythonAggregatedHolding> pyHoldings = holdings.stream()
            .map(h -> new PythonQuantClient.PythonAggregatedHolding(
                h.getIsin(), h.getSchemeName(), h.getCurrentValue(), h.getLtcgAmount(), h.getStcgAmount(), h.getDaysToNextLtcg(), h.getNav()))
            .toList();
            
        List<PythonQuantClient.PythonStrategyTarget> pyTargets = targets.stream()
            .map(t -> new PythonQuantClient.PythonStrategyTarget(
                t.isin(), t.schemeName(), t.targetPortfolioPct(), t.sipPct(), t.status(), t.bucket()))
            .toList();
            
        Map<String, PythonQuantClient.PythonMarketMetrics> pyMetrics = metricsMap.entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getKey, e -> new PythonQuantClient.PythonMarketMetrics(
                e.getKey(), e.getValue().convictionScore(), e.getValue().rollingZScore252(), 
                e.getValue().hurstExponent(), e.getValue().hurstRegime(), e.getValue().hmmState(), 
                e.getValue().hmmTransitionBearProb(), e.getValue().ouValid(), e.getValue().ouHalfLife(), 
                e.getValue().volatilityTax(), e.getValue().historicalRarityPct())));
                
        PythonQuantClient.PythonRebalanceRequest req = new PythonQuantClient.PythonRebalanceRequest(
            pan, totalValue, fyLtcgRealized, tailRisk.name(), pyHoldings, pyTargets, pyMetrics, nameToAmfiMap);
            
        List<PythonQuantClient.PythonTacticalSignal> pySignals = pythonQuantClient.rebalancePortfolio(req);
        
        List<TacticalSignal> results = new ArrayList<>();
        for (PythonQuantClient.PythonTacticalSignal ps : pySignals) {
            String amfi = ps.amfi_code();
            MarketMetrics m = metricsMap.getOrDefault(amfi, MarketMetrics.fromLegacy(50, 0, 0, 0, 0, 0.5, 0, 0, java.time.LocalDate.of(1970, 1, 1)));
            
            results.add(TacticalSignal.builder()
                .schemeName(ps.scheme_name())
                .simpleName(CommonUtils.NORMALIZE_NAME.apply(ps.scheme_name()))
                .amfiCode(ps.amfi_code())
                .action(SignalType.valueOf(ps.action()))
                .amount(String.format(java.util.Locale.US, "%.2f", Math.abs(ps.amount())))
                .plannedPercentage(ps.planned_percentage())
                .actualPercentage(ps.actual_percentage())
                .sipPercentage(0) 
                .fundStatus(FundStatus.valueOf(ps.fund_status()))
                .convictionScore(m.convictionScore())
                .sortinoRatio(m.sortinoRatio())
                .maxDrawdown(m.maxDrawdown())
                .navPercentile1yr(m.navPercentile1yr())
                .navPercentile3yr(m.navPercentile3yr())
                .drawdownFromAth(m.drawdownFromAth())
                .returnZScore(m.returnZScore())
                .lastBuyDate(m.lastBuyDate())
                .justifications(ps.justifications())
                .reasoningMetadata(com.oreki.cas_injector.rebalancing.dto.ReasoningMetadata.neutral(ps.scheme_name()))
                .yieldScore(m.yieldScore())
                .riskScore(m.riskScore())
                .valueScore(m.valueScore())
                .painScore(m.painScore())
                .regimeScore(m.regimeScore())
                .frictionScore(m.frictionScore())
                .expenseScore(m.expenseScore())
                .expenseRatio(m.expenseRatio())
                .aumCr(m.aumCr())
                .ouHalfLife(m.ouHalfLife())
                .ouValid(m.ouValid())
                .build());
        }

        return results;
    }
}
