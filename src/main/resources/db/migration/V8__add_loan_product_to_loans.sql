-- Nullable at the DB level (safe against any pre-existing rows in a long-lived dev database);
-- LoanRequest.productType is @NotNull, so the application layer always supplies both columns.
ALTER TABLE loans ADD COLUMN product_type VARCHAR(30);
ALTER TABLE loans ADD COLUMN term_months INT;
