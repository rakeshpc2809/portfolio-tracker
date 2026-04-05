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
        log.info("🧮 Starting Advanced Quantitative Math Engine (Sortino, CVaR, MDD)...");

        try {
            long startTime = System.currentTimeMillis();
            int rowsAffected = convictionMetricsRepository.runNightlyMathEngine();
            long endTime = System.currentTimeMillis();
            log.info("✅ Math Engine Complete! Calculated Advanced Metrics for {} funds in {} ms.", rowsAffected, (endTime - startTime));
        } catch (Exception e) {
            log.error("🚨 Math Engine Failed to execute native SQL.", e);
        }
    }
}