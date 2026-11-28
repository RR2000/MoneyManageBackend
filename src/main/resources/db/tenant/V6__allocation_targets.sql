-- The share of the portfolio the user wants in each ISIN (investments page, "allocation"), in percent of the
-- portfolio in euro. What the targets leave out of 100 is "not assigned". A table of its own: the row of instrument
-- goes away when the symbol goes back to automatic, the target must stay.
CREATE TABLE allocation_target (
    isin    VARCHAR(20)   PRIMARY KEY,
    percent DECIMAL(5, 2) NOT NULL CHECK (percent > 0 AND percent <= 100)
);
