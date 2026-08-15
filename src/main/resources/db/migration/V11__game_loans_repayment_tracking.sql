-- Game loans now support repayment (previously computed everything live from origination with
-- no mutable state) — needs the same principal/outstanding/accrued split the real loans table
-- already has, plus a rolling accrual timestamp since there's no scheduled job settling interest
-- for game loans the way LoanInterestScheduler does for real ones.

ALTER TABLE game_loans ADD COLUMN outstanding_principal NUMERIC(20, 8) NOT NULL DEFAULT 0 CHECK (outstanding_principal >= 0);
ALTER TABLE game_loans ADD COLUMN accrued_interest NUMERIC(20, 8) NOT NULL DEFAULT 0 CHECK (accrued_interest >= 0);
ALTER TABLE game_loans ADD COLUMN last_accrual_at TIMESTAMPTZ;

UPDATE game_loans SET outstanding_principal = principal, last_accrual_at = originated_at;

ALTER TABLE game_loans ALTER COLUMN outstanding_principal DROP DEFAULT;
ALTER TABLE game_loans ALTER COLUMN accrued_interest DROP DEFAULT;
ALTER TABLE game_loans ALTER COLUMN last_accrual_at SET NOT NULL;
