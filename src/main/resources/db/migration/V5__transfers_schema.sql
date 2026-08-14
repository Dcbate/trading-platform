CREATE TABLE transfers (
    transfer_id     UUID PRIMARY KEY,
    from_account_id UUID            NOT NULL REFERENCES accounts (account_id),
    to_account_id   UUID            NOT NULL REFERENCES accounts (account_id),
    from_client_id  VARCHAR(64)     NOT NULL,
    to_client_id    VARCHAR(64)     NOT NULL,
    amount          NUMERIC(20, 8)  NOT NULL CHECK (amount > 0),
    status          VARCHAR(20)     NOT NULL,
    created_at      TIMESTAMPTZ     NOT NULL
);

CREATE INDEX idx_transfers_from_account_id ON transfers (from_account_id);
CREATE INDEX idx_transfers_to_account_id ON transfers (to_account_id);
