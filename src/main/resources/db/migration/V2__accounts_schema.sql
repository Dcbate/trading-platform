CREATE TABLE accounts (
    account_id   UUID PRIMARY KEY,
    client_id    VARCHAR(64)     NOT NULL,
    account_type VARCHAR(20)     NOT NULL,
    currency     VARCHAR(3)      NOT NULL,
    balance      NUMERIC(20, 8)  NOT NULL CHECK (balance >= 0),
    status       VARCHAR(20)     NOT NULL,
    created_at   TIMESTAMPTZ     NOT NULL
);

CREATE INDEX idx_accounts_client_id ON accounts (client_id);
