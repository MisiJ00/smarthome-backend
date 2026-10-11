-- Users, roles and rooms

CREATE TABLE users (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    login         TEXT        NOT NULL UNIQUE,
    name          TEXT        NOT NULL,
    password_hash TEXT        NOT NULL,
    is_active     BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE roles (
    id   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name TEXT NOT NULL UNIQUE
);

CREATE TABLE user_roles (
    user_id BIGINT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role_id BIGINT NOT NULL REFERENCES roles (id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, role_id)
);

CREATE TABLE rooms (
    id   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name TEXT    NOT NULL UNIQUE,
    sort INTEGER NOT NULL DEFAULT 0
);

-- Devices

CREATE TABLE devices (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name           TEXT   NOT NULL,
    room_id        BIGINT REFERENCES rooms (id) ON DELETE SET NULL,
    type           TEXT   NOT NULL,                  -- light, thermostat, plug, lock, ir_light, ...
    adapter        TEXT   NOT NULL,                  -- z2m, shelly, esphome, ha, sim
    external_id    TEXT   NOT NULL,                  -- id within the adapter, e.g. z2m friendly name
    capabilities   JSONB  NOT NULL DEFAULT '{}',
    state          JSONB  NOT NULL DEFAULT '{}',
    last_seen      TIMESTAMPTZ,
    -- Actions nobody may run, not even admins or the AI (e.g. switch_off on the server plug)
    locked_actions TEXT[] NOT NULL DEFAULT '{}',
    UNIQUE (adapter, external_id)
);

-- Permissions: a grant gives a role or a user one action on all devices, a room or a device

CREATE TABLE grants (
    id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    subject_type          TEXT    NOT NULL CHECK (subject_type IN ('role', 'user')),
    subject_id            BIGINT  NOT NULL,
    scope_type            TEXT    NOT NULL CHECK (scope_type IN ('all', 'room', 'device')),
    scope_id              BIGINT,
    action                TEXT    NOT NULL CHECK (action IN ('view', 'control', 'configure', 'unlock')),
    requires_confirmation BOOLEAN NOT NULL DEFAULT FALSE,
    CHECK ((scope_type = 'all') = (scope_id IS NULL))
);

CREATE INDEX grants_subject_idx ON grants (subject_type, subject_id);

-- Actions waiting for a human to approve them (always the case for unlock)

CREATE TABLE confirmations (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id    BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    device_id  BIGINT      NOT NULL REFERENCES devices (id) ON DELETE CASCADE,
    action     TEXT        NOT NULL,
    params     JSONB       NOT NULL DEFAULT '{}',
    status     TEXT        NOT NULL DEFAULT 'pending'
               CHECK (status IN ('pending', 'approved', 'rejected', 'expired')),
    decided_by BIGINT      REFERENCES users (id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL
);

-- Widgets per user

CREATE TABLE widgets (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id     BIGINT  NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    kind        TEXT    NOT NULL,
    target_type TEXT    NOT NULL CHECK (target_type IN ('device', 'room', 'scene')),
    target_id   BIGINT  NOT NULL,
    config      JSONB   NOT NULL DEFAULT '{}',
    position    INTEGER NOT NULL DEFAULT 0
);

-- API tokens for the AI, widgets and scripts

CREATE TABLE api_tokens (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id    BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name       TEXT        NOT NULL,
    token_hash TEXT        NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_used  TIMESTAMPTZ
);

-- Who did what, through which client

CREATE TABLE audit_log (
    id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ts        TIMESTAMPTZ NOT NULL DEFAULT now(),
    user_id   BIGINT REFERENCES users (id) ON DELETE SET NULL,
    via       TEXT   NOT NULL CHECK (via IN ('web', 'app', 'widget', 'ki', 'system')),
    device_id BIGINT REFERENCES devices (id) ON DELETE SET NULL,
    action    TEXT   NOT NULL,
    params    JSONB  NOT NULL DEFAULT '{}',
    result    TEXT   NOT NULL
);

CREATE INDEX audit_log_ts_idx ON audit_log (ts DESC);

INSERT INTO roles (name) VALUES ('admin'), ('bewohner'), ('gast'), ('ki');
