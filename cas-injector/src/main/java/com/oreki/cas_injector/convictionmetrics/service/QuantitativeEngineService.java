package com.oreki.cas_injector.convictionmetrics.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Service;

import com.oreki.cas_injector.convictionmetrics.repository.ConvictionMetricsRepository;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
public class QuantitativeEngineService {

    private final ConvictionMetricsRepository convictionMetricsRepository;
    private final BucketZScorerService bucketZScorerService;
    private final HurstExponentService hurstExponentService;
    private final OrnsteinUhlenbeckService ouService;
    private final HmmRegimeService hmmRegimeService;

    @Getter private final AtomicBoolean isRunning = new AtomicBoolean(false);
    @Getter private final AtomicInteger currentStep = new AtomicInteger(0); // 0-7
    @Getter private String lastStatusMessage = "Idle";

    /**
     * This method triggers the native PostgreSQL Window Functions
     * to calculate quantitative metrics for every fund.
     */
  public void runNightlyMathEngine() {
        if (isRunning.getAndSet(true)) {
            log.warn("Math Engine already running.");
            return;
        }

        try {
            if (convictionMetricsRepository.getHistoryCount() == 0) {
                log.warn("⚠️ Math Engine aborted: fund_history is empty. Please run 'Full History Refresh' first.");
                lastStatusMessage = "Failed: History missing. Run 'Full History Refresh' first.";
                isRunning.set(false);
                return;
            }

            log.info("🧮 Starting Advanced Quantitative Math Engine (Sortino, CVaR, MDD, NAV Signals, Hurst, Z-Score, OU, HMM)...");
            long startTime = System.currentTimeMillis();

            // 1. Run existing Sortino/CVaR/MDD block
            currentStep.set(1);
            lastStatusMessage = "Calculating main risk metrics (Sortino, CVaR)...";
            int mainMetricsRows = convictionMetricsRepository.runNightlyMathEngine();

            // 2. Run existing NAV Signals block (Percentile, ATH Drawdown, Return Z-Score)
            currentStep.set(2);
            lastStatusMessage = "Updating NAV signals (Percentile, ATH)...";
            int navSignalRows = convictionMetricsRepository.updateNavSignals();

            // 3. Run existing Bucket Z-Scorer (CQS)
            currentStep.set(3);
            lastStatusMessage = "Computing relative Bucket Z-Scores...";
            bucketZScorerService.computeBucketCqs();

            // 4. Run new Rolling Z-Score & Volatility Tax via SQL
            currentStep.set(4);
            lastStatusMessage = "Computing Rolling 252-day Z-Score & Volatility Tax...";
            int zScoreRows = convictionMetricsRepository.updateRollingZScoreAndVolatilityTax();
            log.info("📈 Rolling Z-Score updated for {} funds.", zScoreRows);

            // ─── OPTIMISATION: Load NAV series once for all Java services ───
            Map<String, double[]> returnsCache = loadAllReturns(252);
            Map<String, double[]> navsCache = loadAllNavs(252);

            // 5. Run new Hurst Exponent (Java R/S Analysis)
            currentStep.set(1);
            lastStatusMessage = "Running Hurst Exponent R/S Analysis...";
            hurstExponentService.computeAndPersistHurstMetrics(returnsCache);

            // 6. Run new OU Process Calibration
            currentStep.set(6);
            lastStatusMessage = "Calibrating Ornstein-Uhlenbeck Mean Reversion...";
            ouService.computeAndPersistOUMetrics(navsCache);

            // 7. Run HMM Regime Filter
            currentStep.set(7);
            lastStatusMessage = "Detecting HMM Market Regimes...";
            hmmRegimeService.computeAndPersistHmmStates(returnsCache);

            long endTime = System.currentTimeMillis();
            lastStatusMessage = "Complete! Updated " + mainMetricsRows + " funds.";
            log.info("✅ Math Engine Complete! Main Metrics updated for {} funds. NAV Signals updated for {} rows. Z-Score/Hurst/OU/HMM complete in {} ms.", 
                mainMetricsRows, navSignalRows, (endTime - startTime));
        } catch (Exception e) {
            lastStatusMessage = "Failed: " + e.getMessage();
            log.error("🚨 Math Engine Failed to execute native SQL or Java services.", e);
        } finally {
            isRunning.set(false);
        }
    }

    private Map<String, double[]> loadAllNavs(int days) {
        log.info("🗂️ Pre-loading NAV series for all funds ({} days)...", days);
        String sql = """
            SELECT amfi_code, nav, nav_date
            FROM fund_history
            WHERE amfi_code IN (
                SELECT amfi_code FROM fund_history 
                GROUP BY amfi_code HAVING COUNT(*) >= ?
            )
            AND nav_date >= CURRENT_DATE - INTERVAL '400 days'
            ORDER BY amfi_code, nav_date DESC
        """;

        List<Map<String, Object>> rows = convictionMetricsRepository.getJdbcTemplate().queryForList(sql, days + 1);
        Map<String, List<Double>> navMap = new HashMap<>();

        for (Map<String, Object> r : rows) {
            String amfi = (String) r.get("amfi_code");
            double nav = ((Number) r.get("nav")).doubleValue();
            navMap.computeIfAbsent(amfi, k -> new ArrayList<>()).add(nav);
        }

        Map<String, double[]> result = new HashMap<>();
        for (var entry : navMap.entrySet()) {
            List<Double> navsDesc = entry.getValue();
            if (navsDesc.size() < days) continue;

            double[] navs = new double[days];
            for (int i = 0; i < days; i++) {
                navs[days - 1 - i] = navsDesc.get(i);
            }
            result.put(entry.getKey(), navs);
        }
        return result;
    }

    private Map<String, double[]> loadAllReturns(int days) {
        log.info("🗂️ Pre-loading log returns for all funds ({} days)...", days);
        String sql = """
            SELECT amfi_code, nav, nav_date
            FROM fund_history
            WHERE amfi_code IN (
                SELECT amfi_code FROM fund_history 
                GROUP BY amfi_code HAVING COUNT(*) >= ?
            )
            AND nav_date >= CURRENT_DATE - INTERVAL '400 days'
            ORDER BY amfi_code, nav_date DESC
        """;

        List<Map<String, Object>> rows = convictionMetricsRepository.getJdbcTemplate().queryForList(sql, days + 1);
        Map<String, List<Double>> navMap = new HashMap<>();

        for (Map<String, Object> r : rows) {
            String amfi = (String) r.get("amfi_code");
            double nav = ((Number) r.get("nav")).doubleValue();
            navMap.computeIfAbsent(amfi, k -> new ArrayList<>()).add(nav);
        }

        Map<String, double[]> returnsMap = new HashMap<>();
        for (var entry : navMap.entrySet()) {
            List<Double> navsDesc = entry.getValue();
            if (navsDesc.size() < days + 1) continue;

            // Reverse to get chronological order for return calculation
            double[] returns = new double[days];
            for (int i = 0; i < days; i++) {
                // navsDesc is [latest, yesterday, ..., oldest]
                // logReturn = log(latest/yesterday)
                double today = navsDesc.get(i); 
                double prev  = navsDesc.get(i + 1);
                if (prev > 0) {
                    returns[days - 1 - i] = Math.log(today / prev);
                }
            }
            returnsMap.put(entry.getKey(), returns);
        }
        log.info("🗂️ Cache primed with returns for {} funds.", returnsMap.size());
        return returnsMap;
    }
}