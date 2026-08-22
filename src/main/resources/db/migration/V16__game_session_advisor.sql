-- Game Mode: the wealth-manager advisor's hire state and current tip, tracked per session — see
-- GameServiceImpl.hireAdvisor/settleAdvisorTip. advisor_tip_side is nullable text (not an enum
-- column type) mirroring how game_trades.side is already stored elsewhere in this schema.
ALTER TABLE game_sessions
    ADD COLUMN advisor_hired BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN advisor_hired_at TIMESTAMPTZ,
    ADD COLUMN advisor_last_tip_at TIMESTAMPTZ,
    ADD COLUMN advisor_tip_symbol VARCHAR(20),
    ADD COLUMN advisor_tip_side VARCHAR(10);
