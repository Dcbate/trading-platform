CREATE TABLE payments (
    payment_id        UUID PRIMARY KEY,
    client_id         VARCHAR(64)     NOT NULL,
    source_account_id UUID            NOT NULL REFERENCES accounts (account_id),
    amount            NUMERIC(20, 8)  NOT NULL CHECK (amount > 0),
    status            VARCHAR(20)     NOT NULL,
    idempotency_key   VARCHAR(128)    NOT NULL UNIQUE,
    country           VARCHAR(2)      NOT NULL,
    created_at        TIMESTAMPTZ     NOT NULL
);

CREATE INDEX idx_payments_client_id ON payments (client_id);
CREATE INDEX idx_payments_source_account_id ON payments (source_account_id);

CREATE TABLE ledger_entries (
    entry_id    UUID PRIMARY KEY,
    payment_id  UUID            NOT NULL REFERENCES payments (payment_id),
    account_id  VARCHAR(64)     NOT NULL,
    entry_type  VARCHAR(10)     NOT NULL CHECK (entry_type IN ('DEBIT', 'CREDIT')),
    amount      NUMERIC(20, 8)  NOT NULL CHECK (amount > 0),
    created_at  TIMESTAMPTZ     NOT NULL
);

CREATE INDEX idx_ledger_entries_payment_id ON ledger_entries (payment_id);

CREATE TABLE settlements (
    settlement_id   UUID PRIMARY KEY,
    payment_id      UUID            NOT NULL REFERENCES payments (payment_id),
    status          VARCHAR(20)     NOT NULL,
    attempt_count   INT             NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ     NOT NULL,
    updated_at      TIMESTAMPTZ     NOT NULL
);

CREATE INDEX idx_settlements_payment_id ON settlements (payment_id);

CREATE TABLE fraud_flags (
    flag_id       UUID PRIMARY KEY,
    payment_id    UUID            NOT NULL REFERENCES payments (payment_id),
    risk_level    VARCHAR(20)     NOT NULL,
    reason        VARCHAR(1024)   NOT NULL,
    action_taken  VARCHAR(20)     NOT NULL,
    created_at    TIMESTAMPTZ     NOT NULL
);

CREATE INDEX idx_fraud_flags_payment_id ON fraud_flags (payment_id);

CREATE TABLE notifications (
    notification_id UUID PRIMARY KEY,
    payment_id      UUID            NOT NULL REFERENCES payments (payment_id),
    type            VARCHAR(30)     NOT NULL,
    delivery_status VARCHAR(20)     NOT NULL,
    retry_count     INT             NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ     NOT NULL
);

CREATE INDEX idx_notifications_payment_id ON notifications (payment_id);

CREATE TABLE reconciliation_alerts (
    alert_id        UUID PRIMARY KEY,
    payment_id      UUID            NOT NULL REFERENCES payments (payment_id),
    expected_amount NUMERIC(20, 8)  NOT NULL,
    actual_amount   NUMERIC(20, 8)  NOT NULL,
    discrepancy     NUMERIC(20, 8)  NOT NULL,
    created_at      TIMESTAMPTZ     NOT NULL
);

CREATE INDEX idx_reconciliation_alerts_payment_id ON reconciliation_alerts (payment_id);
