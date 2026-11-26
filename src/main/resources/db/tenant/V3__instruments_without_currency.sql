-- The first automatic search could pick a London listing in pence (GBX, currency unknown) over the euro one: those
-- symbols and their prices go, and the next opening looks the ISIN up again. Symbols set by hand stay.
DELETE FROM stock s USING instrument i
 WHERE s.isin = i.isin AND s.symbol = i.symbol AND NOT i.manual AND i.currency IS NULL;
DELETE FROM instrument WHERE NOT manual AND currency IS NULL;
