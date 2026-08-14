CREATE TABLE users (
    user_id       UUID PRIMARY KEY,
    email         VARCHAR(255)    NOT NULL,
    password_hash VARCHAR(255)    NOT NULL,
    created_at    TIMESTAMPTZ     NOT NULL
);

CREATE UNIQUE INDEX idx_users_email ON users (LOWER(email));

CREATE TABLE refresh_tokens (
    token_id   UUID PRIMARY KEY,
    user_id    UUID            NOT NULL REFERENCES users (user_id),
    expires_at TIMESTAMPTZ     NOT NULL,
    revoked    BOOLEAN         NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ     NOT NULL
);

CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens (user_id);
