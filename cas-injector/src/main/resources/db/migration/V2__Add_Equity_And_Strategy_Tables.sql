-- V2__Add_Equity_And_Strategy_Tables.sql
-- Create tables for strategy targets, stock prices, and stock lots

-- Strategy Target table for asynchronous strategy mirroring
CREATE TABLE strategy_target (
    id BIGSERIAL PRIMARY KEY,
    investor_pan VARCHAR(10),
    amfi_code VARCHAR(10),
    target_allocation_pct DECIMAL(5,2),
    strategy_type VARCHAR(50) DEFAULT 'CORE',
    rebalance_band DECIMAL(5,2) DEFAULT 5.00,
    last_updated TIMESTAMP,
    source VARCHAR(20) DEFAULT 'GOOGLE_SHEETS',
    UNIQUE (investor_pan, amfi_code)
);

-- End of Day stock prices for direct equities
CREATE TABLE stock_price_eod (
    isin VARCHAR(12),
    trade_date DATE,
    close_price DECIMAL(18,4),
    volume BIGINT,
    PRIMARY KEY (isin, trade_date)
);

-- Individual stock lots for direct equities
CREATE TABLE stock_lot (
    id BIGSERIAL PRIMARY KEY,
    investor_pan VARCHAR(10),
    isin VARCHAR(12),
    buy_date DATE,
    quantity DECIMAL(18,4),
    cost_basis DECIMAL(18,4),
    status VARCHAR(20) DEFAULT 'OPEN'
);
