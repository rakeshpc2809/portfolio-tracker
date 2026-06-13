-- Create the materialized view mv_portfolio_summary by joining transactions, schemes, folios, and investors
CREATE MATERIALIZED VIEW IF NOT EXISTS mv_portfolio_summary AS
SELECT 
    t.id AS txn_id,
    t.txn_hash AS txn_hash,
    t.transaction_date AS transaction_date,
    t.transaction_type AS transaction_type,
    t.amount AS amount,
    t.units AS units,
    t.description AS description,
    s.id AS scheme_id,
    s.name AS scheme_name,
    s.isin AS isin,
    s.amfi_code AS amfi_code,
    s.asset_category AS asset_category,
    f.id AS folio_id,
    f.folio_number AS folio_number,
    f.amc AS amc,
    i.pan AS investor_pan,
    i.name AS investor_name
FROM transaction t
JOIN scheme s ON t.scheme_id = s.id
JOIN folio f ON s.folio_id = f.id
JOIN investor i ON f.investor_pan = i.pan
WHERE t.deleted = false AND f.deleted = false;

-- Create unique index to allow CONCURRENT refreshes of the materialized view
CREATE UNIQUE INDEX IF NOT EXISTS idx_mv_portfolio_summary_txn_id ON mv_portfolio_summary (txn_id);
