-- One row per (account, symbol) a client has ever bought — cost_basis is the weighted-average
-- price paid, updated on every buy fill; sells reduce quantity but never change cost_basis
-- (standard average-cost accounting). Real numbers, not fabricated.
CREATE TABLE positions (
    position_id UUID PRIMARY KEY,
    account_id  UUID            NOT NULL REFERENCES accounts (account_id),
    client_id   VARCHAR(64)     NOT NULL,
    symbol      VARCHAR(16)     NOT NULL,
    quantity    NUMERIC(20, 8)  NOT NULL CHECK (quantity >= 0),
    avg_cost    NUMERIC(20, 8)  NOT NULL CHECK (avg_cost >= 0),
    updated_at  TIMESTAMPTZ     NOT NULL
);

CREATE UNIQUE INDEX idx_positions_account_symbol ON positions (account_id, symbol);
CREATE INDEX idx_positions_client_id ON positions (client_id);
