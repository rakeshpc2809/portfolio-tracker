package com.oreki.cas_injector.dashboard.repository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
@Slf4j
public class PortfolioSummaryRepository {

    private final JdbcTemplate jdbcTemplate;

    public void init() {
        log.info("🚀 Checking Materialized View: mv_portfolio_summary");
        
        // 🧬 Transition Logic: 
        // If mv_portfolio_summary exists as a regular table (from previous Hibernate runs), DROP it.
        try {
            String checkSql = """
                SELECT relkind FROM pg_class c 
                JOIN pg_namespace n ON n.oid = c.relnamespace 
                WHERE n.nspname = 'public' AND c.relname = 'mv_portfolio_summary'
            """;
            String relkind = jdbcTemplate.queryForObject(checkSql, String.class);
            if (relkind != null && relkind.equals("r")) {
                log.warn("⚠️ mv_portfolio_summary exists as a TABLE. Dropping to convert to Materialized View.");
                jdbcTemplate.execute("DROP TABLE mv_portfolio_summary CASCADE");
            }
        } catch (Exception e) {
            // Not found or error, safe to proceed
        }

        String createViewSql = """
            CREATE MATERIALIZED VIEW IF NOT EXISTS mv_portfolio_summary AS
            SELECT 
                t.id AS txn_id,
                t.txn_hash,
                t.transaction_date,
                t.transaction_type,
                t.amount,
                t.units,
                t.description,
                s.id AS scheme_id,
                s.name AS scheme_name,
                s.isin,
                s.amfi_code,
                s.asset_category,
                f.id AS folio_id,
                f.folio_number,
                f.amc,
                i.pan AS investor_pan,
                i.name AS investor_name
            FROM "transaction" t
            JOIN scheme s ON t.scheme_id = s.id
            JOIN folio f ON s.folio_id = f.id
            JOIN investor i ON f.investor_pan = i.pan;
        """;
        
        try {
            jdbcTemplate.execute(createViewSql);
            jdbcTemplate.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_mv_portfolio_summary_txn_id ON mv_portfolio_summary (txn_id)");
            log.info("✅ Materialized View mv_portfolio_summary ready.");
        } catch (Exception e) {
            log.error("❌ Failed to create/refresh Materialized View: {}", e.getMessage());
        }
    }

    public void refreshView() {
        log.info("🔄 Refreshing Materialized View: mv_portfolio_summary");
        try {
            jdbcTemplate.execute("REFRESH MATERIALIZED VIEW CONCURRENTLY mv_portfolio_summary;");
            log.info("✅ Materialized View refreshed.");
        } catch (Exception e) {
            log.warn("⚠️ Concurrent refresh failed or view not ready: {}", e.getMessage());
        }
    }
}
