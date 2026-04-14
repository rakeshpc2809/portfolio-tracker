package com.oreki.cas_injector.convictionmetrics.repository;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.time.LocalDate;

import com.oreki.cas_injector.convictionmetrics.dto.MarketMetrics;
import com.oreki.cas_injector.core.utils.CommonUtils;
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
    public void ensureColumnsExist() {
        // 1. Global Data Sanitization
        try {
            log.info("🧹 Running global AMFI code sanitization...");
            jdbcTemplate.execute("UPDATE scheme SET amfi_code = LTRIM(amfi_code, '0') WHERE amfi_code LIKE '0%'");
            jdbcTemplate.execute("UPDATE fund_history SET amfi_code = LTRIM(amfi_code, '0') WHERE amfi_code LIKE '0%'");
        } catch (Exception e) {
            log.warn("⚠️ Sanitization skipped (tables might be empty): {}", e.getMessage());
        }

        String createTableSql = """
            CREATE TABLE IF NOT EXISTS fund_conviction_metrics (
                amfi_code VARCHAR(255) NOT NULL,
                calculation_date DATE NOT NULL,
                sortino_ratio DOUBLE PRECISION,
                cvar_5 DOUBLE PRECISION,
                win_rate DOUBLE PRECISION,
                max_drawdown DOUBLE PRECISION,
                conviction_score INT DEFAULT 50,
                PRIMARY KEY (amfi_code, calculation_date)
            );
        """;
        jdbcTemplate.execute(createTableSql);

        // Individual ALTERS for maximum resilience across Postgres versions
        String[] columns = {
            "nav_percentile_3yr DOUBLE PRECISION DEFAULT 0.5",
            "drawdown_from_ath DOUBLE PRECISION DEFAULT 0.0",
            "return_z_score DOUBLE PRECISION DEFAULT 0.0",
            "yield_score DOUBLE PRECISION DEFAULT 50.0",
            "risk_score DOUBLE PRECISION DEFAULT 50.0",
            "value_score DOUBLE PRECISION DEFAULT 50.0",
            "pain_score DOUBLE PRECISION DEFAULT 50.0",
            "friction_score DOUBLE PRECISION DEFAULT 50.0",
            "composite_quant_score INT DEFAULT 50",
            "bucket_peer_count INT DEFAULT 0",
            "rolling_z_score_252 DOUBLE PRECISION DEFAULT 0.0",
            "hurst_exponent DOUBLE PRECISION DEFAULT 0.5",
            "volatility_tax DOUBLE PRECISION DEFAULT 0.0",
            "hurst_regime VARCHAR(20) DEFAULT 'RANDOM_WALK'",
            "historical_rarity_pct DOUBLE PRECISION DEFAULT 50.0",
            "hurst_20d DOUBLE PRECISION DEFAULT 0.5",
            "hurst_60d DOUBLE PRECISION DEFAULT 0.5",
            "multi_scale_regime VARCHAR(20) DEFAULT 'RANDOM_WALK'",
            "ou_theta DOUBLE PRECISION DEFAULT 0.0",
            "ou_mu DOUBLE PRECISION DEFAULT 0.0",
            "ou_sigma DOUBLE PRECISION DEFAULT 0.0",
            "ou_half_life DOUBLE PRECISION DEFAULT 0.0",
            "ou_valid BOOLEAN DEFAULT FALSE",
            "ou_buy_threshold DOUBLE PRECISION DEFAULT 0.0",
            "ou_sell_threshold DOUBLE PRECISION DEFAULT 0.0",
            "hmm_state VARCHAR(20) DEFAULT 'STRESSED_NEUTRAL'",
            "hmm_bull_prob DOUBLE PRECISION DEFAULT 0.33",
            "hmm_bear_prob DOUBLE PRECISION DEFAULT 0.33",
            "hmm_transition_bear DOUBLE PRECISION DEFAULT 0.33",
            "last_python_update TIMESTAMP"
        };

        for (String col : columns) {
            try {
                jdbcTemplate.execute("ALTER TABLE fund_conviction_metrics ADD COLUMN IF NOT EXISTS " + col);
            } catch (Exception e) {
                log.debug("Column already exists or error adding: {}", col);
            }
        }

        // SELF-HEALING: Update existing NULLs or 0s to defaults
        try {
            jdbcTemplate.execute("""
                UPDATE fund_conviction_metrics
                SET conviction_score = COALESCE(NULLIF(conviction_score, 0), 50),
                    composite_quant_score = COALESCE(NULLIF(composite_quant_score, 0), 50),
                    yield_score = COALESCE(NULLIF(yield_score, 0), 50.0),
                    risk_score = COALESCE(NULLIF(risk_score, 0), 50.0),
                    value_score = COALESCE(NULLIF(value_score, 0), 50.0),
                    pain_score = COALESCE(NULLIF(pain_score, 0), 50.0),
                    friction_score = COALESCE(NULLIF(friction_score, 0), 50.0)
                WHERE conviction_score IS NULL OR conviction_score = 0
                   OR composite_quant_score IS NULL OR composite_quant_score = 0
                   OR yield_score IS NULL OR yield_score = 0
            """);
        } catch (Exception e) {
            log.warn("⚠️ Self-healing update failed: {}", e.getMessage());
        }

        try {
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_fcm_amfi_calcdate ON fund_conviction_metrics (amfi_code, calculation_date DESC)");
        } catch (Exception e) {
            log.debug("Index already exists: idx_fcm_amfi_calcdate");
        }

        // Snapshot table
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS portfolio_snapshot (
                pan VARCHAR(20) NOT NULL,
                snapshot_date DATE NOT NULL,
                total_value DOUBLE PRECISION DEFAULT 0,
                total_invested DOUBLE PRECISION DEFAULT 0,
                PRIMARY KEY (pan, snapshot_date)
            )
        """);
        
        try {
            jdbcTemplate.execute("ALTER TABLE portfolio_snapshot ADD COLUMN IF NOT EXISTS total_invested DOUBLE PRECISION DEFAULT 0");
        } catch (Exception e) {}
    }

    public long getHistoryCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM fund_history", Long.class);
    }

    public Map<String, MarketMetrics> fetchLiveMetricsMap(String pan) {
        // 1. Find all funds held by this investor
        String heldAmfiSql = """
            SELECT DISTINCT LTRIM(amfi_code, '0') as amfi 
            FROM scheme s 
            JOIN folio f ON s.folio_id = f.id 
            WHERE f.investor_pan = ?
        """;
        List<String> heldAmfis = jdbcTemplate.queryForList(heldAmfiSql, String.class, pan);
        
        Map<String, MarketMetrics> resultMap = new HashMap<>();
        if (heldAmfis.isEmpty()) return resultMap;

        // 2. Fetch latest metrics from FCM
        String metricsSql = """
            SELECT m.*, 
                   (SELECT MAX(t.transaction_date) 
                    FROM "transaction" t 
                    JOIN scheme s2 ON t.scheme_id = s2.id 
                    JOIN folio f2 ON s2.folio_id = f2.id
                    WHERE LTRIM(s2.amfi_code, '0') = LTRIM(m.amfi_code, '0') 
                    AND f2.investor_pan = ? 
                    AND t.transaction_type = 'BUY') as last_buy,
                   (SELECT MAX(t.transaction_date) 
                    FROM "transaction" t 
                    JOIN scheme s2 ON t.scheme_id = s2.id 
                    JOIN folio f2 ON s2.folio_id = f2.id
                    WHERE LTRIM(s2.amfi_code, '0') = LTRIM(m.amfi_code, '0') 
                    AND f2.investor_pan = ? 
                    AND t.transaction_type = 'SELL') as last_sell
            FROM fund_conviction_metrics m
            WHERE m.calculation_date = (SELECT MAX(calculation_date) FROM fund_conviction_metrics)
            AND LTRIM(m.amfi_code, '0') IN (""";
        
        // Build IN clause
        StringBuilder inClause = new StringBuilder();
        for (int i = 0; i < heldAmfis.size(); i++) {
            inClause.append("?");
            if (i < heldAmfis.size() - 1) inClause.append(",");
        }
        metricsSql += inClause.toString() + ")";

        Object[] params = new Object[heldAmfis.size() + 2];
        params[0] = pan; // for last_buy subquery
        params[1] = pan; // for last_sell subquery
        for (int i = 0; i < heldAmfis.size(); i++) {
            params[i + 2] = heldAmfis.get(i);
        }

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(metricsSql, params);
        Map<String, MarketMetrics> foundMetrics = MarketMetrics.fromRows(rows);

        // 3. Fill map, providing defaults for funds with no metrics yet
        for (String amfi : heldAmfis) {
            String cleanAmfi = CommonUtils.SANITIZE_AMFI.apply(amfi);
            if (foundMetrics.containsKey(cleanAmfi)) {
                resultMap.put(cleanAmfi, foundMetrics.get(cleanAmfi));
            } else {
                // Return neutral defaults so engine doesn't crash or skip
                resultMap.put(cleanAmfi, MarketMetrics.fromLegacy(50, 0, 0, 0, 0, 0.5, 0, 0, LocalDate.of(1970, 1, 1)));
            }
        }

        return resultMap;
    }

    public List<Map<String, Object>> findMetricsForInvestor(String investorPan) {
        String sql = """
            SELECT m.* FROM fund_conviction_metrics m
            WHERE LTRIM(m.amfi_code, '0') IN (
                SELECT LTRIM(s.amfi_code, '0') FROM scheme s
                JOIN folio f ON s.folio_id = f.id
                WHERE f.investor_pan = ?
            )
            AND m.calculation_date = (SELECT MAX(calculation_date) FROM fund_conviction_metrics)
            """;
        return jdbcTemplate.queryForList(sql, investorPan);
    }

    public List<Map<String, Object>> findAllMap() {
        String sql = "SELECT * FROM fund_conviction_metrics WHERE calculation_date = (SELECT MAX(calculation_date) FROM fund_conviction_metrics)";
        return jdbcTemplate.queryForList(sql);
    }

    public void updateConvictionBreakdown(int total, double yield, double risk, double value, double pain, double friction, String amfi) {
        jdbcTemplate.update("""
            UPDATE fund_conviction_metrics 
            SET conviction_score = ?,
                yield_score = ?,
                risk_score = ?,
                value_score = ?,
                pain_score = ?,
                friction_score = ?
            WHERE LTRIM(amfi_code, '0') = LTRIM(?, '0')
            AND calculation_date = (SELECT MAX(calculation_date) FROM fund_conviction_metrics)
            """, total, yield, risk, value, pain, friction, amfi);
    }

    public int runNightlyMathEngine() {
        String sql = """
            INSERT INTO fund_conviction_metrics (amfi_code, calculation_date, sortino_ratio, cvar_5, win_rate, max_drawdown)

            WITH rolling_window AS (
                SELECT LTRIM(amfi_code, '0') as amfi_code, nav_date, nav AS current_nav,
                       LAG(nav) OVER (PARTITION BY amfi_code ORDER BY nav_date) AS previous_nav
                FROM fund_history
                WHERE nav_date >= CURRENT_DATE - INTERVAL '3 years'
            ),
            daily_stats AS (
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
                SELECT amfi_code,
                       PERCENTILE_CONT(0.05) WITHIN GROUP (ORDER BY daily_return) AS var_5
                FROM daily_stats
                GROUP BY amfi_code
            ),
            cvar_calc AS (
                SELECT d.amfi_code,
                       AVG(d.daily_return) AS cvar_5
                FROM daily_stats d
                JOIN percentile_calc p ON d.amfi_code = p.amfi_code
                WHERE d.daily_return <= p.var_5
                GROUP BY d.amfi_code
            ),
            drawdown_and_winrate AS (
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
                SELECT amfi_code,
                       AVG(daily_return) * 252 AS annualized_return,
                       SQRT(AVG(POWER(downside_excess, 2))) * SQRT(252) AS annualized_downside_dev
                FROM daily_stats
                GROUP BY amfi_code
            )

            SELECT 
                a.amfi_code, 
                CURRENT_DATE AS calculation_date,
                CASE 
                    WHEN a.annualized_downside_dev = 0 THEN 0 
                    ELSE ROUND(CAST((a.annualized_return - 0.07) / a.annualized_downside_dev AS NUMERIC), 4)
                END AS sortino_ratio,
                ROUND(CAST(c.cvar_5 * 100 AS NUMERIC), 4) AS cvar_5,
                ROUND(CAST(dw.win_rate AS NUMERIC), 2) AS win_rate,
                ROUND(CAST(dw.max_drawdown * 100 AS NUMERIC), 4) AS max_drawdown
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
                    AVG(log_return) OVER (
                        PARTITION BY amfi_code
                        ORDER BY nav_date
                        ROWS BETWEEN 251 PRECEDING AND CURRENT ROW
                    ) AS rolling_mean_252,
                    STDDEV_POP(log_return) OVER (
                        PARTITION BY amfi_code
                        ORDER BY nav_date
                        ROWS BETWEEN 251 PRECEDING AND CURRENT ROW
                    ) AS rolling_std_252,
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
                    CASE
                        WHEN rolling_std_252 > 0
                        THEN (log_return - rolling_mean_252) / rolling_std_252
                        ELSE 0
                    END AS z_score_252,
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
            JOIN scheme s ON m.amfi_code = s.amfi_code 
            JOIN folio f ON s.folio_id = f.id
            WHERE f.investor_pan = ?
            AND m.calculation_date = (SELECT MAX(calculation_date) FROM fund_conviction_metrics)
            """;
        return jdbcTemplate.queryForList(sql, pan);
    }
}
