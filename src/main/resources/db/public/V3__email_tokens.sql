-- Links sent by email (password reset, confirmation of the account's email). Only the SHA-256 of the random token
-- is kept; a link works once (used_at) and until expires_at.
CREATE TABLE email_tokens (
    token_hash VARCHAR(64)  PRIMARY KEY,
    user_id    BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    purpose    VARCHAR(16)  NOT NULL,
    email      VARCHAR(320) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL,
    expires_at TIMESTAMPTZ  NOT NULL,
    used_at    TIMESTAMPTZ
);
CREATE INDEX email_tokens_user ON email_tokens (user_id, purpose);
