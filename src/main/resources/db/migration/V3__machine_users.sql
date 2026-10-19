-- Machine users (the AI, scripts) have no Kratos identity; they authenticate with API tokens.

ALTER TABLE users ALTER COLUMN identity_id DROP NOT NULL;

-- The local AI: a normal user with role ki, so it goes through the same permission checks as everyone else
WITH ki AS (
    INSERT INTO users (name) VALUES ('KI') RETURNING id
)
INSERT INTO user_roles (user_id, role_id)
SELECT ki.id, roles.id FROM ki, roles WHERE roles.name = 'ki';
