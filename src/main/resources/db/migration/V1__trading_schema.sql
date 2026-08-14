CREATE TABLE orders (
    order_id      UUID PRIMARY KEY,
    client_id     VARCHAR(64)     NOT NULL,
    currency_pair VARCHAR(16)     NOT NULL,
    side          VARCHAR(4)      NOT NULL CHECK (side IN ('BUY', 'SELL')),
    quantity      NUMERIC(20, 8)  NOT NULL CHECK (quantity > 0),
    price         NUMERIC(20, 8)  NOT NULL CHECK (price > 0),
    status        VARCHAR(20)     NOT NULL,
    created_at    TIMESTAMPTZ     NOT NULL,
    filled_at     TIMESTAMPTZ
);

CREATE INDEX idx_orders_client_id ON orders (client_id);
CREATE INDEX idx_orders_currency_pair_status ON orders (currency_pair, status);

CREATE TABLE trades (
    trade_id      UUID PRIMARY KEY,
    buy_order_id  UUID            NOT NULL REFERENCES orders (order_id),
    sell_order_id UUID            NOT NULL REFERENCES orders (order_id),
    currency_pair VARCHAR(16)     NOT NULL,
    quantity      NUMERIC(20, 8)  NOT NULL CHECK (quantity > 0),
    price         NUMERIC(20, 8)  NOT NULL CHECK (price > 0),
    created_at    TIMESTAMPTZ     NOT NULL
);

CREATE INDEX idx_trades_currency_pair ON trades (currency_pair);
CREATE INDEX idx_trades_buy_order_id ON trades (buy_order_id);
CREATE INDEX idx_trades_sell_order_id ON trades (sell_order_id);

CREATE TABLE risk_logs (
    log_id      UUID PRIMARY KEY,
    order_id    UUID            NOT NULL REFERENCES orders (order_id),
    client_id   VARCHAR(64)     NOT NULL,
    risk_level  VARCHAR(20)     NOT NULL,
    reason      VARCHAR(1024)   NOT NULL,
    created_at  TIMESTAMPTZ     NOT NULL
);

CREATE INDEX idx_risk_logs_client_id ON risk_logs (client_id);
