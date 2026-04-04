package com.oreki.cas_injector.core;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.oreki.cas_injector.rebalancing.dto.StrategyTarget;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class GoogleSheetService {

    private static final String CSV_URL = "https://docs.google.com/spreadsheets/d/e/2PACX-1vTq7bqtww_gsZv4FPXBWErHOSmuPSNrnL-82cno8rShNP9TsTOjJmkPBnocg_GqSWALUQo9YEI_dyto/pub?gid=412111003&single=true&output=csv";

    private final RestTemplate restTemplate;

    public GoogleSheetService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public List<StrategyTarget> fetchLatestStrategy() {
        List<StrategyTarget> targets = new ArrayList<>();
        try {
            String csvData = restTemplate.getForObject(CSV_URL, String.class);
            
            if (csvData == null || csvData.isBlank()) {
                log.warn("🚨 Google Sheet returned empty data.");
                return targets;
            }

            // Strip UTF-8 BOM if present
            if (csvData.startsWith("\uFEFF")) {
                csvData = csvData.substring(1);
            }

            // Parse with resilient settings
            CSVParser parser = CSVFormat.DEFAULT.builder()
                    .setHeader()
                    .setIgnoreHeaderCase(true)
                    .setTrim(true)
                    .setIgnoreEmptyLines(true)
                    .setAllowMissingColumnNames(true)
                    .build()
                    .parse(new StringReader(csvData));

            // Log the headers the parser found
            List<String> headers = parser.getHeaderNames();
            log.info("📊 Detected CSV Headers: {}", headers);

            // If headers are empty, the Google Sheet likely has a blank first row.
            if (headers.isEmpty()) {
                 log.error("🚨 Headers are empty! Please ensure row 1 in your Google Sheet contains your column names (ISIN, Scheme Name, etc.) and is not a blank row.");
                 return targets;
            }

            for (CSVRecord record : parser) {
                Map<String, String> recordMap = record.toMap();
                
                String rawName = getFuzzyValue(recordMap, "Scheme Name", "Fund Name", "SchemeName");
                String isin = getFuzzyValue(recordMap, "ISIN", "ISIN Code", "ISDN");

                if (isin == null || isin.isBlank()) {
                    if (rawName != null && !rawName.isBlank()) {
                        log.warn("⚠️ Skipping row. Missing ISIN for fund: {}", rawName);
                    }
                    continue; 
                }

                double target = parseValue(getFuzzyValue(recordMap, "Target %", "Planned %", "Target", "Planned"));
                double sip = parseValue(getFuzzyValue(recordMap, "SIP %", "SIP"));
                
                // 🚀 NEW: Extract Status (e.g., "ACTIVE", "DROPPED")
                String status = getFuzzyValue(recordMap, "Status", "Fund Status", "State");
                if (status == null || status.isBlank()) {
                    status = "ACTIVE"; // Default to ACTIVE if the column is empty
                }

                // ✅ Updated constructor with 5 arguments
                targets.add(new StrategyTarget(isin.trim(), rawName, target, sip, status.trim().toUpperCase()));
            }
            
            log.info("✅ Successfully loaded {} strategy targets from Google Sheets.", targets.size());

        } catch (Exception e) {
            log.error("🚨 Google Sheet Sync Failed. Details: {}", e.getMessage());
            e.printStackTrace();
        }
        return targets;
    }

    /**
     * Fuzzy Header Matcher using the Record's Map
     */
    private String getFuzzyValue(Map<String, String> rowData, String... possibleNames) {
        for (String headerKey : rowData.keySet()) {
            if (headerKey == null || headerKey.trim().isEmpty()) continue; // Skip blank Google Sheet columns
            
            for (String possibleName : possibleNames) {
                // Compare ignoring spaces and case
                if (headerKey.replace(" ", "").equalsIgnoreCase(possibleName.replace(" ", ""))) {
                    return rowData.get(headerKey);
                }
            }
        }
        return null; 
    }

    private double parseValue(String val) {
        try {
            if (val == null || val.isBlank() || val.equals("-")) return 0.0;
            String cleanVal = val.replace("%", "").replace(",", "").trim();
            return Double.parseDouble(cleanVal);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }
}