-- Nullable at the DB level (safe against any pre-existing rows in a long-lived dev database);
-- PaymentRequest.sourceAccountId is @NotNull, so the application layer always supplies it.
ALTER TABLE payments ADD COLUMN source_account_id UUID REFERENCES accounts (account_id);

CREATE INDEX idx_payments_source_account_id ON payments (source_account_id);
