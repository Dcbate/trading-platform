-- Replaces account_activity and loan_activity with one table. Both were already the same shape
-- (an immutable row per event) split for no good reason; deposits/withdrawals/conversions/
-- closures/loan events now all land here, alongside a transfer's two legs.

DROP TABLE account_activity;
DROP TABLE loan_activity;

CREATE TABLE activity (
    activity_id  UUID PRIMARY KEY,
    client_id    VARCHAR(64)     NOT NULL,
    account_id   UUID,
    type         VARCHAR(20)     NOT NULL,
    amount       NUMERIC(20, 8)  NOT NULL,
    currency     VARCHAR(3),
    description  VARCHAR(255)    NOT NULL,
    occurred_at  TIMESTAMPTZ     NOT NULL
);

CREATE INDEX idx_activity_client_id ON activity (client_id);
CREATE INDEX idx_activity_client_id_account_id ON activity (client_id, account_id);
