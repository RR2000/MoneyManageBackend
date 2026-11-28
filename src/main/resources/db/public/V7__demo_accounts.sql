-- Demo sandboxes ("Prova la demo", DemoAccounts): an account made by one click, with the sample data, that stops
-- working at expires_at and is then removed with its schema by the hourly cleanup. Every other account keeps
-- demo = false and expires_at null. The cleanup filters on demo AND the demo- prefix of the username: the check
-- below makes sure a demo row always has both, and the sign-up refuses usernames that start with demo-.
ALTER TABLE users ADD COLUMN demo BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE users ADD COLUMN expires_at TIMESTAMPTZ;
ALTER TABLE users ADD CONSTRAINT users_demo_sandbox
    CHECK (NOT demo OR (expires_at IS NOT NULL AND username LIKE 'demo-%' AND email IS NULL));
CREATE INDEX users_demo_expires_at ON users (expires_at) WHERE demo;
