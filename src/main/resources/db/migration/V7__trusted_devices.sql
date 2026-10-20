-- Phones whose key (in the Secure Enclave / Android Keystore, usable only with Face ID or a fingerprint) may sign
-- approvals. Only the public key is stored. Revoked phones stay for the audit log.

CREATE TABLE trusted_devices (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id    BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name       TEXT        NOT NULL,
    public_key TEXT        NOT NULL UNIQUE,     -- Base64 X.509 SubjectPublicKeyInfo, EC P-256
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_used  TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ
);

CREATE INDEX trusted_devices_user_idx ON trusted_devices (user_id);

-- One-time challenges: a phone signs one of these to approve one confirmation, so a signature cannot be replayed

CREATE TABLE confirmation_challenges (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    confirmation_id BIGINT      NOT NULL REFERENCES confirmations (id) ON DELETE CASCADE,
    user_id         BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    nonce           TEXT        NOT NULL UNIQUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ NOT NULL,
    used_at         TIMESTAMPTZ
);
