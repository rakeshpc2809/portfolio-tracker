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
import java.util.List;
import java.util.Map;

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

    /**
     * Retrospective backfill of snapshots based on transaction history and fund_history.
     * Use this if snapshots were missed or if fresh historical data is imported.
     */
    public void backfillSnapshots(String pan) {
        logger.info("🚀 Starting retrospective snapshot backfill for PAN: {}", pan);
        
        // 1. Get the earliest transaction date
        LocalDate startDate = jdbcTemplate.queryForObject(
            "SELECT MIN(transaction_date) FROM transaction t JOIN scheme s ON t.scheme_id = s.id JOIN folio f ON s.folio_id = f.id WHERE f.investor_pan = ?",
            LocalDate.class, pan);
            
        if (startDate == null) {
            logger.warn("No transactions found for PAN: {}. Aborting backfill.", pan);
            return;
        }

        LocalDate today = LocalDate.now();
        
        // Loop through every day from startDate to today
        for (LocalDate date = startDate; !date.isAfter(today); date = date.plusDays(1)) {
            // Compute cumulative units and total cost for all schemes as of this date
            // Logic: Total Units = SUM(units) before or on 'date'
            // Total Invested = SUM(amount) for all BUYs before or on 'date' (Simplified)
            
            String snapshotSql = """
                WITH holdings_at_date AS (
                    SELECT 
                        s.amfi_code,
                        SUM(t.units) as total_units,
                        SUM(CASE WHEN t.units > 0 THEN t.amount ELSE 0 END) as total_invested_at_cost
                    FROM transaction t
                    JOIN scheme s ON t.scheme_id = s.id
                    JOIN folio f ON s.folio_id = f.id
                    WHERE f.investor_pan = ?
                    AND t.transaction_date <= ?
                    GROUP BY s.amfi_code
                    HAVING SUM(t.units) > 0.001
                ),
                latest_nav_at_date AS (
                    SELECT DISTINCT ON (amfi_code) amfi_code, nav
                    FROM fund_history
                    WHERE nav_date <= ?
                    ORDER BY amfi_code, nav_date DESC
                )
                SELECT 
                    SUM(h.total_units * n.nav) as total_value,
                    SUM(h.total_invested_at_cost) as total_invested
                FROM holdings_at_date h
                JOIN latest_nav_at_date n ON h.amfi_code = n.amfi_code
                """;

            Map<String, Object> result = jdbcTemplate.queryForMap(snapshotSql, pan, date, date);
            Double value = (result.get("total_value") != null) ? ((Number) result.get("total_value")).doubleValue() : null;
            Double invested = (result.get("total_invested") != null) ? ((Number) result.get("total_invested")).doubleValue() : null;

            if (value != null && invested != null) {
                jdbcTemplate.update("""
                    INSERT INTO portfolio_snapshot (pan, snapshot_date, total_value, total_invested)
                    VALUES (?, ?, ?, ?)
                    ON CONFLICT (pan, snapshot_date) DO UPDATE 
                    SET total_value = EXCLUDED.total_value,
                        total_invested = EXCLUDED.total_invested
                    """, pan, date, value, invested);
            }
        }
        
        logger.info("✅ Retrospective backfill complete for PAN: {}", pan);
        
        // Evict caches
        Cache pCache = cacheManager.getCache("portfolioCache");
        if (pCache != null) pCache.clear();
    }

    public void snapshotAllPortfolios() {
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

        // Evict caches
        Cache pCache = cacheManager.getCache("portfolioCache");
        if (pCache != null) pCache.clear();
        Cache dCache = cacheManager.getCache("dashboardSummaryV3");
        if (dCache != null) dCache.clear();
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
