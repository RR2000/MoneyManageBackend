-- "Accedi con GitHub": the account is found again by the verified email GitHub gives (never an unverified one).
-- Accounts registered with a password have no email and are never linked.
ALTER TABLE users ADD COLUMN email VARCHAR(320) UNIQUE;
