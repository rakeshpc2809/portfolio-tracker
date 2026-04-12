package com.oreki.cas_injector.convictionmetrics.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import com.oreki.cas_injector.convictionmetrics.repository.ConvictionMetricsRepository;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class QuantitativeEngineService {

    private final ConvictionMetricsRepository convictionMetricsRepository;
    private final BucketZScorerService bucketZScorerService;
    private final HurstExponentService hurstExponentService;
    private final OrnsteinUhlenbeckService ouService;
    private final HmmRegimeService hmmRegimeService;
    private final SimpMessagingTemplate messagingTemplate;
    private final Executor taskExecutor;

    @Getter private final AtomicBoolean isRunning = new AtomicBoolean(false);
    @Getter private final AtomicInteger currentStep = new AtomicInteger(0); // 0-7
    @Getter private String lastStatusMessage = "Idle";

    public QuantitativeEngineService(
            ConvictionMetricsRepository convictionMetricsRepository,
            BucketZScorerService bucketZScorerService,
            HurstExponentService hurstExponentService,
            OrnsteinUhlenbeckService ouService,
            HmmRegimeService hmmRegimeService,
            SimpMessagingTemplate messagingTemplate,
            @Qualifier("mathEngineExecutor") Executor taskExecutor) {
        this.convictionMetricsRepository = convictionMetricsRepository;
        this.bucketZScorerService = bucketZScorerService;
        this.hurstExponentService = hurstExponentService;
        this.ouService = ouService;
        this.hmmRegimeService = hmmRegimeService;
        this.messagingTemplate = messagingTemplate;
        this.taskExecutor = taskExecutor;
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

            log.info("🧮 Starting Advanced Quantitative Math Engine (Sortino, CVaR, MDD, NAV Signals, Hurst, Z-Score, OU, HMM)...");
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

            // Parallel execution of independent steps 5, 6, 7
            updateStatus(5, "Running Hurst, OU and HMM in parallel...");
            
            CompletableFuture<Void> hurstFuture = CompletableFuture.runAsync(
                () -> hurstExponentService.computeAndPersistHurstMetrics(returnsCache),
                taskExecutor
            );
            CompletableFuture<Void> ouFuture = CompletableFuture.runAsync(
                () -> ouService.computeAndPersistOUMetrics(navsCache),
                taskExecutor
            );
            CompletableFuture<Void> hmmFuture = CompletableFuture.runAsync(
                () -> hmmRegimeService.computeAndPersistHmmStates(returnsCache),
                taskExecutor
            );

            CompletableFuture.allOf(hurstFuture, ouFuture, hmmFuture).join();

            long endTime = System.currentTimeMillis();
            updateStatus(7, "Complete! Updated " + mainMetricsRows + " funds.");
            log.info("✅ Math Engine Complete! Main Metrics updated for {} funds. NAV Signals updated for {} rows. Z-Score/Hurst/OU/HMM complete in {} ms.", 
                mainMetricsRows, navSignalRows, (endTime - startTime));
        } catch (Exception e) {
            updateStatus(0, "Failed: " + e.getMessage());
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

            double[] returns = new double[days];
            for (int i = 0; i < days; i++) {
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
