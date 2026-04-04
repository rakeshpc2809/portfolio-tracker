package com.oreki.cas_injector.backfill.service;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.oreki.cas_injector.core.model.Scheme;
import com.oreki.cas_injector.core.repository.SchemeRepository;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class HistoricalBackfillerService {

    private final SchemeRepository schemeRepository;
    private final JdbcTemplate jdbcTemplate;
    private final RestTemplate restTemplate;

    // mfapi.in uses dd-MM-yyyy format
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("dd-MM-yyyy");

    public HistoricalBackfillerService(SchemeRepository schemeRepository, JdbcTemplate jdbcTemplate, RestTemplate restTemplate) {
        this.schemeRepository = schemeRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.restTemplate = restTemplate;
    }

    /**
     * DTOs for parsing the mfapi.in JSON response
     */
    public record MfApiData(String date, String nav) {}
    public record MfApiResponse(Object meta, List<MfApiData> data) {}

    public String executeOneShotBackfill() {
        log.info("🚀 Starting One-Shot Historical Backfill...");

        // 1. Get all unique active funds from your database
        List<Scheme> activeSchemes = schemeRepository.findAll(); // Adjust this if you have a specific custom query
        int totalFunds = activeSchemes.size();
        int successCount = 0;

        for (int i = 0; i < totalFunds; i++) {
            Scheme scheme = activeSchemes.get(i);
            String amfiCode = scheme.getAmfiCode();

            if (amfiCode == null || amfiCode.isBlank()) {
                log.warn("⚠️ Skipping Scheme {}: No AMFI code found.", scheme.getName());
                continue;
            }

            log.info("📥 [{}/{}] Fetching history for {} (AMFI: {})", i + 1, totalFunds, scheme.getName(), amfiCode);

            try {
                // 2. Fetch the ENTIRE history in one shot
                String url = "https://api.mfapi.in/mf/" + amfiCode;
                MfApiResponse response = restTemplate.getForObject(url, MfApiResponse.class);

                if (response != null && response.data() != null && !response.data().isEmpty()) {
                    List<MfApiData> historyData = response.data();
                    
                    // 3. Blast into PostgreSQL using High-Speed JDBC Batching
                    insertBatchIntoDatabase(amfiCode, historyData);
                    successCount++;
                    log.info("✅ Saved {} historical records for {}.", historyData.size(), amfiCode);
                } else {
                    log.warn("⚠️ No historical data returned for AMFI: {}", amfiCode);
                }

                // 4. THE EXPLOIT RULE: Sleep for 10 seconds to avoid 502 Bad Gateway DDoS blocks
                if (i < totalFunds - 1) { // Don't sleep after the very last fund
                    log.info("💤 Sleeping for 10 seconds to respect API limits...");
                    Thread.sleep(10000);
                }

            } catch (InterruptedException e) {
                log.error("🛑 Backfill interrupted!", e);
                Thread.currentThread().interrupt();
                return "Backfill Interrupted!";
            } catch (Exception e) {
                log.error("🚨 Failed to process AMFI {}: {}", amfiCode, e.getMessage());
                // We don't throw here; we want it to keep trying the next funds
            }
        }

        String resultMessage = String.format("🎉 Backfill Complete! Successfully processed %d/%d funds.", successCount, totalFunds);
        log.info(resultMessage);
        return resultMessage;
    }

    /**
     * High-Performance JDBC Batch Insert
     * Avoids JPA overhead which would crash memory when inserting 100,000+ rows.
     */
    private void insertBatchIntoDatabase(String amfiCode, List<MfApiData> historyData) {
        // We use ON CONFLICT DO NOTHING so you can safely re-run this script if it fails halfway
        String sql = "INSERT INTO fund_history (amfi_code, nav_date, nav) VALUES (?, ?, ?) " +
                     "ON CONFLICT (amfi_code, nav_date) DO NOTHING";

        jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                MfApiData record = historyData.get(i);
                try {
                    LocalDate navDate = LocalDate.parse(record.date(), DATE_FORMATTER);
                    double navValue = Double.parseDouble(record.nav());

                    ps.setString(1, amfiCode);
                    ps.setObject(2, navDate);
                    ps.setDouble(3, navValue);
                } catch (DateTimeParseException | NumberFormatException e) {
                    // Fallback for corrupted historical rows (sometimes very old AMFI data has "N/A" for nav)
                    ps.setString(1, amfiCode);
                    ps.setObject(2, LocalDate.of(1970, 1, 1)); 
                    ps.setDouble(3, 0.0);
                }
            }

            @Override
            public int getBatchSize() {
                return historyData.size();
            }
        });
    }
}