-- V5__Harden_Read_Model_Precision.sql
-- Create portfolio_dashboard_read_model if it doesn't exist
CREATE TABLE IF NOT EXISTS portfolio_dashboard_read_model (
    investor_pan VARCHAR(10) PRIMARY KEY,
    total_value NUMERIC(18, 2),
    total_cost NUMERIC(18, 2),
    total_gains NUMERIC(18, 2),
    ltcg_gains NUMERIC(18, 2),
    stcg_gains NUMERIC(18, 2),
    days_to_next_ltcg INT,
    portfolio_age_days INT,
    estimated_tax NUMERIC(18, 2),
    content TEXT,
    last_updated_at TIMESTAMP
);

-- Alter columns to ensure numeric(18, 2) in case table already exists
ALTER TABLE portfolio_dashboard_read_model ALTER COLUMN total_value TYPE NUMERIC(18, 2);
ALTER TABLE portfolio_dashboard_read_model ALTER COLUMN total_cost TYPE NUMERIC(18, 2);
ALTER TABLE portfolio_dashboard_read_model ALTER COLUMN total_gains TYPE NUMERIC(18, 2);
ALTER TABLE portfolio_dashboard_read_model ALTER COLUMN ltcg_gains TYPE NUMERIC(18, 2);
ALTER TABLE portfolio_dashboard_read_model ALTER COLUMN stcg_gains TYPE NUMERIC(18, 2);
ALTER TABLE portfolio_dashboard_read_model ALTER COLUMN estimated_tax TYPE NUMERIC(18, 2);

-- Create index_fundamentals table if it doesn't exist (in case of fresh db bootstrap or missing table)
CREATE TABLE IF NOT EXISTS index_fundamentals (
    id BIGSERIAL PRIMARY KEY,
    index_name VARCHAR(100),
    date DATE,
    pe DOUBLE PRECISION,
    pb DOUBLE PRECISION,
    div_yield DOUBLE PRECISION,
    closing_price NUMERIC(18, 4),
    UNIQUE (index_name, date)
);

-- Alter index_fundamentals closing_price column to ensure numeric(18, 4)
ALTER TABLE index_fundamentals ALTER COLUMN closing_price TYPE NUMERIC(18, 4);

-- Add deleted column to scheme table for SoftDelete support
ALTER TABLE scheme ADD COLUMN IF NOT EXISTS deleted BOOLEAN DEFAULT FALSE;
