package com.oreki.cas_injector.convictionmetrics.service;

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

    @Getter private final AtomicBoolean isRunning = new AtomicBoolean(false);
    @Getter private final AtomicInteger currentStep = new AtomicInteger(0); // 0-4
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
            log.info("🧮 Starting Advanced Quantitative Math Engine (Sortino, CVaR, MDD, NAV Signals)...");
            long startTime = System.currentTimeMillis();

            // 1. Ensure new columns exist
            currentStep.set(1);
            lastStatusMessage = "Ensuring DB columns exist...";
            convictionMetricsRepository.ensureColumnsExist();

            // 2. Run existing Sortino/CVaR/MDD block
            currentStep.set(2);
            lastStatusMessage = "Calculating main risk metrics (Sortino, CVaR)...";
            int mainMetricsRows = convictionMetricsRepository.runNightlyMathEngine();

            // 3. Run new NAV Signals block (Percentile, ATH Drawdown, Return Z-Score)
            currentStep.set(3);
            lastStatusMessage = "Updating NAV signals (Percentile, ATH)...";
            int navSignalRows = convictionMetricsRepository.updateNavSignals();

            // 4. Run new Bucket Z-Scorer (CQS)
            currentStep.set(4);
            lastStatusMessage = "Computing relative Bucket Z-Scores...";
            bucketZScorerService.computeBucketCqs();

            long endTime = System.currentTimeMillis();
            lastStatusMessage = "Complete! Updated " + mainMetricsRows + " funds.";
            log.info("✅ Math Engine Complete! Main Metrics updated for {} funds. NAV Signals updated for {} rows in {} ms.", 
                mainMetricsRows, navSignalRows, (endTime - startTime));
        } catch (Exception e) {
            lastStatusMessage = "Failed: " + e.getMessage();
            log.error("🚨 Math Engine Failed to execute native SQL.", e);
        } finally {
            isRunning.set(false);
        }
    }
}