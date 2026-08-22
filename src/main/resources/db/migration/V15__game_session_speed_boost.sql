-- Game Mode: a player-triggered speed boost's cooldown clock, tracked per session — see
-- GameServiceImpl.activateSpeedBoost. Defaults to now() so an existing in-progress session (from
-- before this migration) can immediately activate a boost rather than being stuck on a cooldown
-- it never started.
ALTER TABLE game_sessions
    ADD COLUMN speed_boost_available_at TIMESTAMPTZ NOT NULL DEFAULT now();
