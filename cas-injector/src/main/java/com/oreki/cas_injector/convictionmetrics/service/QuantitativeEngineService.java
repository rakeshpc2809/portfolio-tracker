package com.oreki.cas_injector.convictionmetrics.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.math.BigDecimal;
import java.time.LocalDate;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import com.oreki.cas_injector.convictionmetrics.repository.ConvictionMetricsRepository;
import com.oreki.cas_injector.convictionmetrics.service.PythonQuantClient.QuantAnalyzeResponse;
import com.oreki.cas_injector.core.utils.CommonUtils;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class QuantitativeEngineService {

    private final ConvictionMetricsRepository convictionMetricsRepository;
    private final BucketZScorerService bucketZScorerService;
    private final SimpMessagingTemplate messagingTemplate;
    private final Executor taskExecutor;
    private final PythonQuantClient pythonQuantClient;

    @Getter private final AtomicBoolean isRunning = new AtomicBoolean(false);
    @Getter private final AtomicInteger currentStep = new AtomicInteger(0);
    @Getter private String lastStatusMessage = "Idle";

    public QuantitativeEngineService(
            ConvictionMetricsRepository convictionMetricsRepository,
            BucketZScorerService bucketZScorerService,
            SimpMessagingTemplate messagingTemplate,
            @Qualifier("mathEngineExecutor") Executor taskExecutor,
            PythonQuantClient pythonQuantClient) {
        this.convictionMetricsRepository = convictionMetricsRepository;
        this.bucketZScorerService = bucketZScorerService;
        this.messagingTemplate = messagingTemplate;
        this.taskExecutor = taskExecutor;
        this.pythonQuantClient = pythonQuantClient;
    }

    private void updateStatus(int step, String message) {
        this.currentStep.set(step);
        this.lastStatusMessage = message;
        messagingTemplate.convertAndSend("/topic/engine-progress", 
            Map.of("step", step, "message", message, "total", 7));
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

            // 1. Run main risk metrics
            updateStatus(1, "Calculating main risk metrics (Sortino, CVaR)...");
            int mainMetricsRows = convictionMetricsRepository.runNightlyMathEngine();

            // 2. Run NAV Signals
            updateStatus(2, "Updating NAV signals (Percentile, ATH)...");
            int navSignalRows = convictionMetricsRepository.updateNavSignals();

            // 3. Run Bucket Z-Scorer
            updateStatus(3, "Computing relative Bucket Z-Scores...");
            bucketZScorerService.computeBucketCqs();

            // 4. Run Rolling Z-Score & Volatility Tax
            updateStatus(4, "Computing Rolling 252-day Z-Score & Volatility Tax...");
            convictionMetricsRepository.updateRollingZScoreAndVolatilityTax();

            // ─── Pre-load caches ───
            Map<String, double[]> returnsCache = loadAllReturns(252);
            Map<String, double[]> navsCache = loadAllNavs(252);

            // Step 5: Delegate to Python Sidecar for heavy math
            updateStatus(5, "Delegating Hurst, OU and HMM to Python Sidecar...");
            
            List<String> amfiCodes = new ArrayList<>(navsCache.keySet());
            int totalAmfi = amfiCodes.size();
            
            for (int i = 0; i < totalAmfi; i++) {
                String amfi = amfiCodes.get(i);
                double[] navs = navsCache.get(amfi);
                double[] returns = returnsCache.get(amfi);
                
                if (navs == null || returns == null) continue;

                // Call Python analyze API
                QuantAnalyzeResponse res = pythonQuantClient.analyze(amfi, navs, returns);
                if (res != null) {
                    persistPythonMetrics(amfi, res);
                }

                if (i % 10 == 0) {
                    updateStatus(6, String.format("Processed %d/%d funds via Python...", i, totalAmfi));
                }
            }

            long endTime = System.currentTimeMillis();
            updateStatus(7, "Complete! Updated " + mainMetricsRows + " funds.");
            log.info("✅ Math Engine Complete! Main Metrics updated for {} funds. NAV Signals updated for {} rows. Python Vectorization complete in {} ms.", 
                mainMetricsRows, navSignalRows, (endTime - startTime));
        } catch (Exception e) {
            updateStatus(0, "Failed: " + e.getMessage());
            log.error("🚨 Math Engine Failed to execute native SQL or delegation.", e);
        } finally {
            isRunning.set(false);
        }
    }

    private void persistPythonMetrics(String amfi, QuantAnalyzeResponse res) {
        String hRegime = res.hurst() < 0.47 ? "MEAN_REVERTING" : (res.hurst() > 0.53 ? "TRENDING" : "RANDOM_WALK");
        String hmmStateStr = switch(res.hmm_state()) {
            case 0 -> "CALM_BULL";
            case 1 -> "STRESSED_NEUTRAL";
            case 2 -> "VOLATILE_BEAR";
            default -> "UNKNOWN";
        };

        convictionMetricsRepository.getJdbcTemplate().update("""
            UPDATE fund_conviction_metrics
            SET hurst_exponent = ?,
                hurst_regime = ?,
                ou_half_life = ?,
                ou_valid = ?,
                hmm_state = ?,
                hmm_bull_prob = ?,
                hmm_bear_prob = ?,
                hmm_transition_bear = ?
            WHERE LTRIM(amfi_code, '0') = LTRIM(?, '0')
            AND calculation_date = (SELECT MAX(calculation_date) FROM fund_conviction_metrics)
            """, res.hurst(), hRegime, res.ou_half_life(), res.ou_valid(), hmmStateStr, 
                 res.bull_prob(), res.bear_prob(), res.transition_to_bear(), amfi, amfi);
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

    private Map<String, double[]> loadAllReturns(int days) {
        log.info("🗂️ Pre-loading log-returns for all funds ({} days)...", days);
        Map<String, double[]> navsMap = loadAllNavs(days + 1);
        Map<String, double[]> returnsMap = new HashMap<>();

        for (var entry : navsMap.entrySet()) {
            double[] navs = entry.getValue();
            double[] returns = new double[navs.length - 1];
            for (int i = 0; i < returns.length; i++) {
                double prev = navs[i];
                double today = navs[i+1];
                if (prev == 0) {
                    returns[i] = 0;
                } else {
                    returns[i] = Math.log(today / prev);
                }
            }
            returnsMap.put(entry.getKey(), returns);
        }
        log.info("🗂️ Cache primed with returns for {} funds.", returnsMap.size());
        return returnsMap;
    }
}
