package com.oreki.cas_injector.backfill.service;

import java.io.BufferedReader;
import java.io.StringReader;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.cache.CacheManager;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.oreki.cas_injector.core.model.Investor;
import com.oreki.cas_injector.core.repository.InvestorRepository;
import com.oreki.cas_injector.convictionmetrics.service.ConvictionScoringService;
import com.oreki.cas_injector.convictionmetrics.service.QuantitativeEngineService;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class AmfiDailyDeltaService {

    private final JdbcTemplate jdbcTemplate;
    private final RestTemplate restTemplate;
    private final QuantitativeEngineService quantitativeEngineService;
    private final ConvictionScoringService convictionScoringService;
    private final CacheManager cacheManager;
    private final InvestorRepository investorRepository;

    private static final String AMFI_TXT_URL = "https://www.amfiindia.com/spages/NAVAll.txt";
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("dd-MMM-yyyy");

    public AmfiDailyDeltaService(JdbcTemplate jdbcTemplate, RestTemplate restTemplate, 
                                 QuantitativeEngineService quantitativeEngineService,
                                 ConvictionScoringService convictionScoringService,
                                 CacheManager cacheManager,
                                 InvestorRepository investorRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.restTemplate = restTemplate;
        this.quantitativeEngineService = quantitativeEngineService;
        this.convictionScoringService = convictionScoringService;
        this.cacheManager = cacheManager;
        this.investorRepository = investorRepository;
    }

    // 🌟 THE NEW RESILIENCE FIX 🌟
    // This runs automatically the moment you start your Spring Boot app.
    // If you haven't opened the app in 3 days, opening it now will instantly fetch the latest AMFI file.
    @EventListener(ApplicationReadyEvent.class)
    public void syncOnStartup() {
        log.info("🚀 App Booted: Triggering Catch-Up NAV Sync...");
        executeDailyNavSync();
    }

    // This stays the same for when you DO leave your PC on overnight.
    @Scheduled(cron = "0 30 23 * * MON-FRI", zone = "Asia/Kolkata")
    public void executeDailyNavSync() {
        log.info("📡 Fetching latest AMFI NAVs...");

        try {
            log.info("📡 Fetching latest AMFI NAVs for all ~14,000 funds (Required for peer-relative Z-Scoring)...");
            String amfiData = restTemplate.getForObject(AMFI_TXT_URL, String.class);
            if (amfiData == null || amfiData.isBlank()) return;

            List<Object[]> batchArgs = new ArrayList<>();

            try (BufferedReader reader = new BufferedReader(new StringReader(amfiData))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] parts = line.split(";");
                    if (parts.length == 6 && parts[0].matches("\\d+")) { 
                        try {
                            String amfiCode = parts[0].trim();
                            double nav = Double.parseDouble(parts[4].trim());
                            LocalDate date = LocalDate.parse(parts[5].trim(), DATE_FORMATTER);

                            batchArgs.add(new Object[]{amfiCode, date, nav});
                        } catch (Exception e) {} // Skip bad lines
                    }
                }
            }

            String sql = "INSERT INTO fund_history (amfi_code, nav_date, nav) VALUES (?, ?, ?) " +
                         "ON CONFLICT (amfi_code, nav_date) DO NOTHING";

            int[] results = jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
                @Override
                public void setValues(PreparedStatement ps, int i) throws SQLException {
                    ps.setString(1, (String) batchArgs.get(i)[0]);
                    ps.setObject(2, batchArgs.get(i)[1]);
                    ps.setDouble(3, (Double) batchArgs.get(i)[2]);
                }
                @Override
                public int getBatchSize() { return batchArgs.size(); }
            });

            int insertedCount = 0;
            for (int r : results) if (r > 0) insertedCount++;

            if (insertedCount > 0) {
                log.info("✅ Sync Complete! Appended {} missing NAV records.", insertedCount);
            } else {
                log.info("⚡ Database is already up to date. No new records found.");
            }

            // Trigger engines
            quantitativeEngineService.runNightlyMathEngine();
            investorRepository.findAll().forEach(investor -> {
                convictionScoringService.calculateAndSaveFinalScores(investor.getPan());
            });

            // Evict caches
            evictCache("portfolioCache");
            evictCache("dashboardSummary");
            evictCache("dashboardSummaryV3");

        } catch (Exception e) {
            log.error("🚨 Failed to sync AMFI delta.", e);
        }
    }

    private void evictCache(String name) {
        if (cacheManager.getCache(name) != null) {
            cacheManager.getCache(name).clear();
            log.info("🧹 Cache evicted: {}", name);
        }
    }
}