-- Backs the bank statement feature. Deposits/withdrawals/conversions/closures and loan
-- origination/repayments were previously only published to Kafka for audit — genuine balance
-- history a client would expect to see was unqueryable. These two tables persist exactly what
-- was already being published, so nothing about the event shape changes, it's just also a row now.

CREATE TABLE account_activity (
    activity_id         UUID PRIMARY KEY,
    account_id          UUID            NOT NULL REFERENCES accounts (account_id),
    client_id           VARCHAR(64)     NOT NULL,
    type                VARCHAR(20)     NOT NULL,
    amount              NUMERIC(20, 8)  NOT NULL,
    balance_after       NUMERIC(20, 8)  NOT NULL,
    related_account_id  UUID,
    rate                NUMERIC(20, 10),
    occurred_at         TIMESTAMPTZ     NOT NULL
);

CREATE INDEX idx_account_activity_client_id ON account_activity (client_id);

CREATE TABLE loan_activity (
    activity_id            UUID PRIMARY KEY,
    loan_id                UUID            NOT NULL REFERENCES loans (loan_id),
    client_id              VARCHAR(64)     NOT NULL,
    type                   VARCHAR(20)     NOT NULL,
    amount                 NUMERIC(20, 8)  NOT NULL,
    outstanding_principal  NUMERIC(20, 8)  NOT NULL,
    accrued_interest       NUMERIC(20, 8)  NOT NULL,
    status                 VARCHAR(20)     NOT NULL,
    occurred_at            TIMESTAMPTZ     NOT NULL
);

CREATE INDEX idx_loan_activity_client_id ON loan_activity (client_id);
