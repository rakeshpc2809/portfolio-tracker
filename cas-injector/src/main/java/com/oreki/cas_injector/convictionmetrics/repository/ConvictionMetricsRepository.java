package com.oreki.cas_injector.convictionmetrics.repository;

import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class ConvictionMetricsRepository {

    private final JdbcTemplate jdbcTemplate;

    /**
     * Executes the native PostgreSQL Window Functions
     * to calculate quantitative metrics for every fund.
     */
    public int runNightlyMathEngine() {
        String sql = """
            INSERT INTO fund_conviction_metrics (amfi_code, calculation_date, sortino_ratio, cvar_5, win_rate, max_drawdown)

            WITH rolling_window AS (
                -- 1. Grab 3 years of data and calculate daily returns
                SELECT amfi_code, nav_date, nav AS current_nav,
                       LAG(nav) OVER (PARTITION BY amfi_code ORDER BY nav_date) AS previous_nav
                FROM fund_history
                WHERE nav_date >= CURRENT_DATE - INTERVAL '3 years'
            ),
            daily_stats AS (
                -- 2. Filter valid returns and calculate downside excess below 7% MAR
                SELECT amfi_code, nav_date, current_nav,
                       (current_nav - previous_nav) / previous_nav AS daily_return,
                       CASE 
                           WHEN ((current_nav - previous_nav) / previous_nav) < (0.07 / 252.0) 
                           THEN ((current_nav - previous_nav) / previous_nav) - (0.07 / 252.0)
                           ELSE 0 
                       END AS downside_excess
                FROM rolling_window
                WHERE previous_nav IS NOT NULL AND previous_nav > 0
            ),
            percentile_calc AS (
                -- 3. Find the worst 5% threshold (Value at Risk)
                SELECT amfi_code,
                       PERCENTILE_CONT(0.05) WITHIN GROUP (ORDER BY daily_return) AS var_5
                FROM daily_stats
                GROUP BY amfi_code
            ),
            cvar_calc AS (
                -- 4. Calculate Expected Shortfall (Average of the worst 5% days)
                SELECT d.amfi_code,
                       AVG(d.daily_return) AS cvar_5
                FROM daily_stats d
                JOIN percentile_calc p ON d.amfi_code = p.amfi_code
                WHERE d.daily_return <= p.var_5
                GROUP BY d.amfi_code
            ),
            drawdown_and_winrate AS (
                -- 5. Calculate Maximum Drawdown and Win Rate (% of days beating 7% MAR)
                SELECT d.amfi_code,
                       SUM(CASE WHEN d.daily_return > (0.07 / 252.0) THEN 1 ELSE 0 END) * 100.0 / COUNT(*) AS win_rate,
                       (
                           SELECT MIN((h2.nav - h1.nav) / h1.nav)
                           FROM fund_history h1
                           JOIN fund_history h2 ON h1.amfi_code = h2.amfi_code AND h2.nav_date > h1.nav_date
                           WHERE h1.amfi_code = d.amfi_code 
                           AND h1.nav_date >= CURRENT_DATE - INTERVAL '3 years'
                       ) AS max_drawdown
                FROM daily_stats d
                GROUP BY d.amfi_code
            ),
            annualized_aggregations AS (
                -- 6. Annualize the Sortino components
                SELECT amfi_code,
                       AVG(daily_return) * 252 AS annualized_return,
                       SQRT(AVG(POWER(downside_excess, 2))) * SQRT(252) AS annualized_downside_dev
                FROM daily_stats
                GROUP BY amfi_code
            )

            -- 7. Combine all metrics into the final output
            SELECT 
                a.amfi_code, 
                CURRENT_DATE AS calculation_date,
                CASE 
                    WHEN a.annualized_downside_dev = 0 THEN 0 
                    ELSE ROUND(CAST((a.annualized_return - 0.07) / a.annualized_downside_dev AS NUMERIC), 4)
                END AS sortino_ratio,
                ROUND(CAST(c.cvar_5 * 100 AS NUMERIC), 4) AS cvar_5,  -- Converted to percentage
                ROUND(CAST(dw.win_rate AS NUMERIC), 2) AS win_rate,
                ROUND(CAST(dw.max_drawdown * 100 AS NUMERIC), 4) AS max_drawdown -- Converted to percentage
            FROM annualized_aggregations a
            JOIN cvar_calc c ON a.amfi_code = c.amfi_code
            JOIN drawdown_and_winrate dw ON a.amfi_code = dw.amfi_code

            ON CONFLICT (amfi_code, calculation_date) 
            DO UPDATE SET 
                sortino_ratio = EXCLUDED.sortino_ratio,
                cvar_5 = EXCLUDED.cvar_5,
                win_rate = EXCLUDED.win_rate,
                max_drawdown = EXCLUDED.max_drawdown;
            """;
        return jdbcTemplate.update(sql);
    }

    /**
     * Fetches details required for scoring for a specific investor.
     */
    public List<Map<String, Object>> findMetricsForScoring(String investorPan) {
        String fetchSql = """
           SELECT m.amfi_code, m.sortino_ratio, m.max_drawdown, m.calculation_date,
                  f.pe_ratio as fund_pe, f.pb_ratio as fund_pb, f.coverage_pct,
                  s.benchmark_index, idx.pe as bench_pe, idx.pb as bench_pb
           FROM fund_conviction_metrics m
           JOIN scheme s ON m.amfi_code = s.amfi_code
           LEFT JOIN fund_metrics f ON s.amfi_code = f.scheme_code 
                AND f.fetch_date = (SELECT MAX(fetch_date) FROM fund_metrics)
           LEFT JOIN index_fundamentals idx ON s.benchmark_index = idx.index_name
           WHERE m.calculation_date = (SELECT MAX(calculation_date) FROM fund_conviction_metrics)
           AND m.amfi_code IN (
               SELECT s2.amfi_code 
               FROM scheme s2 
               JOIN folio fol ON s2.folio_id = fol.id 
               WHERE fol.investor_pan = ?
           )
        """;
        return jdbcTemplate.queryForList(fetchSql, investorPan);
    }

    /**
     * Updates the calculated conviction scores.
     */
    public void updateConvictionScore(int finalScore, double fundPe, double fundPb, double zScore, 
                                     double coveragePct, String valStatus, String amfiCode) {
        String updateSql = """
            UPDATE fund_conviction_metrics 
            SET conviction_score = ?, pe_ratio = ?, pb_ratio = ?, z_score = ?, coverage_pct = ?, valuation_status = ?
            WHERE amfi_code = ? 
            AND calculation_date = (SELECT MAX(calculation_date) FROM fund_conviction_metrics)
        """;
        jdbcTemplate.update(updateSql, finalScore, fundPe, fundPb, zScore, coveragePct, valStatus, amfiCode);
    }

    /**
     * Fetches latest metrics for display.
     */
    public List<Map<String, Object>> findLatestMetricsByPan(String pan) {
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

    /**
     * Fetches z-score historical spread.
     */
    public List<Double> findHistoricalSpread(String benchmarkIndex, String amfiCode) {
        String historySql = """
            SELECT f.pe_ratio - idx.pe as spread
            FROM fund_metrics f
            JOIN index_fundamentals idx ON idx.index_name = ? AND f.fetch_date = idx.date
            WHERE f.scheme_code = ?
            AND f.fetch_date >= CURRENT_DATE - INTERVAL '365 days'
            AND f.pe_ratio > 0 AND idx.pe > 0
        """;
        return jdbcTemplate.queryForList(historySql, Double.class, benchmarkIndex, amfiCode);
    }
}
