-- =========================================================================
-- PESSOASAQUI — ESQUEMA BASE RELACIONAL (POSTGRESQL / SUPABASE)
-- Versão 1.3.3
-- =========================================================================

CREATE TABLE IF NOT EXISTS identities (
    id VARCHAR(64) PRIMARY KEY,
    public_key_ed25519 TEXT NOT NULL,
    public_key_x25519 TEXT,
    pin_salted_hash VARCHAR(128) NOT NULL,
    alias VARCHAR(64) DEFAULT 'Eu',
    is_banned BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS devices (
    id VARCHAR(64) PRIMARY KEY,
    identity_id VARCHAR(64) REFERENCES identities(id) ON DELETE CASCADE,
    device_fingerprint TEXT NOT NULL,
    is_active BOOLEAN DEFAULT TRUE,
    revoked_at TIMESTAMPTZ,
    last_seen_at TIMESTAMPTZ DEFAULT NOW(),
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS presences (
    identity_id VARCHAR(64) PRIMARY KEY REFERENCES identities(id) ON DELETE CASCADE,
    ephemeral_token VARCHAR(64) NOT NULL,
    intent_id VARCHAR(32) DEFAULT 'QUERO_CONVERSAR',
    last_ping_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS connections (
    id BIGSERIAL PRIMARY KEY,
    from_identity VARCHAR(64) REFERENCES identities(id) ON DELETE CASCADE,
    to_identity VARCHAR(64) REFERENCES identities(id) ON DELETE CASCADE,
    is_mutual BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE (from_identity, to_identity)
);

CREATE TABLE IF NOT EXISTS family_relationships (
    id BIGSERIAL PRIMARY KEY,
    identity_a VARCHAR(64) REFERENCES identities(id) ON DELETE CASCADE,
    identity_b VARCHAR(64) REFERENCES identities(id) ON DELETE CASCADE,
    role_name VARCHAR(32) NOT NULL,
    confirmed_at TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE (identity_a, identity_b)
);

CREATE TABLE IF NOT EXISTS recovery_authorizations (
    id VARCHAR(64) PRIMARY KEY,
    target_identity VARCHAR(64) REFERENCES identities(id) ON DELETE CASCADE,
    issued_by_family VARCHAR(64) REFERENCES identities(id) ON DELETE CASCADE,
    recovery_key VARCHAR(64) UNIQUE NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    failed_pin_attempts INT DEFAULT 0,
    locked_until TIMESTAMPTZ,
    is_used BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS identity_key_bindings (
    identity_id VARCHAR(64) PRIMARY KEY REFERENCES identities(id) ON DELETE CASCADE,
    public_key_spki TEXT NOT NULL,
    generation BIGINT NOT NULL DEFAULT 1,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS recovery_delegations (
    target_identity VARCHAR(64) NOT NULL REFERENCES identities(id) ON DELETE CASCADE,
    family_identity VARCHAR(64) NOT NULL REFERENCES identities(id) ON DELETE CASCADE,
    family_public_key TEXT NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY(target_identity, family_identity),
    CHECK (target_identity <> family_identity)
);

CREATE TABLE IF NOT EXISTS recovery_proof_nonces (
    identity_id VARCHAR(64) NOT NULL,
    operation VARCHAR(32) NOT NULL,
    nonce VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY(identity_id, operation, nonce)
);

CREATE TABLE IF NOT EXISTS recovery_grants (
    id VARCHAR(64) PRIMARY KEY,
    token_hash CHAR(64) NOT NULL UNIQUE,
    target_identity VARCHAR(64) NOT NULL REFERENCES identities(id) ON DELETE CASCADE,
    family_identity VARCHAR(64) NOT NULL REFERENCES identities(id) ON DELETE CASCADE,
    family_public_key TEXT NOT NULL,
    expected_generation BIGINT NOT NULL,
    method VARCHAR(8) NOT NULL DEFAULT 'LINK',
    eligible_at TIMESTAMPTZ,
    request_device_identity VARCHAR(64),
    request_public_key TEXT,
    prepared_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS recovery_approvals (
    grant_id VARCHAR(64) NOT NULL REFERENCES recovery_grants(id) ON DELETE CASCADE,
    family_identity VARCHAR(64) NOT NULL REFERENCES identities(id) ON DELETE CASCADE,
    approved_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY(grant_id, family_identity)
);

CREATE TABLE IF NOT EXISTS recovery_share_releases (
    grant_id VARCHAR(64) NOT NULL REFERENCES recovery_grants(id) ON DELETE CASCADE,
    family_identity VARCHAR(64) NOT NULL REFERENCES identities(id) ON DELETE CASCADE,
    released_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY(grant_id, family_identity)
);

CREATE TABLE IF NOT EXISTS recovery_security_events (
    id BIGSERIAL PRIMARY KEY,
    target_identity VARCHAR(64) NOT NULL,
    actor_identity VARCHAR(64),
    event_type VARCHAR(48) NOT NULL,
    request_id VARCHAR(64),
    details JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS account_recovery_holds (
    target_identity VARCHAR(64) PRIMARY KEY REFERENCES identities(id) ON DELETE CASCADE,
    blocked_until TIMESTAMPTZ NOT NULL,
    reason TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS offers_secure (
    id VARCHAR(64) PRIMARY KEY,
    author_id VARCHAR(64) NOT NULL REFERENCES identities(id) ON DELETE CASCADE,
    author_alias VARCHAR(64) NOT NULL,
    profession VARCHAR(64) NOT NULL,
    description VARCHAR(500) NOT NULL,
    image_base64 TEXT,
    external_link TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS offer_action_nonces (
    author_id VARCHAR(64) NOT NULL,
    nonce VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY(author_id, nonce)
);

CREATE TABLE IF NOT EXISTS invites (
    id VARCHAR(64) PRIMARY KEY,
    token VARCHAR(64) NOT NULL,
    sender_identity VARCHAR(64),
    sender_alias VARCHAR(64),
    signature TEXT,
    expires_at TIMESTAMPTZ NOT NULL,
    is_used BOOLEAN DEFAULT FALSE,
    used_by VARCHAR(64),
    used_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS moderation_reports (
    id VARCHAR(64) PRIMARY KEY,
    reporter_identity VARCHAR(64),
    accused_identity VARCHAR(64),
    violation_type VARCHAR(64) DEFAULT 'ABUSE',
    reason TEXT NOT NULL,
    evidence_payload TEXT,
    status VARCHAR(32) DEFAULT 'PENDING',
    moderator_notes TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    resolved_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS support_tickets (
    id VARCHAR(64) PRIMARY KEY,
    author_identity VARCHAR(64) NOT NULL,
    author_alias VARCHAR(64),
    affected_identity VARCHAR(64),
    verified_family_report BOOLEAN NOT NULL DEFAULT FALSE,
    subject VARCHAR(128) NOT NULL,
    category VARCHAR(64) DEFAULT 'GERAL',
    message TEXT NOT NULL,
    status VARCHAR(32) DEFAULT 'OPEN',
    moderator_reply TEXT,
    replied_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS app_config (
    key VARCHAR(64) PRIMARY KEY,
    value TEXT
);

CREATE TABLE IF NOT EXISTS e2ee_messages (
    id VARCHAR(64) PRIMARY KEY,
    sender_identity VARCHAR(64),
    recipient_identity VARCHAR(64),
    sender_alias VARCHAR(64),
    ciphertext_payload TEXT NOT NULL,
    iv_nonce VARCHAR(64) NOT NULL,
    is_delivered BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_connections_pair ON connections(from_identity, to_identity);
CREATE INDEX IF NOT EXISTS idx_connections_mutual ON connections(is_mutual);
CREATE INDEX IF NOT EXISTS idx_recovery_key ON recovery_authorizations(recovery_key);
CREATE INDEX IF NOT EXISTS idx_recovery_grants_target ON recovery_grants(target_identity, expires_at);
CREATE INDEX IF NOT EXISTS idx_recovery_approvals_family ON recovery_approvals(family_identity);
CREATE INDEX IF NOT EXISTS idx_recovery_share_releases_family ON recovery_share_releases(family_identity);
CREATE INDEX IF NOT EXISTS idx_recovery_security_events_target ON recovery_security_events(target_identity, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_offers_secure_owner ON offers_secure(author_id, created_at);
CREATE INDEX IF NOT EXISTS idx_e2ee_recipient ON e2ee_messages(recipient_identity, is_delivered);
