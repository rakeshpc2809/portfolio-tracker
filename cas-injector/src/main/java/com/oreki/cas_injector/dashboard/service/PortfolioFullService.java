package com.oreki.cas_injector.dashboard.service;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.oreki.cas_injector.dashboard.dto.DashboardSummaryDTO;
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

    public DashboardSummaryDTO getFullPortfolio(String pan) {
        log.info("📊 Fetching Unified Portfolio Payload for {}", pan);
        
        // 1. Get Base Dashboard (Schemes, NAVs, Gains)
        DashboardSummaryDTO summary = dashboardService.getInvestorSummary(pan);
        
        // 2. Get Tactical Signals (Planned vs Actual Allocations)
        Map<String, TacticalSignal> signals = orchestrator.generateDailySignals(pan, 75000, 0).stream()
            .collect(Collectors.toMap(sig -> {
                return jdbcTemplate.queryForObject("SELECT amfi_code FROM scheme WHERE name = ?", String.class, sig.schemeName());
            }, s -> s, (s1, s2) -> s1));
            
        // 3. Get Conviction Metrics (Sortino, Score, MDD, NAV Signals)
        String metricsSql = "SELECT amfi_code, conviction_score, sortino_ratio, max_drawdown, " +
                           "nav_percentile_3yr, drawdown_from_ath, return_z_score " +
                           "FROM fund_conviction_metrics " +
                           "WHERE calculation_date = (SELECT MAX(calculation_date) FROM fund_conviction_metrics)";
        
        Map<String, Map<String, Object>> metrics = jdbcTemplate.queryForList(metricsSql).stream()
            .collect(Collectors.toMap(m -> (String) m.get("amfi_code"), m -> m, (m1, m2) -> m1));

        // 4. Merge everything into SchemePerformanceDTO using amfiCode as Join Key
        summary.getSchemeBreakdown().forEach(scheme -> {
            String code = scheme.getAmfiCode();
            
            // Join Signals
            TacticalSignal signal = signals.get(code);
            if (signal != null) {
                scheme.setPlannedPercentage(signal.plannedPercentage());
                scheme.setAllocationPercentage(signal.actualPercentage());
                scheme.setSignalType(signal.action());
                scheme.setAction(signal.action());
                scheme.setSignalAmount(new java.math.BigDecimal(signal.amount().replace(",", "")));
                scheme.setJustifications(signal.justifications());
                scheme.setLastBuyDate(signal.lastBuyDate());
            } else {
                scheme.setAllocationPercentage(0.0); 
                scheme.setPlannedPercentage(0.0);
                scheme.setSignalType("NOT_IN_STRATEGY");
                scheme.setAction("HOLD"); 
                scheme.setJustifications(Collections.emptyList());
                scheme.setLastBuyDate(LocalDate.of(1970, 1, 1));
            }
            
            // Join Metrics
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
