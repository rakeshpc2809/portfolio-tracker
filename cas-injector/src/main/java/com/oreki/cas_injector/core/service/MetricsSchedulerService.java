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

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class MetricsSchedulerService {

    private static final Logger logger = LoggerFactory.getLogger(MetricsSchedulerService.class);
    private final RestTemplate restTemplate;
    private final DashboardService dashboardService;
    private final InvestorRepository investorRepository;
    private final JdbcTemplate jdbcTemplate;

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
                var summary = dashboardService.getInvestorSummary(pan);
                double totalValue = summary.getCurrentValueAmount().doubleValue();
                
                jdbcTemplate.update("""
                    INSERT INTO portfolio_snapshot (pan, snapshot_date, total_value)
                    VALUES (?, ?, ?)
                    ON CONFLICT (pan, snapshot_date) DO UPDATE SET total_value = EXCLUDED.total_value
                    """, pan, LocalDate.now(), totalValue);
                
                logger.info("📸 Saved snapshot for PAN {}: ₹{}", pan, totalValue);
            } catch (Exception e) {
                logger.error("❌ Failed to snapshot portfolio for investor {}: {}", investor.getPan(), e.getMessage());
            }
        });
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
