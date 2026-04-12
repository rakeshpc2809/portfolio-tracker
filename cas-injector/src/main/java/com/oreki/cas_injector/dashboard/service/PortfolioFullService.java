package com.oreki.cas_injector.dashboard.service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.stream.Collectors;
import java.math.BigDecimal;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.oreki.cas_injector.dashboard.dto.DashboardSummaryDTO;
import com.oreki.cas_injector.dashboard.dto.UnifiedTacticalPayload;
import com.oreki.cas_injector.dashboard.dto.PortfolioPerformanceDTO;
import com.oreki.cas_injector.dashboard.dto.SnapshotPoint;
import com.oreki.cas_injector.dashboard.dto.BenchmarkPoint;
import com.oreki.cas_injector.dashboard.dto.PeriodReturns;
import com.oreki.cas_injector.rebalancing.dto.SipLineItem;
import com.oreki.cas_injector.rebalancing.dto.TacticalSignal;
import com.oreki.cas_injector.rebalancing.service.PortfolioOrchestrator;
import com.oreki.cas_injector.taxmanagement.dto.TlhOpportunity;
import com.oreki.cas_injector.taxmanagement.service.TaxLossHarvestingService;
import com.oreki.cas_injector.transactions.model.TaxLot;
import com.oreki.cas_injector.transactions.repository.TaxLotRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
public class PortfolioFullService {

    private final DashboardService dashboardService;
    private final PortfolioOrchestrator orchestrator;
    private final JdbcTemplate jdbcTemplate;
    private final TaxLossHarvestingService taxLossHarvestingService;
    private final TaxLotRepository taxLotRepository;
    private final BenchmarkService benchmarkService;

    public PortfolioPerformanceDTO getPerformanceHistory(String pan) {
        // 1. Fetch portfolio_snapshot rows ordered by date ASC
        List<Map<String, Object>> snapshots = jdbcTemplate.queryForList("""
            SELECT snapshot_date, total_value, total_invested
            FROM portfolio_snapshot
            WHERE pan = ?
            ORDER BY snapshot_date ASC
            """, pan);

        List<SnapshotPoint> history = snapshots.stream().map(r -> new SnapshotPoint(
            r.get("snapshot_date").toString(),
            ((Number) r.get("total_value")).doubleValue(),
            ((Number) r.get("total_invested")).doubleValue()
        )).toList();

        // 2. Fetch Nifty 50 history for the same date range, normalize to 100 at first snapshot date
        if (!history.isEmpty()) {
            String fromDate = history.get(0).date();
            List<Map<String, Object>> niftyRows = jdbcTemplate.queryForList("""
                SELECT date, closing_price
                FROM index_fundamentals
                WHERE index_name = 'NIFTY 50'
                AND date >= ?::date
                ORDER BY date ASC
                """, fromDate);

            double firstClose = niftyRows.isEmpty() ? 1.0 :
                ((Number) niftyRows.get(0).get("closing_price")).doubleValue();
            
            List<BenchmarkPoint> niftyHistory = niftyRows.stream().map(r ->
                new BenchmarkPoint(
                    r.get("date").toString(),
                    ((Number) r.get("closing_price")).doubleValue() / firstClose * 100
                )
            ).toList();

            // 3. Compute period returns from snapshots
            PeriodReturns periods = computePeriodReturns(history);
            
            // 4. Compute alpha
            DashboardSummaryDTO summary = dashboardService.getInvestorSummary(pan);
            double xirr = 0;
            try {
                xirr = Double.parseDouble(summary.getOverallXirr().replace("%", ""));
            } catch (Exception e) {
                log.warn("Failed to parse XIRR for alpha calculation: {}", summary.getOverallXirr());
            }
            double benchmarkXirr = benchmarkService.getBenchmarkReturn("", "", "NIFTY 50");
            double alpha = xirr - benchmarkXirr;

            // 5. Total gain breakdown
            double currentValue = history.get(history.size()-1).value();
            double totalInvested = history.get(history.size()-1).invested();
            double totalGain = currentValue - totalInvested;

            return new PortfolioPerformanceDTO(
                history, niftyHistory, 
                totalInvested > 0 ? (totalGain / totalInvested) * 100 : 0,
                xirr, periods, alpha, totalGain,
                totalInvested, // SIP contribution proxy
                totalGain // market gain
            );
        }
        
        return new PortfolioPerformanceDTO(List.of(), List.of(), 0, 0, 
            new PeriodReturns(0,0,0,0,0,0), 0, 0, 0, 0);
    }

    private PeriodReturns computePeriodReturns(List<SnapshotPoint> history) {
        double latest = history.isEmpty() ? 0 : history.get(history.size()-1).value();
        return new PeriodReturns(
            findReturn(history, latest, 30),
            findReturn(history, latest, 90),
            findReturn(history, latest, 180),
            findReturn(history, latest, 365),
            findReturn(history, latest, 1095),
            history.size() > 1 ? (latest / history.get(0).value() - 1) * 100 : 0
        );
    }

    private double findReturn(List<SnapshotPoint> h, double latest, int daysBack) {
        LocalDate target = LocalDate.now().minusDays(daysBack);
        return h.stream()
            .filter(p -> !LocalDate.parse(p.date()).isAfter(target))
            .reduce((a, b) -> b)
            .map(p -> p.value() > 0 ? (latest / p.value() - 1) * 100 : 0.0)
            .orElse(0.0);
    }

    /**
     * Entry point from Controller. NOT cached.
     * Combines heavy cached base data with lightweight tactical payload.
     */
    public DashboardSummaryDTO getFullPortfolioWithTactical(String pan, double monthlySip, double lumpsum) {
        log.info("🎯 Building Full Portfolio Dashboard for PAN: {}", pan);
        // Hits cache for heavy stuff
        DashboardSummaryDTO summary = getBasePortfolioCached(pan);
        log.info("📦 Base portfolio loaded for {}. Schemes found: {}", pan, summary.getSchemeBreakdown().size());
        
        // Lightweight - NOT cached (depends on SIP sliders)
        List<SipLineItem> sipPlan = orchestrator.computeSipPlan(pan, monthlySip);
        List<TacticalSignal> opportunistic = orchestrator.computeOpportunisticSignals(pan, lumpsum);
        List<TacticalSignal> activeSells = orchestrator.computeActiveSellSignals(pan);
        List<TacticalSignal> exitQueue = orchestrator.computeExitQueue(pan);

        List<TlhOpportunity> harvestOps = Collections.emptyList();
        try {
            harvestOps = taxLossHarvestingService.scanForOpportunities(pan);
        } catch (Exception e) {
            log.warn("TLH scan failed, continuing without harvest opportunities: {}", e.getMessage());
        }
        
        double totalHarvestValue = harvestOps.stream()
            .mapToDouble(TlhOpportunity::estimatedTaxSaving)
            .sum();

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
            .harvestOpportunities(harvestOps)
            .totalHarvestValue(totalHarvestValue)
            .totalExitValue(totalExitValue)
            .droppedFundsCount(exitQueue.size())
            .build();

        summary.setTacticalPayload(tacticalPayload);

        // Build a lookup map from SIP plan: isin -> SipLineItem
        Map<String, SipLineItem> sipByIsin = sipPlan.stream()
            .collect(Collectors.toMap(SipLineItem::isin, s -> s, (a, b) -> a));

        // Map tactical signals onto the breakdown for UI consistency
        Map<String, TacticalSignal> signalByAmfi = new HashMap<>();
        opportunistic.forEach(s -> signalByAmfi.put(s.amfiCode(), s));
        exitQueue.forEach(s -> signalByAmfi.put(s.amfiCode(), s));
        activeSells.forEach(s -> signalByAmfi.put(s.amfiCode(), s));

        summary.getSchemeBreakdown().forEach(scheme -> {
            String isin = scheme.getIsin();
            
            // 1. Apply SIP plan defaults
            SipLineItem sipItem = sipByIsin.get(isin);
            if (sipItem != null) {
                scheme.setPlannedPercentage(sipItem.targetPortfolioPct());
                scheme.setAction("HOLD");
                scheme.setSignalType(sipItem.mode());
                scheme.setJustifications(List.of(sipItem.note()));
            } else {
                scheme.setAction("HOLD");
            }

            // 2. Override with tactical signals if present
            TacticalSignal sig = signalByAmfi.get(scheme.getAmfiCode());
            if (sig != null) {
                scheme.setAction(sig.action().name());
                scheme.setSignalAmount(new BigDecimal(sig.amount().replace(",", "")));
                scheme.setJustifications(sig.justifications());
                scheme.setLastBuyDate(sig.lastBuyDate());
                scheme.setSignalType(sig.fundStatus());
            }
        });

        return summary;
    }

    /**
     * CACHED - keyed by PAN only. 
     * Heavy lifting: total aggregation, XIRR calculation, metrics enrichment.
     */
    @Cacheable(value = "portfolioCache", key = "#pan")
    public DashboardSummaryDTO getBasePortfolioCached(String pan) {
        log.info("🧮 Calculating Base Portfolio for {} (Cache Miss)", pan);
        DashboardSummaryDTO summary = dashboardService.getInvestorSummary(pan);
        log.info("📊 Raw summary for {} has {} schemes", pan, summary.getSchemeBreakdown().size());
        enrichWithMetricsAndTax(summary, pan);
        return summary;
    }

    private void enrichWithMetricsAndTax(DashboardSummaryDTO summary, String pan) {
        // 1. Fetch Metrics
        String metricsSql = "SELECT amfi_code, conviction_score, sortino_ratio, max_drawdown, " +
                           "cvar_5, nav_percentile_3yr, drawdown_from_ath, return_z_score, " +
                           "yield_score, risk_score, value_score, pain_score, friction_score " +
                           "FROM fund_conviction_metrics " +
                           "WHERE calculation_date = (SELECT MAX(calculation_date) FROM fund_conviction_metrics)";
        
        Map<String, Map<String, Object>> metrics = jdbcTemplate.queryForList(metricsSql).stream()
            .collect(Collectors.toMap(m -> (String) m.get("amfi_code"), m -> m, (m1, m2) -> m1));

        // 2. Fetch Open Lots for Tax countdown
        List<TaxLot> allLots = taxLotRepository.findByStatusAndSchemeFolioInvestorPan("OPEN", pan);
        Map<String, LocalDate> oldestStcgByIsin = allLots.stream()
            .filter(l -> l.getBuyDate().isAfter(LocalDate.now().minusYears(1)))
            .collect(Collectors.groupingBy(
                l -> l.getScheme().getIsin(),
                Collectors.mapping(TaxLot::getBuyDate, Collectors.minBy(LocalDate::compareTo))
            )).entrySet().stream()
            .filter(e -> e.getValue().isPresent())
            .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().get()));

        double totalValue = summary.getSchemeBreakdown().stream()
            .mapToDouble(s -> s.getCurrentValue() != null ? s.getCurrentValue().doubleValue() : 0.0)
            .sum();

        summary.getSchemeBreakdown().forEach(scheme -> {
            String code = sanitizeAmfi(scheme.getAmfiCode());
            Map<String, Object> fundMetrics = metrics.get(code);

            if (fundMetrics != null) {
                scheme.setConvictionScore(getSafeInt(fundMetrics.get("conviction_score")));
                scheme.setSortinoRatio(getSafeDouble(fundMetrics.get("sortino_ratio")));
                scheme.setMaxDrawdown(getSafeDouble(fundMetrics.get("max_drawdown")));
                scheme.setCvar5(getSafeDouble(fundMetrics.get("cvar_5")));
                scheme.setNavPercentile3yr(getSafeDouble(fundMetrics.get("nav_percentile_3yr")));
                scheme.setDrawdownFromAth(getSafeDouble(fundMetrics.get("drawdown_from_ath")));
                scheme.setReturnZScore(getSafeDouble(fundMetrics.get("return_z_score")));
                
                scheme.setYieldScore(getSafeDouble(fundMetrics.get("yield_score")));
                scheme.setRiskScore(getSafeDouble(fundMetrics.get("risk_score")));
                scheme.setValueScore(getSafeDouble(fundMetrics.get("value_score")));
                scheme.setPainScore(getSafeDouble(fundMetrics.get("pain_score")));
                scheme.setFrictionScore(getSafeDouble(fundMetrics.get("friction_score")));
            }

            // Allocation %
            double actualPct = totalValue > 0 
                ? (scheme.getCurrentValue() != null ? scheme.getCurrentValue().doubleValue() : 0.0) 
                  / totalValue * 100.0
                : 0.0;
            scheme.setAllocationPercentage(actualPct);

            // Tax countdown
            String isin = scheme.getIsin();
            LocalDate oldestStcg = oldestStcgByIsin.get(isin);
            if (oldestStcg != null) {
                long days = ChronoUnit.DAYS.between(LocalDate.now(), oldestStcg.plusYears(1));
                scheme.setDaysToNextLtcg((int) Math.max(0, days));
            } else {
                scheme.setDaysToNextLtcg(0);
            }
            
            // Compatibility aliases
            scheme.setStcgValue(scheme.getStcgUnrealizedGain());
            scheme.setLtcgValue(scheme.getLtcgUnrealizedGain());
        });
    }

    private double getSafeDouble(Object obj) {
        if (obj == null) return 0.0;
        return ((Number) obj).doubleValue();
    }

    private int getSafeInt(Object obj) {
        if (obj == null) return 0;
        return ((Number) obj).intValue();
    }

    private String sanitizeAmfi(String amfi) {
        if (amfi == null) return "";
        String s = amfi.trim();
        // Remove leading zeros for consistent matching (MFAPI vs AMFI)
        return s.replaceFirst("^0+(?!$)", "");
    }
}
