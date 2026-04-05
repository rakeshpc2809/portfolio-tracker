package com.oreki.cas_injector.convictionmetrics.service;

import org.springframework.stereotype.Service;

import com.oreki.cas_injector.convictionmetrics.repository.ConvictionMetricsRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
public class QuantitativeEngineService {

    private final ConvictionMetricsRepository convictionMetricsRepository;

    /**
     * This method triggers the native PostgreSQL Window Functions
     * to calculate quantitative metrics for every fund.
     */
  public void runNightlyMathEngine() {
        log.info("🧮 Starting Advanced Quantitative Math Engine (Sortino, CVaR, MDD, NAV Signals)...");

        try {
            long startTime = System.currentTimeMillis();
            
            // 1. Ensure new columns exist
            convictionMetricsRepository.ensureColumnsExist();

            // 2. Run existing Sortino/CVaR/MDD block
            int mainMetricsRows = convictionMetricsRepository.runNightlyMathEngine();
            
            // 3. Run new NAV Signals block (Percentile, ATH Drawdown, Return Z-Score)
            int navSignalRows = convictionMetricsRepository.updateNavSignals();

            long endTime = System.currentTimeMillis();
            log.info("✅ Math Engine Complete! Main Metrics updated for {} funds. NAV Signals updated for {} rows in {} ms.", 
                mainMetricsRows, navSignalRows, (endTime - startTime));
        } catch (Exception e) {
            log.error("🚨 Math Engine Failed to execute native SQL.", e);
        }
    }
}