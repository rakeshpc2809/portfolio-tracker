package com.oreki.cas_injector.dashboard.service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
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
import com.oreki.cas_injector.dashboard.dto.DroppedFundSummary;
import com.oreki.cas_injector.rebalancing.dto.SipLineItem;
import com.oreki.cas_injector.rebalancing.dto.TacticalSignal;
import com.oreki.cas_injector.rebalancing.dto.RebalancingTrade;
import com.oreki.cas_injector.rebalancing.dto.StrategyTarget;
import com.oreki.cas_injector.rebalancing.service.PortfolioOrchestrator;
import com.oreki.cas_injector.rebalancing.service.HierarchicalRiskParityService;
import com.oreki.cas_injector.taxmanagement.dto.TlhOpportunity;
import com.oreki.cas_injector.taxmanagement.service.TaxLossHarvestingService;
import com.oreki.cas_injector.transactions.model.TaxLot;
import com.oreki.cas_injector.transactions.repository.TaxLotRepository;
import com.oreki.cas_injector.core.utils.CommonUtils;

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
    private final HierarchicalRiskParityService hrpService;

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
            PeriodReturns niftyReturns = benchmarkService.getBenchmarkReturnsForAllPeriods("NIFTY 50");

            // 4. Compute alpha
            DashboardSummaryDTO summary = dashboardService.getInvestorSummary(pan);
            double xirr = 0;
            try {
                xirr = Double.parseDouble(summary.getOverallXirr().replace("%", ""));
            } catch (Exception e) {
                log.warn("Failed to parse XIRR for alpha calculation: {}", summary.getOverallXirr());
            }
            Double benchX = benchmarkService.getBenchmarkReturn("", "", "NIFTY 50");
            double benchmarkXirr = benchX != null ? benchX : 0.0;
            double alpha = xirr - benchmarkXirr;

            // 5. Total gain breakdown
            double currentValue = history.get(history.size()-1).value();
            double totalInvested = history.get(history.size()-1).invested();
            double totalGain = currentValue - totalInvested;

            return new PortfolioPerformanceDTO(
                history, niftyHistory,
                totalInvested > 0 ? (totalGain / totalInvested) * 100 : 0,
                xirr, periods, niftyReturns, alpha, totalGain,
                totalInvested, // SIP contribution proxy
                totalGain // market gain
            );
            }

            return new PortfolioPerformanceDTO(List.of(), List.of(), 0, 0,
            new PeriodReturns(0,0,0,0,0,0), new PeriodReturns(0,0,0,0,0,0), 0, 0, 0, 0);

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
        com.oreki.cas_injector.dashboard.dto.UnifiedTacticalPayload tactical = orchestrator.generateUnifiedPayload(pan, monthlySip, lumpsum);

        List<DroppedFundSummary> droppedFundSummaries = computeDroppedFundSummaries(pan);

        summary.setTacticalPayload(tactical.toBuilder()
            .droppedFundSummaries(droppedFundSummaries)
            .build());

        // Build a lookup map from SIP plan: isin -> SipLineItem
        Map<String, SipLineItem> sipByIsin = tactical.getSipPlan().stream()
            .collect(Collectors.toMap(SipLineItem::isin, s -> s, (a, b) -> a));

        // Map tactical signals onto the breakdown for UI consistency
        Map<String, TacticalSignal> signalByAmfi = new HashMap<>();
        if (tactical.getAllSignals() != null) {
            tactical.getAllSignals().forEach(s -> signalByAmfi.put(s.amfiCode(), s));
        }

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
                scheme.setSignalType(sig.fundStatus().name());
                
                // NEW: Map conviction metrics
                scheme.setConvictionScore(sig.convictionScore());
                scheme.setWinRate(sig.winRate());
                scheme.setCvar5(sig.cvar5());
                scheme.setYieldScore(sig.yieldScore());
                scheme.setRiskScore(sig.riskScore());
                scheme.setValueScore(sig.valueScore());
                scheme.setPainScore(sig.painScore());
                scheme.setRegimeScore(sig.regimeScore());
                scheme.setFrictionScore(sig.frictionScore());
                scheme.setExpenseScore(sig.expenseScore());
            }
        });

        return summary;
    }

    private List<DroppedFundSummary> computeDroppedFundSummaries(String pan) {
        DashboardSummaryDTO summary = getBasePortfolioCached(pan);
        List<DroppedFundSummary> result = new ArrayList<>();
        
        Double slab = jdbcTemplate.queryForObject("SELECT tax_slab FROM investor WHERE pan = ?", Double.class, pan);
        double slabRate = (slab != null) ? slab : 0.30;

        // Get strategy to identify dropped funds and their exit philosophy
        List<StrategyTarget> targets = orchestrator.getStrategyService().fetchLatestStrategy();
        Map<String, String> isinToStatus = targets.stream()
            .collect(Collectors.toMap(StrategyTarget::isin, StrategyTarget::status, (a, b) -> a));
        
        Set<String> activeIsins = targets.stream()
            .filter(t -> !"DROPPED".equalsIgnoreCase(t.status()) && !"EXIT".equalsIgnoreCase(t.status()))
            .map(StrategyTarget::isin)
            .collect(Collectors.toSet());

        summary.getSchemeBreakdown().stream()
            .filter(s -> !activeIsins.contains(s.getIsin()) && s.getCurrentValue().doubleValue() > 0)
            .forEach(s -> {
                String philStatus = isinToStatus.getOrDefault(s.getIsin(), "DROPPED").toUpperCase();
                double ltcg = s.getLtcgUnrealizedGain();
                double stcg = s.getStcgUnrealizedGain();
                double slabGain = s.getSlabRateGain();
                int days = s.getDaysToNextLtcg();
                double currentValue = s.getCurrentValue().doubleValue();
                
                double vt = s.getVolatilityTax() > 0 ? s.getVolatilityTax() : 0.05; 
                
                double taxIfExitNow;
                String recommendedAction;
                double taxIfWaitForLtcg;

                if (s.isSlabRateFund()) {
                    taxIfExitNow = (ltcg + stcg + slabGain) * slabRate;
                    recommendedAction = (ltcg + stcg + slabGain < 1000) ? "EXIT_NOW_TAX_FREE" : "EXIT_NOW_SLAB_RATE";
                    taxIfWaitForLtcg = taxIfExitNow; // No LTCG benefit for slab funds
                } else {
                    taxIfExitNow = (ltcg > 125000 ? (ltcg - 125000) * 0.125 : 0) + (stcg * 0.20);
                    double taxSavingIfWait = stcg * (0.20 - 0.125);
                    double driftCostOfWaiting = currentValue * vt * (days / 252.0);
                    taxIfWaitForLtcg = (ltcg + stcg > 125000 ? (ltcg + stcg - 125000) * 0.125 : 0);
                    
                    if ("EXIT".equals(philStatus)) {
                        recommendedAction = (ltcg + stcg < 1000) ? "EXIT_NOW_TAX_FREE" : "EXIT_NOW";
                    } else {
                        recommendedAction = "EXIT_NOW";
                        if (ltcg + stcg < 1000) recommendedAction = "EXIT_NOW_TAX_FREE";
                        else if (taxSavingIfWait > driftCostOfWaiting && days > 0) recommendedAction = "WAIT_FOR_LTCG";
                    }
                }
                
                if (s.getHurstExponent() > 0.62 && s.getReturnZScore() < 0 && s.getAllocationPercentage() < 5.0) {
                    recommendedAction = "HOLD_WAVE_RIDER";
                }

                result.add(new DroppedFundSummary(
                    s.getSchemeName(),
                    s.getAmfiCode(),
                    currentValue,
                    ltcg,
                    stcg + slabGain,
                    days,
                    taxIfExitNow,
                    taxIfWaitForLtcg,
                    "EXIT".equals(philStatus) ? 0 : taxSavingByWaiting(stcg, days, vt, currentValue),
                    recommendedAction,
                    LocalDate.now().plusDays(days).toString(),
                    "Strategic assessment based on tax and momentum."
                ));
            });
            
        result.sort((a, b) -> Double.compare(b.taxSavingByWaiting(), a.taxSavingByWaiting()));
        return result;
    }

    private double taxSavingByWaiting(double stcg, int days, double vt, double currentValue) {
        double taxSaving = stcg * (0.20 - 0.125);
        double driftCost = currentValue * vt * (days / 252.0);
        return Math.max(0, taxSaving - driftCost);
    }

    public List<com.oreki.cas_injector.rebalancing.dto.RebalancingTrade> computeRebalancingTrades(String pan) {
        return orchestrator.computeRebalancingTrades(pan);
    }

    /**
     * CACHED - keyed by PAN only. 
     * Heavy lifting: total aggregation, XIRR calculation, metrics enrichment.
     */
    @Cacheable(value = "portfolioCache", key = "#pan")
    public DashboardSummaryDTO getBasePortfolioCached(String pan) {
        log.info("🧮 Calculating Base Portfolio for {} (Cache Miss)", pan);
        DashboardSummaryDTO summary = dashboardService.getInvestorSummary(pan);
        
        // Fetch FY Realized LTCG
        Double fyLtcg = jdbcTemplate.queryForObject("""
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
        summary.setFyLtcgAlreadyRealized(fyLtcg != null ? fyLtcg : 0.0);

        log.info("📊 Raw summary for {} has {} schemes", pan, summary.getSchemeBreakdown().size());
        enrichWithMetricsAndTax(summary, pan);
        return summary;
    }

    private void enrichWithMetricsAndTax(DashboardSummaryDTO summary, String pan) {
        // 1. Fetch Metrics
        String metricsSql = "SELECT m.amfi_code, m.conviction_score, m.sortino_ratio, m.max_drawdown, " +
                           "m.cvar_5, m.nav_percentile_1yr, m.nav_percentile_3yr, m.drawdown_from_ath, m.return_z_score, " +
                           "m.yield_score, m.risk_score, m.value_score, m.pain_score, m.friction_score, " +
                           "m.regime_score, m.expense_score, fm.expense_ratio, fm.aum_cr " +
                           "FROM fund_conviction_metrics m " +
                           "LEFT JOIN ( " +
                           "    SELECT DISTINCT ON (scheme_code) scheme_code, expense_ratio, aum_cr " +
                           "    FROM fund_metrics " +
                           "    ORDER BY scheme_code, fetch_date DESC " +
                           ") fm ON LTRIM(fm.scheme_code, '0') = LTRIM(m.amfi_code, '0') " +
                           "WHERE m.calculation_date = (SELECT MAX(calculation_date) FROM fund_conviction_metrics)";
        
        Map<String, Map<String, Object>> metrics = jdbcTemplate.queryForList(metricsSql).stream()
            .collect(Collectors.toMap(m -> CommonUtils.SANITIZE_AMFI.apply((String) m.get("amfi_code")), m -> m, (m1, m2) -> m1));

        // 1.1 Fetch Conviction History (Last 30 days)
        Map<String, List<Integer>> historyMap = new HashMap<>();
        jdbcTemplate.query("SELECT amfi_code, conviction_score FROM fund_conviction_metrics WHERE calculation_date >= CURRENT_DATE - INTERVAL '30 days' ORDER BY amfi_code, calculation_date ASC", (rs) -> {
            String amfi = CommonUtils.SANITIZE_AMFI.apply(rs.getString("amfi_code"));
            historyMap.computeIfAbsent(amfi, k -> new ArrayList<>()).add(rs.getInt("conviction_score"));
        });

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
            String code = CommonUtils.SANITIZE_AMFI.apply(scheme.getAmfiCode());
            Map<String, Object> fundMetrics = metrics.get(code);

            if (fundMetrics != null) {
                scheme.setConvictionScore(getSafeInt(fundMetrics.get("conviction_score")));
                scheme.setSortinoRatio(getSafeDouble(fundMetrics.get("sortino_ratio")));
                scheme.setMaxDrawdown(getSafeDouble(fundMetrics.get("max_drawdown")));
                scheme.setCvar5(getSafeDouble(fundMetrics.get("cvar_5")));
                scheme.setNavPercentile1yr(getSafeDouble(fundMetrics.get("nav_percentile_1yr")));
                scheme.setNavPercentile3yr(getSafeDouble(fundMetrics.get("nav_percentile_3yr")));
                scheme.setDrawdownFromAth(getSafeDouble(fundMetrics.get("drawdown_from_ath")));
                scheme.setReturnZScore(getSafeDouble(fundMetrics.get("return_z_score")));
                
                scheme.setYieldScore(getSafeDouble(fundMetrics.get("yield_score")));
                scheme.setRiskScore(getSafeDouble(fundMetrics.get("risk_score")));
                scheme.setValueScore(getSafeDouble(fundMetrics.get("value_score")));
                scheme.setPainScore(getSafeDouble(fundMetrics.get("pain_score")));
                scheme.setRegimeScore(getSafeDouble(fundMetrics.get("regime_score")));
                scheme.setFrictionScore(getSafeDouble(fundMetrics.get("friction_score")));
                scheme.setExpenseScore(getSafeDouble(fundMetrics.get("expense_score")));
                scheme.setConvictionHistory(historyMap.getOrDefault(code, List.of()));
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

    public Map<String, Object> getCorrelationMatrix(String pan) {
        DashboardSummaryDTO summary = getBasePortfolioCached(pan);
        List<String> activeAmfiCodes = summary.getSchemeBreakdown().stream()
            .filter(s -> s.getCurrentValue().compareTo(BigDecimal.ZERO) > 0)
            .map(s -> CommonUtils.SANITIZE_AMFI.apply(s.getAmfiCode()))
            .distinct()
            .toList();

        HierarchicalRiskParityService.HrpResult result = hrpService.computeHrpWeights(activeAmfiCodes);
        
        // Map AMFI to names for labels
        Map<String, String> amfiToName = summary.getSchemeBreakdown().stream()
            .collect(Collectors.toMap(
                s -> CommonUtils.SANITIZE_AMFI.apply(s.getAmfiCode()),
                s -> CommonUtils.NORMALIZE_NAME.apply(s.getSchemeName()),
                (a, b) -> a
            ));

        List<String> labels = result.sortedAmfiCodes().stream()
            .map(amfi -> amfiToName.getOrDefault(amfi, amfi))
            .toList();

        return Map.of(
            "labels", labels,
            "matrix", result.corrMatrix()
        );
    }
}
