package com.oreki.cas_injector.dashboard.service;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.math.BigDecimal;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.oreki.cas_injector.dashboard.dto.DashboardSummaryDTO;
import com.oreki.cas_injector.dashboard.dto.UnifiedTacticalPayload;
import com.oreki.cas_injector.rebalancing.dto.SipLineItem;
import com.oreki.cas_injector.rebalancing.dto.TacticalSignal;
import com.oreki.cas_injector.rebalancing.service.PortfolioOrchestrator;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
public class PortfolioFullService {

    private final DashboardService dashboardService;
    private final PortfolioOrchestrator orchestrator;
    private final JdbcTemplate jdbcTemplate;

    public DashboardSummaryDTO getFullPortfolio(String pan, double monthlySip, double lumpsum) {
        log.info("📊 Fetching Unified Portfolio Payload for {} (SIP: {}, Lumpsum: {})", pan, monthlySip, lumpsum);
        
        DashboardSummaryDTO summary = dashboardService.getInvestorSummary(pan);
        
        // 🚀 NEW: Compute the Three Rebalance Modes + Active Sell Gate (Gate B)
        List<SipLineItem> sipPlan = orchestrator.computeSipPlan(pan, monthlySip);
        List<TacticalSignal> opportunistic = orchestrator.computeOpportunisticSignals(pan, lumpsum);
        List<TacticalSignal> activeSells = orchestrator.computeActiveSellSignals(pan);
        List<TacticalSignal> exitQueue = orchestrator.computeExitQueue(pan);

        double totalExitValue = exitQueue.stream()
            .mapToDouble(s -> {
                try {
                    return s.amount() != null ? Double.parseDouble(s.amount().replace(",", "")) : 0.0;
                } catch (Exception e) {
                    return 0.0;
                }
            })
            .sum();

        UnifiedTacticalPayload tacticalPayload = UnifiedTacticalPayload.builder()
            .sipPlan(sipPlan)
            .opportunisticSignals(opportunistic)
            .activeSellSignals(activeSells)
            .exitQueue(exitQueue)
            .totalExitValue(totalExitValue)
            .droppedFundsCount(exitQueue.size())
            .build();

        summary.setTacticalPayload(tacticalPayload);

        // 3. Get Conviction Metrics (Sortino, Score, MDD, NAV Signals)
        String metricsSql = "SELECT amfi_code, conviction_score, sortino_ratio, max_drawdown, " +
                           "cvar_5, nav_percentile_3yr, drawdown_from_ath, return_z_score, " +
                           "yield_score, risk_score, value_score, pain_score, friction_score " +
                           "FROM fund_conviction_metrics " +
                           "WHERE calculation_date = (SELECT MAX(calculation_date) FROM fund_conviction_metrics)";
        
        Map<String, Map<String, Object>> metrics = jdbcTemplate.queryForList(metricsSql).stream()
            .collect(Collectors.toMap(m -> (String) m.get("amfi_code"), m -> m, (m1, m2) -> m1));

        // Build a lookup map from SIP plan: isin -> SipLineItem
        Map<String, SipLineItem> sipByIsin = sipPlan.stream()
            .collect(Collectors.toMap(SipLineItem::isin, s -> s, (a, b) -> a));

        // Also build a map from opportunistic + exit signals by amfiCode
        Map<String, TacticalSignal> signalByAmfi = new java.util.HashMap<>();
        opportunistic.forEach(s -> signalByAmfi.put(s.amfiCode(), s));
        exitQueue.forEach(s -> signalByAmfi.put(s.amfiCode(), s));

        // Compute total portfolio value for percentage calculation
        double totalPortfolioValue = summary.getSchemeBreakdown().stream()
            .mapToDouble(s -> s.getCurrentValue() != null ? s.getCurrentValue().doubleValue() : 0.0)
            .sum();

        // 4. Merge Metrics and Signals into SchemePerformanceDTO
        summary.getSchemeBreakdown().forEach(scheme -> {
            String code = scheme.getAmfiCode();
            String isin = scheme.getIsin();
            Map<String, Object> fundMetrics = metrics.get(code);

            if (fundMetrics != null) {
                scheme.setConvictionScore(getSafeInt(fundMetrics.get("conviction_score")));
                scheme.setSortinoRatio(getSafeDouble(fundMetrics.get("sortino_ratio")));
                scheme.setMaxDrawdown(getSafeDouble(fundMetrics.get("max_drawdown")));
                scheme.setCvar5(getSafeDouble(fundMetrics.get("cvar_5")));
                scheme.setNavPercentile3yr(getSafeDouble(fundMetrics.get("nav_percentile_3yr")));
                scheme.setDrawdownFromAth(getSafeDouble(fundMetrics.get("drawdown_from_ath")));
                scheme.setReturnZScore(getSafeDouble(fundMetrics.get("return_z_score")));
                
                // Set sub-scores
                scheme.setYieldScore(getSafeDouble(fundMetrics.get("yield_score")));
                scheme.setRiskScore(getSafeDouble(fundMetrics.get("risk_score")));
                scheme.setValueScore(getSafeDouble(fundMetrics.get("value_score")));
                scheme.setPainScore(getSafeDouble(fundMetrics.get("pain_score")));
                scheme.setFrictionScore(getSafeDouble(fundMetrics.get("friction_score")));
            }

            // 1. Set actual allocation percentage
            double actualPct = totalPortfolioValue > 0 
                ? (scheme.getCurrentValue() != null ? scheme.getCurrentValue().doubleValue() : 0.0) 
                  / totalPortfolioValue * 100.0
                : 0.0;
            scheme.setAllocationPercentage(actualPct);

            // 2. Set planned percentage and action from SIP plan (primary source)
            SipLineItem sipItem = sipByIsin.get(isin);
            if (sipItem != null) {
                scheme.setPlannedPercentage(sipItem.targetPortfolioPct());
                scheme.setAction("HOLD");
                scheme.setSignalType(sipItem.mode());
                scheme.setJustifications(List.of(sipItem.note()));
            }

            // 3. Override with tactical signal if present
            TacticalSignal signal = signalByAmfi.get(code);
            if (signal != null) {
                scheme.setAction(signal.action().name());
                scheme.setSignalAmount(new BigDecimal(signal.amount().replace(",", "")));
                scheme.setJustifications(signal.justifications());
                scheme.setLastBuyDate(signal.lastBuyDate());
                scheme.setSignalType(signal.fundStatus()); // mode/status
            }
        });

        return summary;
    }

    private double getSafeDouble(Object obj) {
        if (obj == null) return 0.0;
        return ((Number) obj).doubleValue();
    }

    private int getSafeInt(Object obj) {
        if (obj == null) return 0;
        return ((Number) obj).intValue();
    }
}
