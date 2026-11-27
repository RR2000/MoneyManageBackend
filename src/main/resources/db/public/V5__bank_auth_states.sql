-- Open banking: the "state" of a bank authorization in progress (the user is at the bank). Only the SHA-256 of the
-- random state is kept; it works once (used_at) and for 10 minutes. The callback comes back without a login: the row
-- says whose it is.
CREATE TABLE bank_auth_state (
    state_hash    VARCHAR(64)  PRIMARY KEY,
    user_id       BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    aspsp_name    VARCHAR(200) NOT NULL,
    aspsp_country VARCHAR(2)   NOT NULL,
    valid_until   TIMESTAMPTZ  NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL,
    expires_at    TIMESTAMPTZ  NOT NULL,
    used_at       TIMESTAMPTZ
);
