-- Game Mode: capital gains tax on realized profits, tracked per session — see
-- GameServiceImpl.settleTax. total_realized_pnl_taxed is the internal high-water mark (a later
-- loss never claws back tax already paid); tax_last_settled_at defaults to now() so an existing
-- in-progress session doesn't get hit with a backdated tax bill covering time before this shipped.
ALTER TABLE game_sessions
    ADD COLUMN total_realized_pnl_taxed NUMERIC(20, 8) NOT NULL DEFAULT 0,
    ADD COLUMN total_tax_paid NUMERIC(20, 8) NOT NULL DEFAULT 0 CHECK (total_tax_paid >= 0),
    ADD COLUMN tax_last_settled_at TIMESTAMPTZ NOT NULL DEFAULT now();
