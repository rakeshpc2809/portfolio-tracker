package com.oreki.cas_injector.rebalancing.service;

import com.oreki.cas_injector.dashboard.dto.UnifiedTacticalPayload;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.math.BigDecimal;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.cache.CacheManager;

import com.oreki.cas_injector.backfill.service.NavService;
import com.oreki.cas_injector.convictionmetrics.dto.MarketMetrics;
import com.oreki.cas_injector.convictionmetrics.repository.ConvictionMetricsRepository;
import com.oreki.cas_injector.convictionmetrics.service.ConvictionScoringService;

import com.oreki.cas_injector.core.service.StrategyService;
import com.oreki.cas_injector.core.dto.AggregatedHolding;
import com.oreki.cas_injector.core.model.Scheme;
import com.oreki.cas_injector.core.repository.SchemeRepository;
import com.oreki.cas_injector.core.service.LotAggregationService;

import com.oreki.cas_injector.core.utils.CommonUtils;
import com.oreki.cas_injector.core.utils.SignalType;
import com.oreki.cas_injector.rebalancing.dto.SipLineItem;
import com.oreki.cas_injector.rebalancing.dto.StrategyTarget;
import com.oreki.cas_injector.rebalancing.dto.TacticalSignal;
import com.oreki.cas_injector.rebalancing.dto.RebalancingTrade;
import com.oreki.cas_injector.rebalancing.dto.RebalanceActionDTO;
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
public class RebalanceOrchestrator {
    
    private final StrategyService strategyService;
    private final NavService amfiService; 
    private final TaxLotRepository taxLotRepository; 
    private final SchemeRepository schemeRepository;
    private final TaxSimulatorService taxSimulator;
    private final JdbcTemplate jdbcTemplate;
    private final LotAggregationService aggregationService;
    private final ConvictionMetricsRepository metricsRepo;
    private final ConvictionScoringService scoringService;
    private final HierarchicalRiskParityService hrpService;

    private final TaxLossHarvestingService taxLossHarvestingService;
    private final com.oreki.cas_injector.transactions.repository.TransactionRepository txnRepo;
    private final RebalanceEngine rebalanceEngine;

    public StrategyService getStrategyService() {
        return strategyService;
    }

    private static final double MAX_SINGLE_FUND_CONCENTRATION = 0.30;

    public List<SipLineItem> computeSipPlan(String pan, double monthlySip) {
        log.info("🎯 Computing SIP Plan for {}: ₹{}", pan, monthlySip);
        
        List<TaxLot> openLots = taxLotRepository.findByStatusAndSchemeFolioInvestorPan("OPEN", pan);
        List<AggregatedHolding> holdings = aggregationService.aggregate(openLots);
        
        Map<String, AggregatedHolding> holdingsByIsin = holdings.stream()
            .collect(Collectors.toMap(AggregatedHolding::getIsin, h -> h));

        Map<String, String> isinToAmfiMap = openLots.stream()
            .collect(Collectors.toMap(
                l -> l.getScheme().getIsin(),
                l -> CommonUtils.SANITIZE_AMFI.apply(l.getScheme().getAmfiCode()),
                (a, b) -> a));

        List<StrategyTarget> targets = strategyService.fetchLatestStrategy(pan);
        double totalValue = holdings.stream().mapToDouble(h -> h.getCurrentValue() != null ? h.getCurrentValue().doubleValue() : 0.0).sum();

        List<SipLineItem> plan = new ArrayList<>();
        for (StrategyTarget target : targets) {
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
            double currentVal = (h != null && h.getCurrentValue() != null) ? h.getCurrentValue().doubleValue() : 0.0;

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

    public List<RebalancingTrade> computeRebalancingTrades(String pan) {
        return computeRebalancingTradesFromSignals(pan, evaluateAll(pan));
    }

    private BigDecimal parseSignalAmount(String amount) {
        if (amount == null) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(amount.trim().replace(",", ""));
        } catch (Exception e) {
            log.warn("Failed to parse signal amount: {}", amount);
            return BigDecimal.ZERO;
        }
    }

    public UnifiedTacticalPayload generateUnifiedPayload(String pan, double monthlySip, double lumpsum) {
        log.info("🎯 Generating Unified Tactical Payload for PAN: {}", pan);
        List<TacticalSignal> all = evaluateAll(pan);
        
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

        List<SipLineItem> sip = computeSipPlan(pan, monthlySip); 

        List<RebalancingTrade> rebalancingTrades = computeRebalancingTradesFromSignals(pan, all);

        List<TlhOpportunity> harvest = java.util.Collections.emptyList();
        try {
            harvest = taxLossHarvestingService.scanForOpportunities(pan);
        } catch (Exception e) {
            log.warn("TLH scan failed: {}", e.getMessage());
        }

        BigDecimal totalHarvest = BigDecimal.valueOf(harvest.stream().mapToDouble(TlhOpportunity::estimatedTaxSaving).sum());

        BigDecimal totalExitValue = exitQueue.stream()
            .map(s -> parseSignalAmount(s.amount()))
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        return UnifiedTacticalPayload.builder()
            .sipPlan(sip)
            .opportunisticSignals(opportunistic)
            .activeSellSignals(activeSells)
            .exitQueue(exitQueue)
            .allSignals(all)
            .harvestOpportunities(harvest)
            .rebalancingTrades(rebalancingTrades)
            .totalExitValue(totalExitValue)
            .totalHarvestValue(totalHarvest)
            .droppedFundsCount(exitQueue.size())
            .build();
    }

    public List<RebalancingTrade> computeRebalancingTradesFromSignals(String pan, List<TacticalSignal> allSignals) {
        List<TacticalSignal> sells = allSignals.stream()
            .filter(s -> s.action() == SignalType.SELL && s.fundStatus() != FundStatus.DROPPED)
            .collect(Collectors.toList());
            
        List<TacticalSignal> buys  = allSignals.stream()
            .filter(s -> s.action() == SignalType.BUY)
            .sorted(Comparator.comparingDouble(
                s -> -(s.convictionScore() * Math.abs(s.returnZScore()))))
            .collect(Collectors.toCollection(ArrayList::new));

        List<RebalancingTrade> trades = new ArrayList<>();

        Double slab = null;
        try {
            slab = metricsRepo.getJdbcTemplate().queryForObject(
                "SELECT tax_slab FROM investor WHERE pan = ?", Double.class, pan);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            log.info("ℹ️ No investor found for PAN: {}, defaulting tax slab to 0.30", pan);
        }
        double slabRate = (slab != null) ? slab : 0.30;

        for (TacticalSignal sell : sells) {
            double sellAmt = parseSignalAmount(sell.amount()).doubleValue();
            
            List<TaxLot> fundLots = taxLotRepository.findByStatusAndSchemeAmfiCodeAndSchemeFolioInvestorPan(
                "OPEN", sell.amfiCode(), pan);
            
            var details = amfiService.getLatestSchemeDetails(sell.amfiCode());
            String category = details.getCategory();
            double currentNav = details.getNav().doubleValue();
            
            var taxRes = taxSimulator.simulateHifoExit(fundLots, category, slabRate, currentNav);
            
            double totalValue = fundLots.stream().mapToDouble(l -> l.getRemainingUnits().doubleValue() * currentNav).sum();
            
            double sellRatio = (totalValue > 0) ? Math.min(1.0, sellAmt / totalValue) : 1.0;
            double taxCost  = taxRes.estimatedTax() * sellRatio; 
            double proceeds = sellAmt - taxCost;

            if (!buys.isEmpty()) {
                TacticalSignal bestBuy = buys.get(0);
                buys.remove(0); 
                double buyAmt = Math.min(parseSignalAmount(bestBuy.amount()).doubleValue(), proceeds);
                double convDelta = bestBuy.convictionScore() - sell.convictionScore();
                double zDelta    = Math.abs(bestBuy.returnZScore()) - Math.abs(sell.returnZScore());

                trades.add(new RebalancingTrade(
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

    public List<RebalanceActionDTO> generateRebalanceActions(String pan) {
        RebalanceEngine.RebalanceRequest rebalanceReq = buildRebalanceRequest(pan);
        return rebalanceEngine.generateSignals(rebalanceReq);
    }

    private List<TacticalSignal> evaluateAll(String pan) {
        RebalanceEngine.RebalanceRequest rebalanceReq = buildRebalanceRequest(pan);
        return rebalanceEngine.computeSignals(rebalanceReq);
    }

    private RebalanceEngine.RebalanceRequest buildRebalanceRequest(String pan) {
        List<TaxLot> openLots = taxLotRepository.findByStatusAndSchemeFolioInvestorPan("OPEN", pan);
        List<AggregatedHolding> holdings = aggregationService.aggregate(openLots);
        Map<String, MarketMetrics> metricsMap = metricsRepo.fetchLiveMetricsMap(pan);
        List<StrategyTarget> targets = strategyService.fetchLatestStrategy(pan);
        BigDecimal totalValue = holdings.stream()
            .map(h -> h.getCurrentValue() != null ? h.getCurrentValue() : BigDecimal.ZERO)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

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

        Map<String, String> isinToAmfiMap = holdings.stream()
            .collect(Collectors.toMap(
                h -> h.getIsin() != null ? h.getIsin() : "",
                h -> {
                    if (h.getIsin() == null || h.getIsin().isEmpty()) return "";
                    return schemeRepository.findByIsin(h.getIsin())
                        .map(s -> CommonUtils.SANITIZE_AMFI.apply(s.getAmfiCode()))
                        .orElse("");
                },
                (a, b) -> a
            ));

        Map<String, MarketMetrics> mutableMetricsMap = new HashMap<>(metricsMap);
        List<String> missingAmfiCodes = new ArrayList<>();

        for (AggregatedHolding h : holdings) {
            String amfi = isinToAmfiMap.get(h.getIsin() != null ? h.getIsin() : "");
            if (amfi != null && !amfi.isEmpty()) {
                if (!mutableMetricsMap.containsKey(amfi)) {
                    mutableMetricsMap.put(amfi, MarketMetrics.defaultInstance());
                    missingAmfiCodes.add(amfi);
                }
            }
        }

        for (StrategyTarget target : targets) {
            String isin = target.isin();
            String amfi = schemeRepository.findByIsin(isin)
                .map(s -> CommonUtils.SANITIZE_AMFI.apply(s.getAmfiCode()))
                .orElse("");
            if (!amfi.isEmpty()) {
                if (!mutableMetricsMap.containsKey(amfi)) {
                    mutableMetricsMap.put(amfi, MarketMetrics.defaultInstance());
                    missingAmfiCodes.add(amfi);
                }
            }
        }

        if (!missingAmfiCodes.isEmpty()) {
            log.warn("⚠️ MarketMetrics not found in database for amfiCodes: {}. Fell back to default MarketMetrics.", 
                missingAmfiCodes.stream().distinct().collect(Collectors.joining(", ")));
        }

        String tailRisk = "LOW";

        return RebalanceEngine.RebalanceRequest.builder()
            .pan(pan)
            .totalPortfolioValue(totalValue)
            .fyLtcgAlreadyRealized(fyLtcgRealized)
            .tailRiskLevel(tailRisk)
            .holdings(holdings)
            .targets(targets)
            .metrics(mutableMetricsMap)
            .amfiMap(isinToAmfiMap)
            .build();
    }
}
