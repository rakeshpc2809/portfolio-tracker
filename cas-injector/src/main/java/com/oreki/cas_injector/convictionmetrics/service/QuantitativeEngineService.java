package com.oreki.cas_injector.convictionmetrics.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Service;

import com.oreki.cas_injector.convictionmetrics.repository.ConvictionMetricsRepository;
import com.oreki.cas_injector.core.utils.CommonUtils;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class QuantitativeEngineService {

    private final ConvictionMetricsRepository convictionMetricsRepository;

    @Getter private final AtomicBoolean isRunning = new AtomicBoolean(false);
    @Getter private final AtomicInteger currentStep = new AtomicInteger(0);
    @Getter private String lastStatusMessage = "Idle";

    public QuantitativeEngineService(ConvictionMetricsRepository convictionMetricsRepository) {
        this.convictionMetricsRepository = convictionMetricsRepository;
    }

    private void updateStatus(int step, String message) {
        this.currentStep.set(step);
        this.lastStatusMessage = message;
        log.info("MathEngine [Step {}/7]: {}", step, message);
    }

    public void runNightlyMathEngine() {
        if (isRunning.getAndSet(true)) {
            log.warn("Math Engine already running.");
            return;
        }

        try {
            if (convictionMetricsRepository.getHistoryCount() == 0) {
                log.warn("⚠️ Math Engine aborted: fund_history is empty. Please run 'Full History Refresh' first.");
                updateStatus(0, "Failed: History missing. Run 'Full History Refresh' first.");
                isRunning.set(false);
                return;
            }

            log.info("🧮 Starting Advanced Quantitative Math Engine (Vectorized Hurst, OU, HMM)...");
            long startTime = System.currentTimeMillis();

            updateStatus(1, "Calculating main risk metrics (Sortino, CVaR)...");
            int mainMetricsRows = convictionMetricsRepository.runNightlyMathEngine();

            updateStatus(2, "Updating NAV signals (Percentile, ATH)...");
            int navSignalRows = convictionMetricsRepository.updateNavSignals();

            updateStatus(4, "Computing Rolling 252-day Z-Score & Volatility Tax...");
            convictionMetricsRepository.updateRollingZScoreAndVolatilityTax();

            long endTime = System.currentTimeMillis();
            updateStatus(7, "Sync complete! System calibrated.");
            log.info("✅ Math Engine Java phase Complete! Main Metrics updated for {} funds. NAV Signals updated for {} rows. Time: {}ms", 
                mainMetricsRows, navSignalRows, (endTime - startTime));
        } catch (Exception e) {
            updateStatus(0, "Failed: " + e.getMessage());
            log.error("🚨 Math Engine Failed to execute native SQL or delegation.", e);
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
                WHERE amfi_code IN (
                    SELECT LTRIM(s.amfi_code, '0') FROM scheme s
                    JOIN folio f ON s.folio_id = f.id
                )
                GROUP BY amfi_code HAVING COUNT(*) >= ?
            )
            AND nav_date >= CURRENT_DATE - INTERVAL '400 days'
            ORDER BY amfi_code, nav_date DESC
        """;

        List<Map<String, Object>> rows = convictionMetricsRepository.getJdbcTemplate().queryForList(sql, days + 1);
        Map<String, List<Double>> navMap = new HashMap<>();

        for (Map<String, Object> r : rows) {
            String amfi = CommonUtils.SANITIZE_AMFI.apply((String) r.get("amfi_code"));
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

    private Map<String, double[]> computeReturnsFromNavs(Map<String, double[]> navsMap) {
        log.info("🗂️ Computing log-returns from pre-loaded NAVs...");
        Map<String, double[]> returnsMap = new HashMap<>();

        for (var entry : navsMap.entrySet()) {
            double[] navs = entry.getValue();
            if (navs.length < 2) continue;

            double[] returns = new double[navs.length - 1];
            for (int i = 0; i < returns.length; i++) {
                double prev = navs[i];
                double today = navs[i+1];
                if (prev <= 0) {
                    returns[i] = 0;
                } else {
                    returns[i] = Math.log(today / prev);
                }
            }
            returnsMap.put(entry.getKey(), returns);
        }
        log.info("🗂️ Computed returns for {} funds.", returnsMap.size());
        return returnsMap;
    }
}
