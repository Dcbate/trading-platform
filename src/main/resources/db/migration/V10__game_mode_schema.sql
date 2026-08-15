-- Game Mode: an entirely separate economy from the real accounts/orders/loans tables above.
-- No foreign keys into accounts/clients beyond client_id itself — a game session never touches
-- real money, so it deliberately doesn't join against the real banking schema.

CREATE TABLE game_sessions (
    session_id        UUID PRIMARY KEY,
    client_id         VARCHAR(64)     NOT NULL,
    difficulty        VARCHAR(20)     NOT NULL,
    cash              NUMERIC(20, 8)  NOT NULL,
    status            VARCHAR(20)     NOT NULL,
    started_at        TIMESTAMPTZ     NOT NULL,
    ends_at           TIMESTAMPTZ     NOT NULL,
    finished_at       TIMESTAMPTZ,
    final_net_worth   NUMERIC(20, 8)
);

CREATE INDEX idx_game_sessions_client_id ON game_sessions (client_id);
CREATE INDEX idx_game_sessions_client_id_status ON game_sessions (client_id, status);

CREATE TABLE game_positions (
    position_id  UUID PRIMARY KEY,
    session_id   UUID            NOT NULL REFERENCES game_sessions (session_id),
    symbol       VARCHAR(20)     NOT NULL,
    quantity     NUMERIC(20, 8)  NOT NULL CHECK (quantity > 0),
    avg_cost     NUMERIC(20, 8)  NOT NULL CHECK (avg_cost >= 0)
);

CREATE UNIQUE INDEX idx_game_positions_session_symbol ON game_positions (session_id, symbol);

CREATE TABLE game_loans (
    game_loan_id          UUID PRIMARY KEY,
    session_id            UUID            NOT NULL REFERENCES game_sessions (session_id),
    principal             NUMERIC(20, 8)  NOT NULL CHECK (principal > 0),
    rate_annual_percent   NUMERIC(6, 3)   NOT NULL CHECK (rate_annual_percent >= 0),
    originated_at         TIMESTAMPTZ     NOT NULL
);

CREATE INDEX idx_game_loans_session_id ON game_loans (session_id);

CREATE TABLE game_trades (
    trade_id       UUID PRIMARY KEY,
    session_id     UUID            NOT NULL REFERENCES game_sessions (session_id),
    symbol         VARCHAR(20)     NOT NULL,
    side           VARCHAR(10)     NOT NULL,
    quantity       NUMERIC(20, 8)  NOT NULL CHECK (quantity > 0),
    price          NUMERIC(20, 8)  NOT NULL CHECK (price > 0),
    fee            NUMERIC(20, 8)  NOT NULL CHECK (fee >= 0),
    realized_pnl   NUMERIC(20, 8),
    created_at     TIMESTAMPTZ     NOT NULL
);

CREATE INDEX idx_game_trades_session_id ON game_trades (session_id, created_at DESC);
