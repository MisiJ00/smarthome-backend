-- Confirmations remember which client the request came from and when it was decided

ALTER TABLE confirmations
    ADD COLUMN via        TEXT NOT NULL DEFAULT 'app' CHECK (via IN ('web', 'app', 'widget', 'ki', 'system')),
    ADD COLUMN decided_at TIMESTAMPTZ;

CREATE INDEX confirmations_pending_idx ON confirmations (expires_at) WHERE status = 'pending';

-- The audit log keeps the names, so entries stay readable after a user or device is deleted

ALTER TABLE audit_log
    ADD COLUMN user_name       TEXT,
    ADD COLUMN device_name     TEXT,
    ADD COLUMN confirmation_id BIGINT REFERENCES confirmations (id) ON DELETE SET NULL;

CREATE INDEX audit_log_user_idx ON audit_log (user_id, ts DESC);
CREATE INDEX audit_log_device_idx ON audit_log (device_id, ts DESC);
