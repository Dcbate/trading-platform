-- Game Mode: a per-position downside floor bought with a one-time premium — see
-- GameServiceImpl.purchaseInsurance/effectivePrice.
ALTER TABLE game_positions
    ADD COLUMN insured BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN insurance_floor_price NUMERIC(20, 8) CHECK (insurance_floor_price IS NULL OR insurance_floor_price >= 0);
