-- User and permission management (admin only). Email and username are copies of the Kratos traits so that the admin
-- list does not need a Kratos request per user; they are updated on every sign-in.

ALTER TABLE users
    ADD COLUMN email    TEXT,
    ADD COLUMN username TEXT;

-- admin, bewohner, gast and ki are system roles: they cannot be deleted. Admins may add their own roles.
ALTER TABLE roles ADD COLUMN is_system BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE roles SET is_system = TRUE WHERE name IN ('admin', 'bewohner', 'gast', 'ki');

-- Grants get real foreign keys instead of subject_id/scope_id, so they disappear together with their role, user,
-- room or device. No code could write grants before this migration, so the table is recreated instead of converted.
DROP TABLE grants;

CREATE TABLE grants (
    id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    role_id               BIGINT REFERENCES roles (id) ON DELETE CASCADE,
    user_id               BIGINT REFERENCES users (id) ON DELETE CASCADE,
    scope_type            TEXT    NOT NULL CHECK (scope_type IN ('all', 'room', 'device')),
    room_id               BIGINT REFERENCES rooms (id) ON DELETE CASCADE,
    device_id             BIGINT REFERENCES devices (id) ON DELETE CASCADE,
    action                TEXT    NOT NULL CHECK (action IN ('view', 'control', 'configure', 'unlock')),
    requires_confirmation BOOLEAN NOT NULL DEFAULT FALSE,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- A grant belongs to exactly one role or one user
    CHECK (num_nonnulls(role_id, user_id) = 1),
    CHECK ((scope_type = 'all' AND room_id IS NULL AND device_id IS NULL)
        OR (scope_type = 'room' AND room_id IS NOT NULL AND device_id IS NULL)
        OR (scope_type = 'device' AND device_id IS NOT NULL AND room_id IS NULL)),
    -- Unlocking always needs a confirmation
    CHECK (action <> 'unlock' OR requires_confirmation),
    UNIQUE NULLS NOT DISTINCT (role_id, user_id, scope_type, room_id, device_id, action)
);

CREATE INDEX grants_role_idx ON grants (role_id);
CREATE INDEX grants_user_idx ON grants (user_id);

-- Default permissions. Admins need no grants, they may do everything except locked actions.
INSERT INTO grants (role_id, scope_type, action, requires_confirmation)
SELECT roles.id, 'all', g.action, g.requires_confirmation
FROM roles
JOIN (VALUES ('bewohner', 'view', FALSE),
             ('bewohner', 'control', FALSE),
             ('bewohner', 'unlock', TRUE),
             ('gast', 'view', FALSE),
             ('ki', 'view', FALSE),
             ('ki', 'control', FALSE),
             ('ki', 'unlock', TRUE)) AS g (role, action, requires_confirmation)
  ON roles.name = g.role;

-- Widgets point to devices, rooms or scenes, so they cannot have a foreign key; triggers clean them up instead
CREATE FUNCTION delete_widgets_of_target() RETURNS trigger AS $$
BEGIN
    DELETE FROM widgets WHERE target_type = TG_ARGV[0] AND target_id = OLD.id;
    RETURN OLD;
END
$$ LANGUAGE plpgsql;

CREATE TRIGGER rooms_delete_widgets AFTER DELETE ON rooms
    FOR EACH ROW EXECUTE FUNCTION delete_widgets_of_target('room');

CREATE TRIGGER devices_delete_widgets AFTER DELETE ON devices
    FOR EACH ROW EXECUTE FUNCTION delete_widgets_of_target('device');
