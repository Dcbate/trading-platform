-- Game Mode: overdraft interest on a negative cash balance — see GameServiceImpl.settleOverdraftInterest.
-- Tax is the one deduction that isn't gated behind an insufficient-funds check, so a big tax bill
-- against a cash-poor, position-heavy session is the realistic way cash goes negative in the first
-- place. overdraft_last_accrual_at is null by default (no existing session is mid-overdraft),
-- matching settleOverdraftInterest's "clock starts the moment cash actually goes negative" rule.
ALTER TABLE game_sessions
    ADD COLUMN overdraft_last_accrual_at TIMESTAMPTZ,
    ADD COLUMN total_overdraft_interest_paid NUMERIC(20, 8) NOT NULL DEFAULT 0 CHECK (total_overdraft_interest_paid >= 0);
