package com.oreki.cas_injector.dashboard.repository;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
@Slf4j
public class PortfolioSummaryRepository {

    private final JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void init() {
        log.info("🚀 Initializing Materialized View: mv_portfolio_summary");
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
            FROM transaction t
            JOIN scheme s ON t.scheme_id = s.id
            JOIN folio f ON s.folio_id = f.id
            JOIN investor i ON f.investor_pan = i.pan;
        """;
        jdbcTemplate.execute(createViewSql);

        String createIndexSql = """
            CREATE UNIQUE INDEX IF NOT EXISTS idx_mv_portfolio_summary_txn_id ON mv_portfolio_summary (txn_id);
        """;
        jdbcTemplate.execute(createIndexSql);
        log.info("✅ Materialized View mv_portfolio_summary initialized.");
    }

    public void refreshView() {
        log.info("🔄 Refreshing Materialized View: mv_portfolio_summary");
        jdbcTemplate.execute("REFRESH MATERIALIZED VIEW CONCURRENTLY mv_portfolio_summary;");
        log.info("✅ Materialized View mv_portfolio_summary refreshed.");
    }
}
