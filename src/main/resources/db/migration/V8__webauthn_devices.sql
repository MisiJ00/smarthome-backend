-- Browsers can be trusted devices too: a WebAuthn credential (platform authenticator, user verification required)
-- signs approvals like a phone's key does. Only the public key (X.509 SubjectPublicKeyInfo, EC P-256) is stored.

ALTER TABLE trusted_devices
    ADD COLUMN kind          TEXT   NOT NULL DEFAULT 'key' CHECK (kind IN ('key', 'webauthn')),
    ADD COLUMN credential_id TEXT UNIQUE,           -- Base64url, only for kind = 'webauthn'
    ADD COLUMN sign_count    BIGINT NOT NULL DEFAULT 0;

-- One-time challenges for creating such a credential (approvals reuse confirmation_challenges)

CREATE TABLE webauthn_challenges (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id    BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    challenge  TEXT        NOT NULL UNIQUE,         -- Base64url
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    used_at    TIMESTAMPTZ
);
