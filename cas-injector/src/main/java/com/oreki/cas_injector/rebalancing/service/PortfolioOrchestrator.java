package com.oreki.cas_injector.rebalancing.service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    @Value("${hrp.blend.ratio:0.5}")
    private double hrpBlendRatio;

    public List<SipLineItem> computeSipPlan(String pan, double monthlySip) {
        log.info("🎯 Computing SIP Plan for {}: ₹{}", pan, monthlySip);
        
        List<TaxLot> openLots = taxLotRepository.findByStatusAndSchemeFolioInvestorPan("OPEN", pan);
        List<AggregatedHolding> holdings = aggregationService.aggregate(openLots);
        
        Map<String, MarketMetrics> metricsMap = metricsRepo.fetchLiveMetricsMap(pan);
        Map<String, String> nameToAmfiMap = holdings.stream()
            .collect(Collectors.toMap(AggregatedHolding::getSchemeName, h -> {
                Scheme s = schemeRepository.findByName(h.getSchemeName()).orElse(null);
                return (s != null) ? CommonUtils.SANITIZE_AMFI.apply(s.getAmfiCode()) : "";
            }));

        List<StrategyTarget> targets = strategyService.fetchLatestStrategy();
        double totalValue = holdings.stream().mapToDouble(AggregatedHolding::getCurrentValue).sum();

        // Check tail risk
        SystemicRiskMonitorService.TailRiskLevel risk = riskMonitor.assessTailRisk(holdings, metricsMap, nameToAmfiMap);
        
        // Compute HRP weights for held assets
        List<String> heldAmfis = nameToAmfiMap.values().stream().filter(s -> !s.isEmpty()).toList();
        Map<String, Double> hrpWeights = hrpService.computeHrpWeights(heldAmfis).weights();

        List<SipLineItem> plan = new ArrayList<>();
        for (StrategyTarget target : targets) {
            // Need AMFI code for lookup. Record doesn't have it, so we'll look it up via ISIN or name
            String isin = target.isin();
            Scheme s = schemeRepository.findByIsin(isin).orElse(null);
            String amfi = (s != null) ? CommonUtils.SANITIZE_AMFI.apply(s.getAmfiCode()) : "";
            
            double targetPct = target.targetPortfolioPct();
            
            // Re-weight if HRP is active
            if (!amfi.isEmpty() && hrpWeights.containsKey(amfi)) {
                double hrpW = hrpWeights.get(amfi);
                targetPct = (targetPct * (1 - hrpBlendRatio)) + (hrpW * 100 * hrpBlendRatio);
            }

            double targetValue = (targetPct / 100.0) * (totalValue + monthlySip);
            double currentVal = holdings.stream()
                .filter(h -> {
                    Scheme sch = schemeRepository.findByName(h.getSchemeName()).orElse(null);
                    return sch != null && sch.getIsin().equals(isin);
                })
                .mapToDouble(AggregatedHolding::getCurrentValue)
                .sum();

            double gap = targetValue - currentVal;
            double sipAllocation = Math.max(0, Math.min(monthlySip, gap));

            if (sipAllocation > 100) {
                plan.add(new SipLineItem(
                    target.schemeName(),
                    CommonUtils.NORMALIZE_NAME.apply(target.schemeName()),
                    target.isin(),
                    amfi,
                    sipAllocation,
                    target.sipPct(),
                    targetPct,
                    "SIP_BUY",
                    "DEPLOY",
                    "Targeting gap of " + String.format("%.1f", gap)
                ));
            }
        }
        return plan;
    }

    public List<TacticalSignal> generateDailySignals(String pan, double monthlySip, double lumpsum) {
        // Logic to combine all tactical signals
        List<TacticalSignal> all = new ArrayList<>();
        all.addAll(computeOpportunisticSignals(pan, lumpsum));
        all.addAll(computeActiveSellSignals(pan));
        all.addAll(computeExitQueue(pan));
        return all;
    }

    public List<TacticalSignal> computeOpportunisticSignals(String pan, double lumpsum) {
        // Implementation for opportunistic buy signals based on Z-Scores and Hurst
        return new ArrayList<>();
    }

    public List<TacticalSignal> computeActiveSellSignals(String pan) {
        // High Z-Score + Volatility Tax > 2% + Bear Regime
        return new ArrayList<>();
    }

    public List<TacticalSignal> computeExitQueue(String pan) {
        // Strategy-level removals
        return new ArrayList<>();
    }
}
