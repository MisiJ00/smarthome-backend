-- Rooms get an icon and timestamps. Names are unique regardless of case, so "Küche" and "küche" cannot both exist.

ALTER TABLE rooms
    DROP CONSTRAINT rooms_name_key,
    ALTER COLUMN name TYPE VARCHAR(64),
    ADD COLUMN icon       VARCHAR(32),
    ADD COLUMN created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now();

CREATE UNIQUE INDEX rooms_name_lower_idx ON rooms (lower(name));

-- A room with devices cannot be deleted; the devices have to be moved out first
ALTER TABLE devices
    DROP CONSTRAINT devices_room_id_fkey,
    ADD CONSTRAINT devices_room_id_fkey FOREIGN KEY (room_id) REFERENCES rooms (id) ON DELETE RESTRICT;

CREATE INDEX devices_room_id_idx ON devices (room_id);
