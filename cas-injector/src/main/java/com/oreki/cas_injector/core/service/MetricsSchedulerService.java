package com.oreki.cas_injector.core.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class MetricsSchedulerService {

    private static final Logger logger = LoggerFactory.getLogger(MetricsSchedulerService.class);
    private final RestTemplate restTemplate;

    @Value("${scraper.service.url:http://python-scraper:8001}")
    private String scraperUrl;

    @Scheduled(cron = "0 30 19 * * MON-FRI", zone = "Asia/Kolkata")
    public void runPipeline() {
        logger.info("Starting scheduled execution of Market & MF Metrics Pipeline via HTTP...");

        // 1. Trigger Benchmark Index Scraper
        triggerScraper("/api/scraper/sync-market", "Benchmark Index Scraper");

        // 2. Trigger Mutual Fund Metrics Engine
        triggerScraper("/api/scraper/sync-metrics", "Mutual Fund Metrics Engine");

        logger.info("Market & MF Metrics Pipeline trigger finished.");
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
