-- Game Mode: optimistic locking on game_sessions. Every money-mutating endpoint (trade, loan,
-- savings, insurance, advisor, speed boost) reads this row, computes a new value in memory, and
-- writes the whole row back with no version check — two overlapping requests (a double-click, a
-- scripted rapid double-submit) could both read the same starting cash and the second write would
-- silently clobber the first's, letting a player effectively spend the same cash twice. Hibernate's
-- @Version support turns that into a loud, retryable conflict instead of a silent lost update.
-- DEFAULT 0 backfills existing in-progress sessions to a valid starting version.
ALTER TABLE game_sessions
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
