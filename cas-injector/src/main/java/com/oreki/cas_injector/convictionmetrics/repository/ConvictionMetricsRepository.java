package com.oreki.cas_injector.convictionmetrics.repository;

import java.util.List;
import java.util.Map;

import com.oreki.cas_injector.convictionmetrics.dto.MarketMetrics;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Repository
@RequiredArgsConstructor
@Slf4j
public class ConvictionMetricsRepository {

    @Getter
    private final JdbcTemplate jdbcTemplate;

    /**
     * Ensures the fund_conviction_metrics table and its columns exist.
     */
    @jakarta.annotation.PostConstruct
    public void ensureColumnsExist() {
        // 1. Global Data Sanitization: Strip leading zeros from all tables for consistency
        log.info("🧹 Running global AMFI code sanitization...");
        jdbcTemplate.execute("UPDATE scheme SET amfi_code = LTRIM(amfi_code, '0') WHERE amfi_code LIKE '0%'");
        jdbcTemplate.execute("UPDATE fund_history SET amfi_code = LTRIM(amfi_code, '0') WHERE amfi_code LIKE '0%'");
        jdbcTemplate.execute("UPDATE fund_conviction_metrics SET amfi_code = LTRIM(amfi_code, '0') WHERE amfi_code LIKE '0%'");

        String createTableSql = """
            CREATE TABLE IF NOT EXISTS fund_conviction_metrics (
                amfi_code VARCHAR(255) NOT NULL,
                calculation_date DATE NOT NULL,
                sortino_ratio DOUBLE PRECISION,
                cvar_5 DOUBLE PRECISION,
                win_rate DOUBLE PRECISION,
                max_drawdown DOUBLE PRECISION,
                conviction_score INT,
                PRIMARY KEY (amfi_code, calculation_date)
            );
        """;
        jdbcTemplate.execute(createTableSql);

        String addColumnsSql = """
            ALTER TABLE fund_conviction_metrics
            ADD COLUMN IF NOT EXISTS nav_percentile_3yr DOUBLE PRECISION DEFAULT 0.5,
            ADD COLUMN IF NOT EXISTS drawdown_from_ath   DOUBLE PRECISION DEFAULT 0.0,
            ADD COLUMN IF NOT EXISTS return_z_score      DOUBLE PRECISION DEFAULT 0.0,
            ADD COLUMN IF NOT EXISTS yield_score         DOUBLE PRECISION DEFAULT 50.0,
            ADD COLUMN IF NOT EXISTS risk_score          DOUBLE PRECISION DEFAULT 50.0,
            ADD COLUMN IF NOT EXISTS value_score         DOUBLE PRECISION DEFAULT 50.0,
            ADD COLUMN IF NOT EXISTS pain_score          DOUBLE PRECISION DEFAULT 50.0,
            ADD COLUMN IF NOT EXISTS friction_score      DOUBLE PRECISION DEFAULT 50.0,
            ADD COLUMN IF NOT EXISTS composite_quant_score INT DEFAULT 50,
            ADD COLUMN IF NOT EXISTS bucket_peer_count   INT DEFAULT 0,
            ADD COLUMN IF NOT EXISTS rolling_z_score_252 DOUBLE PRECISION DEFAULT 0.0,
            ADD COLUMN IF NOT EXISTS hurst_exponent      DOUBLE PRECISION DEFAULT 0.5,
            ADD COLUMN IF NOT EXISTS volatility_tax      DOUBLE PRECISION DEFAULT 0.0,
            ADD COLUMN IF NOT EXISTS hurst_regime        VARCHAR(20)      DEFAULT 'RANDOM_WALK',
            ADD COLUMN IF NOT EXISTS historical_rarity_pct DOUBLE PRECISION DEFAULT 50.0,
            ADD COLUMN IF NOT EXISTS hurst_20d           DOUBLE PRECISION DEFAULT 0.5,
            ADD COLUMN IF NOT EXISTS hurst_60d           DOUBLE PRECISION DEFAULT 0.5,
            ADD COLUMN IF NOT EXISTS multi_scale_regime  VARCHAR(20)      DEFAULT 'RANDOM_WALK',
            ADD COLUMN IF NOT EXISTS ou_theta            DOUBLE PRECISION DEFAULT 0.0,
            ADD COLUMN IF NOT EXISTS ou_mu               DOUBLE PRECISION DEFAULT 0.0,
            ADD COLUMN IF NOT EXISTS ou_sigma            DOUBLE PRECISION DEFAULT 0.0,
            ADD COLUMN IF NOT EXISTS ou_half_life        DOUBLE PRECISION DEFAULT 0.0,
            ADD COLUMN IF NOT EXISTS ou_valid            BOOLEAN          DEFAULT FALSE,
            ADD COLUMN IF NOT EXISTS ou_buy_threshold    DOUBLE PRECISION DEFAULT 0.0,
            ADD COLUMN IF NOT EXISTS ou_sell_threshold   DOUBLE PRECISION DEFAULT 0.0,
            ADD COLUMN IF NOT EXISTS hmm_state           VARCHAR(20)      DEFAULT 'STRESSED_NEUTRAL',
            ADD COLUMN IF NOT EXISTS hmm_bull_prob       DOUBLE PRECISION DEFAULT 0.33,
            ADD COLUMN IF NOT EXISTS hmm_bear_prob       DOUBLE PRECISION DEFAULT 0.33,
            ADD COLUMN IF NOT EXISTS hmm_transition_bear DOUBLE PRECISION DEFAULT 0.33,
            ADD COLUMN IF NOT EXISTS conviction_score    INT              DEFAULT 50;
        """;
        jdbcTemplate.execute(addColumnsSql);

        // SELF-HEALING: Update existing NULLs or 0s to defaults
        jdbcTemplate.execute("""
            UPDATE fund_conviction_metrics
            SET conviction_score = COALESCE(NULLIF(conviction_score, 0), 50),
                composite_quant_score = COALESCE(NULLIF(composite_quant_score, 0), 50),
                yield_score = COALESCE(NULLIF(yield_score, 0), 50.0),
                risk_score = COALESCE(NULLIF(risk_score, 0), 50.0),
                value_score = COALESCE(NULLIF(value_score, 0), 50.0),
                pain_score = COALESCE(NULLIF(pain_score, 0), 50.0),
                friction_score = COALESCE(NULLIF(friction_score, 0), 50.0),
                nav_percentile_3yr = COALESCE(nav_percentile_3yr, 0.5),
                drawdown_from_ath = COALESCE(drawdown_from_ath, 0.0),
                return_z_score = COALESCE(return_z_score, 0.0),
                rolling_z_score_252 = COALESCE(rolling_z_score_252, 0.0),
                hurst_exponent = COALESCE(hurst_exponent, 0.5),
                volatility_tax = COALESCE(volatility_tax, 0.0),
                hurst_regime = COALESCE(hurst_regime, 'RANDOM_WALK'),
                historical_rarity_pct = COALESCE(historical_rarity_pct, 50.0),
                hurst_20d = COALESCE(hurst_20d, 0.5),
                hurst_60d = COALESCE(hurst_60d, 0.5),
                multi_scale_regime = COALESCE(multi_scale_regime, 'RANDOM_WALK'),
                ou_theta = COALESCE(ou_theta, 0.0),
                ou_mu = COALESCE(ou_mu, 0.0),
                ou_sigma = COALESCE(ou_sigma, 0.0),
                ou_half_life = COALESCE(ou_half_life, 0.0),
                ou_valid = COALESCE(ou_valid, FALSE),
                ou_buy_threshold = COALESCE(ou_buy_threshold, 0.0),
                ou_sell_threshold = COALESCE(ou_sell_threshold, 0.0),
                hmm_state = COALESCE(hmm_state, 'STRESSED_NEUTRAL'),
                hmm_bull_prob = COALESCE(hmm_bull_prob, 0.33),
                hmm_bear_prob = COALESCE(hmm_bear_prob, 0.33),
                hmm_transition_bear = COALESCE(hmm_transition_bear, 0.33)
            WHERE conviction_score IS NULL OR conviction_score = 0
               OR composite_quant_score IS NULL OR composite_quant_score = 0
               OR yield_score IS NULL OR yield_score = 0
        """);

        String createIndexSql = """
            CREATE INDEX IF NOT EXISTS idx_fcm_amfi_calcdate
            ON fund_conviction_metrics (amfi_code, calculation_date DESC);
        """;
        jdbcTemplate.execute(createIndexSql);

        // Track portfolio value time-series (manual table, no Entity to avoid validate failure)
        String createSnapshotTableSql = """
            CREATE TABLE IF NOT EXISTS portfolio_snapshot (
                pan VARCHAR(20) NOT NULL,
                snapshot_date DATE NOT NULL,
                total_value DOUBLE PRECISION DEFAULT 0,
                total_invested DOUBLE PRECISION DEFAULT 0,
                PRIMARY KEY (pan, snapshot_date)
            );
        """;
        jdbcTemplate.execute(createSnapshotTableSql);

        // Ensure column exists for older databases
        jdbcTemplate.execute("ALTER TABLE portfolio_snapshot ADD COLUMN IF NOT EXISTS total_invested DOUBLE PRECISION DEFAULT 0");
    }

    public long getHistoryCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM fund_history", Long.class);
    }

    public Map<String, MarketMetrics> fetchLiveMetricsMap(String pan) {
        String sql = """
            SELECT m.*, 
                   (SELECT MAX(t.transaction_date) 
                    FROM "transaction" t 
                    JOIN scheme s2 ON t.scheme_id = s2.id 
                    JOIN folio f2 ON s2.folio_id = f2.id
                    WHERE s2.amfi_code = m.amfi_code 
                    AND f2.investor_pan = ? 
                    AND t.transaction_type = 'BUY') as last_buy
            FROM fund_conviction_metrics m
            WHERE m.calculation_date = (SELECT MAX(calculation_date) FROM fund_conviction_metrics)
            AND m.amfi_code IN (
                SELECT s.amfi_code 
                FROM scheme s 
                JOIN folio f ON s.folio_id = f.id 
                WHERE f.investor_pan = ?
            )
            """;
        
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, pan, pan);
        Map<String, MarketMetrics> map = new java.util.HashMap<>();
        for (Map<String, Object> r : rows) {
            String amfi = (String) r.get("amfi_code");
            java.sql.Date lastBuySql = (java.sql.Date) r.get("last_buy");
            java.time.LocalDate lastBuy = lastBuySql != null ? lastBuySql.toLocalDate() : java.time.LocalDate.of(1970, 1, 1);

            map.put(amfi, new com.oreki.cas_injector.convictionmetrics.dto.MarketMetrics(
                getSafeInt(r.get("conviction_score")),
                getSafeDouble(r.get("sortino_ratio")),
                getSafeDouble(r.get("cvar_5")),
                getSafeDouble(r.get("win_rate")),
                getSafeDouble(r.get("max_drawdown")),
                getSafeDouble(r.get("nav_percentile_3yr")),
                getSafeDouble(r.get("drawdown_from_ath")),
                getSafeDouble(r.get("return_z_score")),
                lastBuy,
                getSafeDouble(r.get("rolling_z_score_252")),
                getSafeDouble(r.get("hurst_exponent")),
                getSafeDouble(r.get("volatility_tax")),
                String.valueOf(r.getOrDefault("hurst_regime", "RANDOM_WALK")),
                getSafeDouble(r.get("historical_rarity_pct")),
                getSafeDouble(r.get("hurst_20d")),
                getSafeDouble(r.get("hurst_60d")),
                String.valueOf(r.getOrDefault("multi_scale_regime", "RANDOM_WALK")),
                getSafeDouble(r.get("ou_theta")),
                getSafeDouble(r.get("ou_mu")),
                getSafeDouble(r.get("ou_sigma")),
                getSafeDouble(r.get("ou_half_life")),
                getSafeBoolean(r.get("ou_valid")),
                getSafeDouble(r.get("ou_buy_threshold")),
                getSafeDouble(r.get("ou_sell_threshold")),
                String.valueOf(r.getOrDefault("hmm_state", "STRESSED_NEUTRAL")),
                getSafeDouble(r.get("hmm_bull_prob")),
                getSafeDouble(r.get("hmm_bear_prob")),
                getSafeDouble(r.get("hmm_transition_bear"))
            ));
        }
        return map;
    }

    private double getSafeDouble(Object obj) { return obj == null ? 0.0 : ((Number) obj).doubleValue(); }
    private int getSafeInt(Object obj) { return obj == null ? 0 : ((Number) obj).intValue(); }
    private boolean getSafeBoolean(Object obj) {
        if (obj == null) return false;
        if (obj instanceof Boolean) return (Boolean) obj;
        if (obj instanceof Number) return ((Number) obj).intValue() != 0;
        return false;
    }

    /**
     * Method 1: Rolling 252-day Z-Score + Volatility Tax via PostgreSQL window functions.
     * This runs as a single SQL statement and writes back to fund_conviction_metrics.
     */
    public int updateRollingZScoreAndVolatilityTax() {
        String sql = """
            WITH daily_returns AS (
                SELECT
                    amfi_code,
                    nav_date,
                    nav,
                    LAG(nav) OVER (PARTITION BY amfi_code ORDER BY nav_date) AS prev_nav
                FROM fund_history
                WHERE nav_date >= CURRENT_DATE - INTERVAL '260 days'
            ),
            log_returns AS (
                SELECT
                    amfi_code,
                    nav_date,
                    LN(nav / NULLIF(prev_nav, 0)) AS log_return
                FROM daily_returns
                WHERE prev_nav > 0 AND nav > 0
            ),
            rolling_stats AS (
                SELECT
                    amfi_code,
                    nav_date,
                    log_return,
                    -- Rolling mean of last 252 trading days
                    AVG(log_return) OVER (
                        PARTITION BY amfi_code
                        ORDER BY nav_date
                        ROWS BETWEEN 251 PRECEDING AND CURRENT ROW
                    ) AS rolling_mean_252,
                    -- Rolling std of last 252 trading days
                    STDDEV_POP(log_return) OVER (
                        PARTITION BY amfi_code
                        ORDER BY nav_date
                        ROWS BETWEEN 251 PRECEDING AND CURRENT ROW
                    ) AS rolling_std_252,
                    -- Rolling variance for volatility tax (annualised)
                    VAR_POP(log_return) OVER (
                        PARTITION BY amfi_code
                        ORDER BY nav_date
                        ROWS BETWEEN 251 PRECEDING AND CURRENT ROW
                    ) AS rolling_var_252
                FROM log_returns
            ),
            latest_per_fund AS (
                SELECT DISTINCT ON (amfi_code)
                    amfi_code,
                    log_return,
                    rolling_mean_252,
                    rolling_std_252,
                    rolling_var_252,
                    -- Z-Score: (today's return - rolling mean) / rolling std
                    CASE
                        WHEN rolling_std_252 > 0
                        THEN (log_return - rolling_mean_252) / rolling_std_252
                        ELSE 0
                    END AS z_score_252,
                    -- Volatility Tax = 2 * annualised variance
                    2 * (rolling_var_252 * 252) AS volatility_tax_annual
                FROM rolling_stats
                ORDER BY amfi_code, nav_date DESC
            )
            UPDATE fund_conviction_metrics m
            SET
                rolling_z_score_252 = l.z_score_252,
                volatility_tax      = l.volatility_tax_annual
            FROM latest_per_fund l
            WHERE m.amfi_code = l.amfi_code
            AND m.calculation_date = (SELECT MAX(calculation_date) FROM fund_conviction_metrics)
            """;
        return jdbcTemplate.update(sql);
    }

    /**
     * Updates the calculated conviction score and its component breakdown.
     */
    public void updateConvictionBreakdown(int finalScore, double yield, double risk, 
                                        double value, double pain, double friction, String amfiCode) {
        String updateSql = """
            UPDATE fund_conviction_metrics 
            SET conviction_score = ?,
                yield_score = ?,
                risk_score = ?,
                value_score = ?,
                pain_score = ?,
                friction_score = ?
            WHERE LTRIM(amfi_code, '0') = LTRIM(?, '0') 
            AND calculation_date = (SELECT MAX(calculation_date) FROM fund_conviction_metrics)
        """;
        int updated = jdbcTemplate.update(updateSql, finalScore, yield, risk, value, pain, friction, amfiCode);
        if (updated == 0) {
            log.warn("⚠️ No rows updated for AMFI {} in fund_conviction_metrics. Check if row exists for latest date.", amfiCode);
        }
    }

    /**
     * Executes the native PostgreSQL Window Functions
     * to calculate quantitative metrics for every fund.
     */
    public int runNightlyMathEngine() {
        String sql = """
            INSERT INTO fund_conviction_metrics (amfi_code, calculation_date, sortino_ratio, cvar_5, win_rate, max_drawdown)

            WITH rolling_window AS (
                -- 1. Grab 3 years of data and calculate daily returns
                SELECT LTRIM(amfi_code, '0') as amfi_code, nav_date, nav AS current_nav,
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
                max_drawdown = EXCLUDED.max_drawdown,
                conviction_score = 50,
                composite_quant_score = 50,
                yield_score = 50.0,
                risk_score = 50.0,
                value_score = 50.0,
                pain_score = 50.0,
                friction_score = 50.0;
            """;
        return jdbcTemplate.update(sql);
    }

    /**
     * Calculates and updates NAV-based signals (percentile, ath drawdown, return z-score).
     */
    public int updateNavSignals() {
        String sql = """
            WITH nav_stats AS (
                SELECT
                    amfi_code,
                    MAX(nav) FILTER (WHERE nav_date >= CURRENT_DATE - INTERVAL '365 days') AS max_1yr,
                    MIN(nav) FILTER (WHERE nav_date >= CURRENT_DATE - INTERVAL '365 days') AS min_1yr,
                    MAX(nav) AS ath_nav,
                    (SELECT nav FROM fund_history h2
                     WHERE h2.amfi_code = h.amfi_code
                     ORDER BY nav_date DESC LIMIT 1) AS current_nav
                FROM fund_history h
                GROUP BY amfi_code
            ),
            percentile_and_ath AS (
                SELECT amfi_code,
                    CASE WHEN (max_1yr - min_1yr) > 0
                        THEN (current_nav - min_1yr) / (max_1yr - min_1yr)
                        ELSE 0.5 END AS nav_percentile_1yr,
                    (current_nav - ath_nav) / NULLIF(ath_nav, 0) AS drawdown_from_ath
                FROM nav_stats
            ),
            rolling_1yr_returns AS (
                SELECT amfi_code, nav_date,
                    (nav / NULLIF(LAG(nav, 252) OVER (PARTITION BY amfi_code ORDER BY nav_date), 0) - 1)
                        AS return_1yr
                FROM fund_history
                WHERE nav_date >= CURRENT_DATE - INTERVAL '4 years'
            ),
            return_z AS (
                SELECT amfi_code,
                    AVG(return_1yr) AS mean_1yr,
                    STDDEV(return_1yr) AS std_1yr,
                    (SELECT return_1yr FROM rolling_1yr_returns r2
                     WHERE r2.amfi_code = r.amfi_code AND return_1yr IS NOT NULL
                     ORDER BY nav_date DESC LIMIT 1) AS latest_1yr
                FROM rolling_1yr_returns r
                WHERE return_1yr IS NOT NULL
                GROUP BY amfi_code
            )
            UPDATE fund_conviction_metrics fcm
            SET
                nav_percentile_3yr    = p.nav_percentile_1yr,
                drawdown_from_ath     = p.drawdown_from_ath,
                return_z_score        = CASE WHEN rz.std_1yr > 0
                                            THEN (rz.latest_1yr - rz.mean_1yr) / rz.std_1yr
                                            ELSE 0 END
            FROM percentile_and_ath p
            LEFT JOIN return_z rz ON p.amfi_code = rz.amfi_code
            WHERE fcm.amfi_code = p.amfi_code
              AND fcm.calculation_date = (SELECT MAX(calculation_date) FROM fund_conviction_metrics);
        """;
        return jdbcTemplate.update(sql);
    }

    /**
     * Fetches details required for scoring for a specific investor.
     */
    public List<Map<String, Object>> findMetricsForScoring(String investorPan) {
        String fetchSql = """
           SELECT m.amfi_code, m.sortino_ratio, m.max_drawdown, m.calculation_date,
                  m.nav_percentile_3yr, m.drawdown_from_ath, m.return_z_score,
                  m.composite_quant_score,
                  s.asset_category
           FROM fund_conviction_metrics m
           JOIN scheme s ON LTRIM(m.amfi_code, '0') = LTRIM(s.amfi_code, '0')
           WHERE m.calculation_date = (SELECT MAX(calculation_date) FROM fund_conviction_metrics)
           AND LTRIM(m.amfi_code, '0') IN (
               SELECT LTRIM(s2.amfi_code, '0')
               FROM scheme s2 
               JOIN folio fol ON s2.folio_id = fol.id 
               WHERE fol.investor_pan = ?
           )
        """;
        return jdbcTemplate.queryForList(fetchSql, investorPan);
    }

    /**
     * Updates the calculated conviction score.
     */
    public void updateFinalConvictionScore(int finalScore, String amfiCode) {
        String updateSql = """
            UPDATE fund_conviction_metrics 
            SET conviction_score = ?
            WHERE LTRIM(amfi_code, '0') = LTRIM(?, '0') 
            AND calculation_date = (SELECT MAX(calculation_date) FROM fund_conviction_metrics)
        """;
        int updated = jdbcTemplate.update(updateSql, finalScore, amfiCode);
        if (updated == 0) {
            log.warn("⚠️ No final score updated for AMFI {}. Check calculation_date.", amfiCode);
        }
    }

    public List<Map<String, Object>> findAllMap() {
        String sql = "SELECT * FROM fund_conviction_metrics WHERE calculation_date = (SELECT MAX(calculation_date) FROM fund_conviction_metrics)";
        return jdbcTemplate.queryForList(sql);
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
