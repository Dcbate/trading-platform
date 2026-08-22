-- Game Mode: dividends on held stock positions, swept straight into cash — see
-- GameServiceImpl.settleDividends. dividend_last_accrual_at defaults to now() so an existing
-- position (from before this migration) doesn't get a backdated payout for time already elapsed.
ALTER TABLE game_positions
    ADD COLUMN dividend_last_accrual_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE game_sessions
    ADD COLUMN total_dividends_paid NUMERIC(20, 8) NOT NULL DEFAULT 0 CHECK (total_dividends_paid >= 0);
