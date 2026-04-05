package com.oreki.cas_injector.dashboard.service;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
        
        // 🚀 NEW: Compute the Three Rebalance Modes
        List<SipLineItem> sipPlan = orchestrator.computeSipPlan(pan, monthlySip);
        List<TacticalSignal> opportunistic = orchestrator.computeOpportunisticSignals(pan, lumpsum);
        List<TacticalSignal> exitQueue = orchestrator.computeExitQueue(pan);

        double totalExitValue = exitQueue.stream()
            .mapToDouble(s -> Double.parseDouble(s.amount()))
            .sum();

        UnifiedTacticalPayload tacticalPayload = UnifiedTacticalPayload.builder()
            .sipPlan(sipPlan)
            .opportunisticSignals(opportunistic)
            .exitQueue(exitQueue)
            .totalExitValue(totalExitValue)
            .droppedFundsCount(exitQueue.size())
            .build();

        summary.setTacticalPayload(tacticalPayload);

        // 3. Get Conviction Metrics (Sortino, Score, MDD, NAV Signals)
        String metricsSql = "SELECT amfi_code, conviction_score, sortino_ratio, max_drawdown, " +
                           "nav_percentile_3yr, drawdown_from_ath, return_z_score " +
                           "FROM fund_conviction_metrics " +
                           "WHERE calculation_date = (SELECT MAX(calculation_date) FROM fund_conviction_metrics)";
        
        Map<String, Map<String, Object>> metrics = jdbcTemplate.queryForList(metricsSql).stream()
            .collect(Collectors.toMap(m -> (String) m.get("amfi_code"), m -> m, (m1, m2) -> m1));

        // 4. Merge Metrics into SchemePerformanceDTO (Join Keys)
        summary.getSchemeBreakdown().forEach(scheme -> {
            String code = scheme.getAmfiCode();
            Map<String, Object> fundMetrics = metrics.get(code);
            if (fundMetrics != null) {
                scheme.setConvictionScore(getSafeInt(fundMetrics.get("conviction_score")));
                scheme.setSortinoRatio(getSafeDouble(fundMetrics.get("sortino_ratio")));
                scheme.setMaxDrawdown(getSafeDouble(fundMetrics.get("max_drawdown")));
                scheme.setNavPercentile3yr(getSafeDouble(fundMetrics.get("nav_percentile_3yr")));
                scheme.setDrawdownFromAth(getSafeDouble(fundMetrics.get("drawdown_from_ath")));
                scheme.setReturnZScore(getSafeDouble(fundMetrics.get("return_z_score")));
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
