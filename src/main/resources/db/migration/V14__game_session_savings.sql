-- Game Mode: a savings sub-balance on the session itself, not a new table — a session only ever
-- has one savings "account", so there's nothing a join would buy over two extra columns.
ALTER TABLE game_sessions
    ADD COLUMN savings_balance NUMERIC(20, 8) NOT NULL DEFAULT 0 CHECK (savings_balance >= 0),
    ADD COLUMN savings_last_accrual_at TIMESTAMPTZ NOT NULL DEFAULT now();
