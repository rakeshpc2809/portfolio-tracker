package com.oreki.cas_injector.core.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import org.springframework.jdbc.core.JdbcTemplate;
import com.oreki.cas_injector.core.repository.InvestorRepository;
import com.oreki.cas_injector.dashboard.service.DashboardService;
import java.time.LocalDate;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class MetricsSchedulerService {

    private static final Logger logger = LoggerFactory.getLogger(MetricsSchedulerService.class);
    private final RestTemplate restTemplate;
    private final DashboardService dashboardService;
    private final InvestorRepository investorRepository;
    private final JdbcTemplate jdbcTemplate;
    private final CacheManager cacheManager;

    @Value("${scraper.service.url:http://python-scraper:8001}")
    private String scraperUrl;

    @Scheduled(cron = "0 30 19 * * MON-FRI", zone = "Asia/Kolkata")
    public void runPipeline() {
        logger.info("Starting scheduled execution of Market & MF Metrics Pipeline via HTTP...");

        // 1. Trigger Benchmark Index Scraper
        triggerScraper("/api/scraper/sync-market", "Benchmark Index Scraper");

        // 2. Trigger Mutual Fund Metrics Engine
        triggerScraper("/api/scraper/sync-metrics", "Mutual Fund Metrics Engine");

        // 3. Snapshot Portfolio Values for all investors
        snapshotAllPortfolios();

        logger.info("Market & MF Metrics Pipeline trigger finished.");
    }

    private void snapshotAllPortfolios() {
        logger.info("📸 Starting nightly portfolio value snapshot...");
        investorRepository.findAll().forEach(investor -> {
            try {
                String pan = investor.getPan();
                
                // Direct DB query — not cached, always fresh
                Double totalValue = jdbcTemplate.queryForObject("""
                    SELECT COALESCE(SUM(tl.remaining_units * s2.nav), 0)
                    FROM tax_lot tl
                    JOIN scheme s ON tl.scheme_id = s.id
                    JOIN folio f ON s.folio_id = f.id
                    JOIN (
                        SELECT amfi_code, nav
                        FROM fund_history
                        WHERE (amfi_code, nav_date) IN (
                            SELECT amfi_code, MAX(nav_date)
                            FROM fund_history
                            GROUP BY amfi_code
                        )
                    ) s2 ON s2.amfi_code = s.amfi_code
                    WHERE f.investor_pan = ?
                    AND tl.status = 'OPEN'
                    """, Double.class, pan);
                
                // Also compute total invested from open lots
                Double totalInvested = jdbcTemplate.queryForObject("""
                    SELECT COALESCE(SUM(tl.remaining_units * tl.cost_basis_per_unit), 0)
                    FROM tax_lot tl
                    JOIN scheme s ON tl.scheme_id = s.id
                    JOIN folio f ON s.folio_id = f.id
                    WHERE f.investor_pan = ? AND tl.status = 'OPEN'
                    """, Double.class, pan);

                jdbcTemplate.update("""
                    INSERT INTO portfolio_snapshot (pan, snapshot_date, total_value, total_invested)
                    VALUES (?, ?, ?, ?)
                    ON CONFLICT (pan, snapshot_date) DO UPDATE 
                    SET total_value = EXCLUDED.total_value,
                        total_invested = EXCLUDED.total_invested
                    """, pan, LocalDate.now(), 
                        totalValue != null ? totalValue : 0.0,
                        totalInvested != null ? totalInvested : 0.0);
                        
                logger.info("📸 Snapshot for PAN {}: Value=₹{} Invested=₹{}", 
                    pan, totalValue, totalInvested);
            } catch (Exception e) {
                logger.error("❌ Failed to snapshot portfolio for investor {}: {}", investor.getPan(), e.getMessage());
            }
        });

        // Evict caches (Step 3.3)
        Cache pCache = cacheManager.getCache("portfolioCache");
        if (pCache != null) {
            pCache.clear();
            logger.info("🗑️ Portfolio cache cleared.");
        }
        
        Cache dCache = cacheManager.getCache("dashboardSummaryV3");
        if (dCache != null) {
            dCache.clear();
            logger.info("🗑️ Dashboard cache cleared.");
        }
    }

    private void triggerScraper(String endpoint, String taskName) {
        String url = scraperUrl + endpoint;
        logger.info("Triggering {} at {}", taskName, url);
        try {
            restTemplate.postForObject(url, null, String.class);
            logger.info("Successfully triggered {}.", taskName);
        } catch (Exception e) {
            logger.error("Failed to trigger {}: {}", taskName, e.getMessage());
        }
    }
}
