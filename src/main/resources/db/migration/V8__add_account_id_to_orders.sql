-- Nullable: the FX desk's existing order flow (dealer-submitted, no funding account) keeps
-- working exactly as before. Only orders that carry an account_id get real settlement — see
-- ExecutionServiceImpl. That's how stock orders opt into moving real money and FX orders don't
-- have to, without touching the existing FX order pipeline at all.
ALTER TABLE orders ADD COLUMN account_id UUID REFERENCES accounts (account_id);

CREATE INDEX idx_orders_account_id ON orders (account_id);
