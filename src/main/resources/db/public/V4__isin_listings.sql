-- Listings of an ISIN (ticker and exchange) from OpenFIGI, kept for good: public market data, not anybody's,
-- and OpenFIGI without a key allows few requests. listings: JSON array of {ticker, exchange, name}.
CREATE TABLE isin_listing (
    isin       VARCHAR(20)  PRIMARY KEY,
    listings   TEXT         NOT NULL,
    fetched_at TIMESTAMPTZ  NOT NULL
);
