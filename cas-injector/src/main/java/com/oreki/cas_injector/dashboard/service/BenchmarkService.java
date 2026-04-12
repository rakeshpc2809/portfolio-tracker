package com.oreki.cas_injector.dashboard.service;

import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import com.oreki.cas_injector.core.utils.CommonUtils;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class BenchmarkService {

    private final JdbcTemplate jdbcTemplate;

    /**
     * Fetches actual 1-year annualized return from index_fundamentals if available.
     * Uses a robust CTE approach to find the price exactly 365 days ago.
     */
    public double getBenchmarkReturn(String bucket, String category, String benchmarkIndex) {
        String targetIndex = (benchmarkIndex != null && !benchmarkIndex.isEmpty()) 
            ? benchmarkIndex.trim().toUpperCase() 
            : CommonUtils.DETERMINE_BENCHMARK.apply(bucket, category);

        try {
            String sql = """
                WITH latest AS (
                    SELECT closing_price, date
                    FROM index_fundamentals
                    WHERE index_name = ?
                    ORDER BY date DESC
                    LIMIT 1
                ),
                year_ago AS (
                    SELECT closing_price
                    FROM index_fundamentals
                    WHERE index_name = ?
                    AND date <= (SELECT date FROM latest) - INTERVAL '365 days'
                    ORDER BY date DESC
                    LIMIT 1
                )
                SELECT (latest.closing_price / NULLIF(year_ago.closing_price, 0) - 1) * 100
                FROM latest, year_ago
                """;
            Double result = jdbcTemplate.queryForObject(sql, Double.class, targetIndex, targetIndex);
            if (result != null) return result;
        } catch (Exception e) {
            // Fallback to constants if data is missing or query fails
        }

        String cat = (bucket + " " + category).toUpperCase();
        if (cat.contains("MID")) return 22.4;
        if (cat.contains("SMALL")) return 28.1;
        if (cat.contains("DEBT") || cat.contains("LIQUID")) return 7.2;
        if (cat.contains("GOLD")) return 14.0;
        if (cat.contains("ARBITRAGE")) return 8.5;
        
        return 14.8; 
    }

    public Map<String, Double> getBenchmarkReturnsForAllPeriods(String benchmarkIndex) {
        String index = (benchmarkIndex == null || benchmarkIndex.isEmpty()) ? "NIFTY 50" : benchmarkIndex.trim().toUpperCase();
        
        return Map.of(
            "1M", computeReturnForPeriod(index, 30),
            "3M", computeReturnForPeriod(index, 90),
            "6M", computeReturnForPeriod(index, 180),
            "1Y", computeReturnForPeriod(index, 365),
            "3Y", computeReturnForPeriod(index, 1095)
        );
    }

    private double computeReturnForPeriod(String index, int days) {
        try {
            String sql = """
                WITH latest AS (
                    SELECT closing_price, date FROM index_fundamentals WHERE index_name = ? ORDER BY date DESC LIMIT 1
                ),
                past AS (
                    SELECT closing_price FROM index_fundamentals WHERE index_name = ? 
                    AND date <= (SELECT date FROM latest) - CAST(? || ' days' AS INTERVAL)
                    ORDER BY date DESC LIMIT 1
                )
                SELECT (latest.closing_price / NULLIF(past.closing_price, 0) - 1) * 100 FROM latest, past
                """;
            Double res = jdbcTemplate.queryForObject(sql, Double.class, index, index, days);
            return res != null ? res : 0.0;
        } catch (Exception e) {
            return 0.0;
        }
    }
}
