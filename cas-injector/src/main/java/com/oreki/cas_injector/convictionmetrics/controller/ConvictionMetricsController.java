package com.oreki.cas_injector.convictionmetrics.controller;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/metrics")
@CrossOrigin(origins = "http://localhost:5173") // Allow your React Dev Server
public class ConvictionMetricsController {

    private final JdbcTemplate jdbcTemplate;

    public ConvictionMetricsController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Fetches the latest quantitative metrics for all funds 
     * currently held by a specific investor.
     */
    @GetMapping("/latest/{pan}")
    public List<Map<String, Object>> getLatestMetrics(@PathVariable String pan) {
        // This query joins our V2 Metrics with your V1 Holdings
        // to ensure the UI only gets dataa for funds you actually own.
        String sql = """
       SELECT 
                s.name as "schemeName",
                m.amfi_code as "amfiCode",
                m.sortino_ratio as "sortinoRatio",
                m.cvar_5 as "cvar5",
                m.win_rate as "winRate",
                m.max_drawdown as "maxDrawdown",
                m.conviction_score as "convictionScore",
                m.calculation_date as "calculationDate"
            FROM fund_conviction_metrics m
            join scheme s on m.amfi_code=s.amfi_code 
            join folio f on s.folio_id =f.id
            where f.investor_pan = ?
            AND m.calculation_date = (SELECT MAX(calculation_date) FROM fund_conviction_metrics)
            """;

        return jdbcTemplate.queryForList(sql, pan);
    }
}