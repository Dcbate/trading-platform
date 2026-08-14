ALTER TABLE orders RENAME COLUMN symbol TO currency_pair;
ALTER TABLE trades RENAME COLUMN symbol TO currency_pair;

ALTER INDEX idx_orders_symbol_status RENAME TO idx_orders_currency_pair_status;
ALTER INDEX idx_trades_symbol RENAME TO idx_trades_currency_pair;
