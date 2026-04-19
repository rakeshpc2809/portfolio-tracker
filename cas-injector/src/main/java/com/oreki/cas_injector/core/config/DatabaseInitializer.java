package com.oreki.cas_injector.core.config;

import com.oreki.cas_injector.backfill.service.NavService;
import com.oreki.cas_injector.convictionmetrics.repository.ConvictionMetricsRepository;
import com.oreki.cas_injector.dashboard.repository.PortfolioDashboardReadModelRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class DatabaseInitializer implements ApplicationRunner {

    private final ConvictionMetricsRepository convictionMetricsRepository;
    private final PortfolioDashboardReadModelRepository portfolioSummaryRepository;
    private final NavService navService;

    @Override
    public void run(ApplicationArguments args) {
        log.info("🎬 [1/4] Starting database initialization sequence...");
        
        try {
            log.info("🎬 [2/4] Ensuring table columns exist...");
            convictionMetricsRepository.ensureColumnsExist();
        } catch (Exception e) {
            log.error("❌ Failed to ensure columns exist: {}", e.getMessage());
        }
            
        try {
            log.info("🎬 [3/4] Ensuring Read Model consistency...");
            // No-op for now as JPA creates the table
        } catch (Exception e) {
            log.error("❌ Failed to initialize Read Model: {}", e.getMessage());
        }
            
        try {
            log.info("🎬 [4/4] Warming up NAV Cache...");
            navService.refreshCache();
        } catch (Exception e) {
            log.error("❌ Failed to warm-boot NAV Cache: {}", e.getMessage());
        }
            
        log.info("✨ Database initialization sequence finished.");
    }
}
