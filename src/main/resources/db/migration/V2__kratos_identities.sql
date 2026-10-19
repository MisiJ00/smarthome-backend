-- Login moves to Ory Kratos: users no longer have a login or password, only the Kratos identity id.
-- Users are created on their first request with a valid Kratos JWT. Before this migration no code
-- created users, so the table is expected to be empty (the NOT NULL column fails otherwise).

ALTER TABLE users
    DROP COLUMN login,
    DROP COLUMN password_hash,
    ADD COLUMN identity_id UUID NOT NULL UNIQUE;
