package com.oreki.cas_injector.dashboard.service;

import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class BenchmarkService {

    private final JdbcTemplate jdbcTemplate;

    // Maps common bucket/category keywords to canonical benchmark indices
    private static final Map<String, String> CATEGORY_TO_BENCHMARK = Map.of(
        "LARGE",      "NIFTY 50",
        "MID",        "NIFTY MIDCAP 150",
        "SMALL",      "NIFTY SMALLCAP 250",
        "FLEXI",      "NIFTY 500",
        "DEBT",       "NIFTY 10 YR BENCHMARK G-SEC",
        "GOLD",       "GOLD_PRICE_INDEX"
    );

    /**
     * Estimated annualized return for a category benchmark.
     * In a production system, this would be computed from the index_fundamentals table.
     * Here we return calibrated constants for the current FY.
     */
    public double getBenchmarkReturn(String bucket, String category) {
        String cat = (bucket + " " + category).toUpperCase();
        
        if (cat.contains("MID")) return 22.4;
        if (cat.contains("SMALL")) return 28.1;
        if (cat.contains("DEBT") || cat.contains("LIQUID")) return 7.2;
        if (cat.contains("GOLD")) return 14.0;
        if (cat.contains("ARBITRAGE")) return 8.5;
        
        return 14.8; // Default: Nifty 50 approx
    }
}
