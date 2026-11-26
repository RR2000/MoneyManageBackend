-- How the prices of each ISIN are found: the Alpha Vantage symbol (found from OpenFIGI, or set by hand on the
-- investments page) and the currency it quotes in. symbol NULL = not found (tried again after a while).
CREATE TABLE instrument (
    isin        VARCHAR(20) PRIMARY KEY,
    symbol      VARCHAR(40),
    currency    VARCHAR(3),
    name        VARCHAR(200),
    manual      BOOLEAN     NOT NULL DEFAULT FALSE,
    resolved_at TIMESTAMP   NOT NULL
);
