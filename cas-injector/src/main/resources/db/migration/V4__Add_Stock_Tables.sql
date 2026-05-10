-- Master stock record
CREATE TABLE stock (
    id              BIGSERIAL PRIMARY KEY,
    ticker          VARCHAR(50)  NOT NULL,
    trading_symbol  VARCHAR(255) NOT NULL,       -- 'RELIANCE-EQ' or long ETF name
    security_id     VARCHAR(50),                 -- INDstocks internal security_id
    isin            VARCHAR(12)  UNIQUE,
    exchange        VARCHAR(20)  DEFAULT 'NSE',
    company_name    VARCHAR(255),
    sector          VARCHAR(100),
    folio_id        BIGINT REFERENCES folio(id) ON DELETE CASCADE,
    created_at      TIMESTAMPTZ  DEFAULT NOW(),
    UNIQUE (ticker, exchange)
);

-- Raw transaction log (imported from CSV)
CREATE TABLE stock_transaction (
    id                  BIGSERIAL PRIMARY KEY,
    stock_id            BIGINT        REFERENCES stock(id) ON DELETE CASCADE,
    transaction_date    DATE          NOT NULL,
    transaction_type    VARCHAR(20)   NOT NULL,  -- BUY | SELL | BONUS | SPLIT | RIGHTS
    quantity            NUMERIC(15,4) NOT NULL,
    price_per_share     NUMERIC(15,4) NOT NULL,
    brokerage           NUMERIC(10,4) DEFAULT 0,
    stt                 NUMERIC(10,4) DEFAULT 0,
    stamp_duty          NUMERIC(10,4) DEFAULT 0,
    other_charges       NUMERIC(10,4) DEFAULT 0,
    total_amount        NUMERIC(15,4) NOT NULL,
    source              VARCHAR(20)   DEFAULT 'INDMONEY_CSV',
    external_trade_id   VARCHAR(50),
    notes               TEXT,
    created_at          TIMESTAMPTZ   DEFAULT NOW()
);

-- FIFO tax lots (auto-generated from transactions)
CREATE TABLE stock_tax_lot (
    id                      BIGSERIAL PRIMARY KEY,
    stock_id                BIGINT        REFERENCES stock(id) ON DELETE CASCADE,
    buy_transaction_id      BIGINT        REFERENCES stock_transaction(id),
    buy_date                DATE          NOT NULL,
    original_qty            NUMERIC(15,4) NOT NULL,
    remaining_qty           NUMERIC(15,4) NOT NULL,
    cost_basis_per_share    NUMERIC(15,4) NOT NULL,  -- incl. brokerage/STT pro-rated
    adjusted_cost_basis     NUMERIC(15,4),           -- post-bonus/split adjusted
    status                  VARCHAR(10)   DEFAULT 'OPEN',  -- OPEN | PARTIAL | CLOSED
    created_at              TIMESTAMPTZ   DEFAULT NOW()
);

-- EOD price history (from INDstocks API + NSE bhavcopy)
CREATE TABLE stock_price_eod (
    ticker          VARCHAR(20),
    price_date      DATE,
    open_price      NUMERIC(15,4),
    high_price      NUMERIC(15,4),
    low_price       NUMERIC(15,4),
    close_price     NUMERIC(15,4) NOT NULL,
    volume          BIGINT,
    PRIMARY KEY (ticker, price_date)
);

-- Capital gain audit for closed lots
CREATE TABLE stock_capital_gain (
    id                  BIGSERIAL PRIMARY KEY,
    stock_id            BIGINT        REFERENCES stock(id),
    buy_lot_id          BIGINT        REFERENCES stock_tax_lot(id),
    sell_transaction_id BIGINT        REFERENCES stock_transaction(id),
    buy_date            DATE          NOT NULL,
    sell_date           DATE          NOT NULL,
    quantity_sold       NUMERIC(15,4) NOT NULL,
    buy_price           NUMERIC(15,4) NOT NULL,
    sell_price          NUMERIC(15,4) NOT NULL,
    realized_gain       NUMERIC(15,4) NOT NULL,
    gain_type           VARCHAR(10),   -- LTCG | STCG
    tax_rate            NUMERIC(6,4),
    tax_estimate        NUMERIC(15,4),
    fy                  VARCHAR(10),   -- '2024-25'
    created_at          TIMESTAMPTZ    DEFAULT NOW()
);

-- Indexes (critical for performance)
CREATE INDEX idx_stock_tx_stock_date     ON stock_transaction(stock_id, transaction_date ASC);
CREATE INDEX idx_stock_lot_open          ON stock_tax_lot(stock_id, buy_date ASC) WHERE status IN ('OPEN','PARTIAL');
CREATE INDEX idx_stock_price_ticker_date ON stock_price_eod(ticker, price_date DESC);
CREATE INDEX idx_stock_isin              ON stock(isin);
CREATE INDEX idx_stock_gain_fy           ON stock_capital_gain(fy, gain_type);
