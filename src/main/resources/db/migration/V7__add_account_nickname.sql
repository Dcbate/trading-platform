-- Nothing forces two of a client's accounts to differ by type or currency (two CHECKING/USD
-- accounts is legitimate), so accountId alone was the only way to tell them apart in the UI.
-- Optional, client-chosen label; nullable because it's a UX nicety, not a constraint the domain
-- depends on.
ALTER TABLE accounts ADD COLUMN nickname VARCHAR(64);
