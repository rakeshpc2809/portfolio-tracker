package com.oreki.cas_injector.backfill.service;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.oreki.cas_injector.core.dto.SchemeDetailsDTO;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import com.fasterxml.jackson.databind.JsonNode;


@JsonIgnoreProperties(ignoreUnknown = true)
record NavData(
    @JsonProperty("date") String date,
    @JsonProperty("nav") String nav // MFAPI returns NAV as a string
) {
    public double getNavAsDouble() {
        try {
            return nav != null ? Double.parseDouble(nav) : 0.0;
        } catch (NumberFormatException e) {
            return 0.0; // Fallback to avoid crashing the batch job
        }
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
record MfApiResponse(
    @JsonProperty("data") List<NavData> data,
    @JsonProperty("meta") Map<String, String> meta
) {}


@Service
@Slf4j
public class NavService {
    private final RestTemplate restTemplate = new RestTemplate();
    private final Map<String, SchemeDetailsDTO> masterFundMap = new ConcurrentHashMap<>();

    /**
     * This runs automatically exactly ONE time when you start your Spring Boot app.
     * It downloads the file, parses the categories, and fills the map.
     */
    @PostConstruct
    public void downloadAndParseAmfiData() {
        System.out.println("🚀 Downloading AMFI Master File...");
        RestTemplate restTemplate = new RestTemplate();
        String amfiUrl = "https://portal.amfiindia.com/spages/NAVAll.txt";

        try {
            String rawText = restTemplate.getForObject(amfiUrl, String.class);
            if (rawText == null) return;

            String[] lines = rawText.split("\n");
            String currentCategory = "UNKNOWN";

            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty()) continue;

                // 1. Detect Category Headers (They don't have semicolons)
                // Example: "Open Ended Schemes(Equity Scheme - Small Cap Fund)"
                if (!line.contains(";")) {
                    if (line.contains("Open Ended") || line.contains("Close Ended")) {
                        // Clean up the text to look like "Equity Scheme - Small Cap Fund"
                        currentCategory = line.replaceAll(".*\\((.*)\\).*", "$1");
                    }
                    continue; 
                }

                // 2. Parse the actual fund data lines
                String[] columns = line.split(";");
                
                // columns[0] = AMFI Code, columns[3] = Name, columns[4] = NAV
                if (columns.length >= 5 && columns[0].matches("\\d+")) {
                    String amfiCode = columns[0];
                    String name = columns[3];
                    String navString = columns[4];

                    try {
                        BigDecimal nav = new BigDecimal(navString);
                        // Save it to our ultra-fast memory map!
                        masterFundMap.put(amfiCode, new SchemeDetailsDTO(nav, currentCategory, name));
                    } catch (NumberFormatException ignored) {
                        // Sometimes NAV is "N.A.", we just skip it
                    }
                }
            }
            System.out.println("✅ AMFI File Loaded! Total Funds tracking: " + masterFundMap.size());

        } catch (Exception e) {
            System.err.println("🚨 Failed to download AMFI file: " + e.getMessage());
        }
    }

    /**
     * Your CAS Injector calls this. It no longer hits the internet!
     * It just instantly pulls the answer from memory.
     */
    public SchemeDetailsDTO getLatestSchemeDetails(String amfiCode) {
        if (amfiCode == null || amfiCode.isEmpty()) {
            return new SchemeDetailsDTO(BigDecimal.ZERO, "UNKNOWN", "N/A");
        }
        
        // 0ms lookup! No Cloudflare, no 502 Bad Gateway.
        return masterFundMap.getOrDefault(amfiCode, new SchemeDetailsDTO(BigDecimal.ZERO, "UNKNOWN", "N/A"));
    }

}
