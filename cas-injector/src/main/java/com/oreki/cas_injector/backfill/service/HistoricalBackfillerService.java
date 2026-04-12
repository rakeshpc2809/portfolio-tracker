package com.oreki.cas_injector.backfill.service;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oreki.cas_injector.core.model.Scheme;
import com.oreki.cas_injector.core.repository.SchemeRepository;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class HistoricalBackfillerService {

    private final SchemeRepository schemeRepository;
    private final JdbcTemplate jdbcTemplate;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Getter private final AtomicBoolean isRunning = new AtomicBoolean(false);
    @Getter private final AtomicInteger currentProgress = new AtomicInteger(0);
    @Getter private final AtomicInteger totalToProcess = new AtomicInteger(0);
    @Getter private String lastStatusMessage = "Idle";

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("dd-MM-yyyy");

    public HistoricalBackfillerService(SchemeRepository schemeRepository, JdbcTemplate jdbcTemplate, RestTemplate restTemplate) {
        this.schemeRepository = schemeRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.restTemplate = restTemplate;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MfApiData(String date, String nav) {}
    
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MfApiResponse(Object meta, List<MfApiData> data) {}

    public String executeOneShotBackfill() {
        if (isRunning.getAndSet(true)) {
            return "Backfill is already in progress.";
        }

        try {
            log.info("🚀 Starting One-Shot Historical Backfill with Smart JSON Healer...");
            lastStatusMessage = "Initializing...";

            List<Scheme> activeSchemes = schemeRepository.findAll(); 
            int totalFunds = activeSchemes.size();
            totalToProcess.set(totalFunds);
            currentProgress.set(0);
            int successCount = 0;

            for (int i = 0; i < totalFunds; i++) {
                currentProgress.set(i + 1);
                Scheme scheme = activeSchemes.get(i);
                String amfiCode = scheme.getAmfiCode();

                if (amfiCode == null || amfiCode.isBlank()) continue;

                lastStatusMessage = "Fetching " + scheme.getName() + " (" + amfiCode + ")";
                log.info("📥 [{}/{}] Fetching history for {} (AMFI: {})", i + 1, totalFunds, scheme.getName(), amfiCode);

                try {
                    String url = "https://api.mfapi.in/mf/" + amfiCode;
                    
                    // 1. Fetch raw string first to allow healing
                    String rawJson = restTemplate.getForObject(url, String.class);
                    
                    if (rawJson != null && !rawJson.isBlank()) {
                        MfApiResponse response = null;
                        try {
                            // Try normal parsing
                            response = objectMapper.readValue(rawJson, MfApiResponse.class);
                        } catch (Exception e) {
                            log.warn("⚠️ JSON Malformed for AMFI {}. Attempting to heal...", amfiCode);
                            String healedJson = healJson(rawJson);
                            response = objectMapper.readValue(healedJson, MfApiResponse.class);
                        }

                        if (response != null && response.data() != null && !response.data().isEmpty()) {
                            insertBatchIntoDatabase(amfiCode, response.data());
                            successCount++;
                            log.info("✅ Saved {} records for {}.", response.data().size(), amfiCode);
                        }
                    }

                    if (i < totalFunds - 1) { 
                        lastStatusMessage = "Cooling down (10s)... Next: " + activeSchemes.get(i+1).getName();
                        Thread.sleep(10000);
                    }

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return "Backfill Interrupted!";
                } catch (Exception e) {
                    log.error("🚨 Failed to process AMFI {}: {}", amfiCode, e.getMessage());
                }
            }

            lastStatusMessage = "Complete! Processed " + successCount + " funds.";
            return String.format("🎉 Backfill Complete! Successfully processed %d/%d funds.", successCount, totalFunds);
        } finally {
            isRunning.set(false);
        }
    }

    /**
     * Smart JSON Healer: Strips the trailing incomplete object and closes the JSON array/object.
     */
    private String healJson(String raw) {
        // Find the last complete data entry "}," or similar
        int lastCompleteIndex = raw.lastIndexOf("},");
        if (lastCompleteIndex == -1) return raw; // Give up
        
        // Truncate at the end of the last complete object and append "]}" 
        return raw.substring(0, lastCompleteIndex + 1) + "]}";
    }

    private void insertBatchIntoDatabase(String amfiCode, List<MfApiData> historyData) {
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
                } catch (Exception e) {
                    ps.setString(1, amfiCode);
                    ps.setObject(2, LocalDate.of(1970, 1, 1)); 
                    ps.setDouble(3, 0.0);
                }
            }
            @Override
            public int getBatchSize() { return historyData.size(); }
        });
    }
}
