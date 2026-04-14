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

    @Value("${hrp.blend.ratio:0.5}")
    private double hrpBlendRatio;

    private static final double MAX_SINGLE_FUND_CONCENTRATION = 0.30;

    public List<SipLineItem> computeSipPlan(String pan, double monthlySip) {
        log.info("🎯 Computing SIP Plan for {}: ₹{}", pan, monthlySip);
        
        List<TaxLot> openLots = taxLotRepository.findByStatusAndSchemeFolioInvestorPan("OPEN", pan);
        List<AggregatedHolding> holdings = aggregationService.aggregate(openLots);
        
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
            String isin = target.isin();
            // Try to get AMFI from held lots first
            String amfi = isinToAmfiMap.getOrDefault(isin, "");
            
            // If not held, we might need a DB lookup, but only once per target fund
            if (amfi.isEmpty()) {
                amfi = schemeRepository.findByIsin(isin)
                    .map(s -> CommonUtils.SANITIZE_AMFI.apply(s.getAmfiCode()))
                    .orElse("");
            }
            
            double targetPct = target.targetPortfolioPct();
            
            // Re-weight if HRP is active
            if (!amfi.isEmpty() && hrpWeights.containsKey(amfi)) {
                double hrpW = hrpWeights.get(amfi);
                targetPct = (targetPct * (1 - hrpBlendRatio)) + (hrpW * 100 * hrpBlendRatio);
            }

            double currentVal = holdings.stream()
                .filter(h -> h.getIsin().equals(isin))
                .mapToDouble(AggregatedHolding::getCurrentValue)
                .sum();

            // CONCENTRATION GUARD: Cap target at 30% of portfolio
            double cappedTargetPct = Math.min(targetPct, MAX_SINGLE_FUND_CONCENTRATION * 100);
            double targetValue = (cappedTargetPct / 100.0) * (totalValue + monthlySip);

            double gap = targetValue - currentVal;
            double sipAllocation = Math.max(0, Math.min(monthlySip, gap));

            // Informative metadata about last transactions
            StringBuilder infoNote = new StringBuilder();
            if (!amfi.isEmpty()) {
                txnRepo.findLatestBuyBySchemeAmfiCodeAndPan(amfi, pan)
                    .ifPresent(t -> infoNote.append(" Last buy: ").append(t.getDate()).append("."));
                txnRepo.findLatestSellBySchemeAmfiCodeAndPan(amfi, pan)
                    .ifPresent(t -> infoNote.append(" Last sell: ").append(t.getDate()).append("."));
            }

            if (sipAllocation > 100) {
                plan.add(new SipLineItem(
                    target.schemeName(),
                    CommonUtils.NORMALIZE_NAME.apply(target.schemeName()),
                    target.isin(),
                    amfi,
                    sipAllocation,
                    target.sipPct(),
                    cappedTargetPct,
                    "SIP_BUY",
                    "DEPLOY",
                    "Targeting gap of " + String.format("%.1f", gap) + (cappedTargetPct < targetPct ? " (Capped)" : "") + infoNote.toString()
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
        log.info("🔍 Computing opportunistic buy signals for PAN: {} with Lumpsum: ₹{}", pan, lumpsum);
        Map<String, MarketMetrics> metricsMap = metricsRepo.fetchLiveMetricsMap(pan);
        
        List<TacticalSignal> signals = new ArrayList<>();
        
        // 1. Identify funds with statistically significant discounts (Z-Score < -1.5)
        metricsMap.forEach((amfi, m) -> {
            if (m.rollingZScore252() < -1.5) {
                Scheme scheme = schemeRepository.findFirstByAmfiCode(amfi).orElse(null);
                if (scheme == null) return;

                String regime = m.hurstExponent() < 0.45 ? "MEAN_REVERTING" : (m.hurstExponent() > 0.55 ? "TRENDING" : "RANDOM_WALK");
                
                // If Mean Reverting and cheap, it's a strong buy
                SignalType action = (regime.equals("MEAN_REVERTING") || m.rollingZScore252() < -2.0) ? SignalType.BUY : SignalType.WATCH;
                
                List<String> justs = new ArrayList<>();
                justs.add("Statistical discount (z=" + String.format("%.2f", m.rollingZScore252()) + ") in " + regime + " regime.");
                txnRepo.findLatestBuyBySchemeAmfiCodeAndPan(amfi, pan).ifPresent(t -> justs.add("Last buy: " + t.getDate()));
                txnRepo.findLatestSellBySchemeAmfiCodeAndPan(amfi, pan).ifPresent(t -> justs.add("Last sell: " + t.getDate()));

                signals.add(TacticalSignal.builder()
                    .schemeName(scheme.getName())
                    .amfiCode(amfi)
                    .action(action)
                    .returnZScore(m.rollingZScore252())
                    .hurst20d(m.hurstExponent())
                    .multiScaleRegime(regime)
                    .convictionScore(m.convictionScore())
                    .amount(lumpsum > 0 ? String.format("%.2f", lumpsum / 3.0) : "0")
                    .justifications(justs)
                    .build());
            }
        });

        return signals.stream()
            .sorted(Comparator.comparing(TacticalSignal::returnZScore)) // Cheapest first
            .collect(Collectors.toList());
    }

    public List<TacticalSignal> computeActiveSellSignals(String pan) {
        log.info("🔍 Computing active rebalance sell signals for PAN: {}", pan);
        Map<String, MarketMetrics> metricsMap = metricsRepo.fetchLiveMetricsMap(pan);
        List<TacticalSignal> signals = new ArrayList<>();

        metricsMap.forEach((amfi, m) -> {
            // High Z-Score + Bear Regime + Hurst Trending Down
            if (m.rollingZScore252() > 2.0 && "VOLATILE_BEAR".equals(m.hmmState())) {
                Scheme scheme = schemeRepository.findFirstByAmfiCode(amfi).orElse(null);
                if (scheme == null) return;

                List<String> justs = new ArrayList<>();
                justs.add("Position overheated (z=" + String.format("%.2f", m.rollingZScore252()) + ") during " + m.hmmState() + " regime.");
                txnRepo.findLatestBuyBySchemeAmfiCodeAndPan(amfi, pan).ifPresent(t -> justs.add("Last buy: " + t.getDate()));
                txnRepo.findLatestSellBySchemeAmfiCodeAndPan(amfi, pan).ifPresent(t -> justs.add("Last sell: " + t.getDate()));

                signals.add(TacticalSignal.builder()
                    .schemeName(scheme.getName())
                    .amfiCode(amfi)
                    .action(SignalType.SELL)
                    .returnZScore(m.rollingZScore252())
                    .justifications(justs)
                    .build());
            }
        });

        return signals;
    }

    public List<TacticalSignal> computeExitQueue(String pan) {
        log.info("🔍 Computing Exit Queue for PAN: {}", pan);
        List<TaxLot> openLots = taxLotRepository.findByStatusAndSchemeFolioInvestorPan("OPEN", pan);
        List<AggregatedHolding> holdings = aggregationService.aggregate(openLots);
        List<StrategyTarget> targets = strategyService.fetchLatestStrategy();
        
        Set<String> strategyIsins = targets.stream()
            .map(StrategyTarget::isin)
            .collect(Collectors.toSet());

        // Map ISIN to AMFI Code from pre-fetched lots
        Map<String, String> isinToAmfiMap = openLots.stream()
            .collect(Collectors.toMap(
                l -> l.getScheme().getIsin(),
                l -> CommonUtils.SANITIZE_AMFI.apply(l.getScheme().getAmfiCode()),
                (a, b) -> a));

        List<TacticalSignal> exitSignals = new ArrayList<>();

        // 1. Identify funds held but NOT in strategy
        for (AggregatedHolding h : holdings) {
            if (!strategyIsins.contains(h.getIsin())) {
                exitSignals.add(TacticalSignal.builder()
                    .schemeName(h.getSchemeName())
                    .amfiCode(isinToAmfiMap.getOrDefault(h.getIsin(), ""))
                    .action(SignalType.EXIT)
                    .amount(String.format("%.2f", h.getCurrentValue()))
                    .justifications(List.of("Asset not present in current investment strategy."))
                    .build());
            }
        }

        // 2. Identify funds in strategy marked as DROPPED
        Map<String, AggregatedHolding> holdingsByIsin = holdings.stream()
            .collect(Collectors.toMap(AggregatedHolding::getIsin, h -> h));

        for (StrategyTarget target : targets) {
            if ("DROPPED".equalsIgnoreCase(target.status()) || "EXIT".equalsIgnoreCase(target.status())) {
                AggregatedHolding h = holdingsByIsin.get(target.isin());
                if (h != null) {
                    exitSignals.add(TacticalSignal.builder()
                        .schemeName(target.schemeName())
                        .amfiCode(isinToAmfiMap.getOrDefault(target.isin(), ""))
                        .action(SignalType.EXIT)
                        .amount(String.format("%.2f", h.getCurrentValue()))
                        .justifications(List.of("Strategy explicitly marked this fund for liquidation."))
                        .build());
                }
            }
        }

        return exitSignals;
    }
}
