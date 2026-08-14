CREATE TABLE loans (
    loan_id                       UUID PRIMARY KEY,
    client_id                     VARCHAR(64)     NOT NULL,
    account_id                    UUID            NOT NULL REFERENCES accounts (account_id),
    principal                     NUMERIC(20, 8)  NOT NULL CHECK (principal > 0),
    outstanding_principal         NUMERIC(20, 8)  NOT NULL CHECK (outstanding_principal >= 0),
    interest_rate_annual_percent  NUMERIC(6, 3)   NOT NULL CHECK (interest_rate_annual_percent >= 0),
    product_type                  VARCHAR(30)     NOT NULL,
    term_months                   INT             NOT NULL CHECK (term_months > 0),
    accrued_interest              NUMERIC(20, 8)  NOT NULL CHECK (accrued_interest >= 0),
    status                        VARCHAR(20)     NOT NULL,
    created_at                    TIMESTAMPTZ     NOT NULL,
    last_accrual_at               TIMESTAMPTZ     NOT NULL
);

CREATE INDEX idx_loans_client_id ON loans (client_id);
CREATE INDEX idx_loans_status ON loans (status);
