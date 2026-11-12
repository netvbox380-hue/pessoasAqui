-- =========================================================================
-- PESSOASAQUI — ESQUEMA RELACIONAL DO BANCO DE DADOS (SUPABASE / POSTGRESQL)
-- “PessoasAqui — converse com quem está perto.”
-- =========================================================================

-- 1. IDENTIDADES TÉCNICAS CRIPTOGRÁFICAS (Sem e-mail, telefone ou senha)
CREATE TABLE IF NOT EXISTS identities (
    id VARCHAR(64) PRIMARY KEY,                  -- Hash SHA-256 da chave pública Ed25519
    public_key_ed25519 TEXT NOT NULL,            -- Chave pública para verificação de assinaturas digitais
    public_key_x25519 TEXT,                      -- Chave pública para troca Diffie-Hellman em conversas E2EE
    pin_salted_hash VARCHAR(128) NOT NULL,       -- Hash do PIN pessoal do usuário (salgado com SHA-256/Argon2)
    alias VARCHAR(64) DEFAULT 'Eu',              -- Pseudônimo ou nome de exibição local
    is_banned BOOLEAN DEFAULT FALSE,             -- Status de moderação
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- 2. DISPOSITIVOS E SESSÕES ATIVAS (Regra: 1 Identidade = 1 Dispositivo Ativo)
CREATE TABLE IF NOT EXISTS devices (
    id VARCHAR(64) PRIMARY KEY,                  -- Identificador do dispositivo
    identity_id VARCHAR(64) REFERENCES identities(id) ON DELETE CASCADE,
    device_fingerprint TEXT NOT NULL,            -- Assinatura para proteção anti-evasão de banimentos
    is_active BOOLEAN DEFAULT TRUE,              -- Apenas 1 dispositivo ativo por vez por identidade
    revoked_at TIMESTAMPTZ,
    last_seen_at TIMESTAMPTZ DEFAULT NOW(),
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- 3. TABELA DE PRESENÇA EFÊMERA (10 Metros)
-- As presenças expiram rapidamente quando o usuário sai do local físico
CREATE TABLE IF NOT EXISTS presences (
    identity_id VARCHAR(64) PRIMARY KEY REFERENCES identities(id) ON DELETE CASCADE,
    ephemeral_token VARCHAR(64) NOT NULL,        -- Token rotativo de presença
    intent_id VARCHAR(32) DEFAULT 'QUERO_CONVERSAR',
    last_ping_at TIMESTAMPTZ DEFAULT NOW()
);

-- 4. MARCAÇÕES E CONEXÕES MÚTUAS (10m = descoberta -> mútua = vínculo à distância)
CREATE TABLE IF NOT EXISTS connections (
    id BIGSERIAL PRIMARY KEY,
    from_identity VARCHAR(64) REFERENCES identities(id) ON DELETE CASCADE,
    to_identity VARCHAR(64) REFERENCES identities(id) ON DELETE CASCADE,
    is_mutual BOOLEAN DEFAULT FALSE,             -- True somente se ambos se marcaram
    created_at TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE (from_identity, to_identity)
);

-- ETAPA 4B / REVISOES MONOTONICAS:
-- As tabelas connection_revisions e connection_events sao definidas pela
-- migracao aditiva backend/migrations/003_connection_monotonic_revisions.sql.
-- Nao duplicar esse DDL aqui antes de adaptar todos os fixtures de testes
-- que executam TRUNCATE identities sem CASCADE (as novas FKs afetariam
-- a execucao isolada). O protocolo de revisoes so e habilitado pelo flag
-- explicito CONNECTION_REVISIONS_ENABLED=true APOS aplicacao/homologacao
-- do esquema e reconciliacao do backfill.
--
-- 5. RELAÇÕES FAMILIARES (Rede de Confiança & Recuperação de Identidade)
CREATE TABLE IF NOT EXISTS family_relationships (
    id BIGSERIAL PRIMARY KEY,
    identity_a VARCHAR(64) REFERENCES identities(id) ON DELETE CASCADE,
    identity_b VARCHAR(64) REFERENCES identities(id) ON DELETE CASCADE,
    role_name VARCHAR(32) NOT NULL,              -- Pai, Mãe, Marido, Esposa, Filho, Filha, Irmão, Irmã
    confirmed_at TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE (identity_a, identity_b)
);

-- 6. AUTORIZAÇÕES DE RECUPERAÇÃO EMITIDAS POR FAMILIARES (“🔐 Entre na sua”)
CREATE TABLE IF NOT EXISTS recovery_authorizations (
    id VARCHAR(64) PRIMARY KEY,
    target_identity VARCHAR(64) REFERENCES identities(id) ON DELETE CASCADE,
    issued_by_family VARCHAR(64) REFERENCES identities(id) ON DELETE CASCADE,
    recovery_key VARCHAR(64) UNIQUE NOT NULL,    -- Chave temporária ou payload do QR Code
    expires_at TIMESTAMPTZ NOT NULL,             -- Validade estrita de 15 minutos
    failed_pin_attempts INT DEFAULT 0,           -- Contador de erros de PIN no novo celular
    locked_until TIMESTAMPTZ,                    -- Timestamp de bloqueio progressivo (Seção 23)
    is_used BOOLEAN DEFAULT FALSE,               -- Uso único estrito
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- 6.0b. RECUPERAÇÃO CRIPTOGRÁFICA V2 (pessoas autorizadas previamente)
-- Tabelas novas, sem alterar ou apagar backups, convites ou autorizações legadas.
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
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_recovery_grants_target
    ON recovery_grants(target_identity,expires_at);

-- OFERTAS V2 — publicações autenticadas e persistidas; localização verificada via BLE no Android.
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
CREATE INDEX IF NOT EXISTS idx_offers_secure_owner ON offers_secure(author_id, created_at);
CREATE TABLE IF NOT EXISTS offer_action_nonces (
  author_id VARCHAR(64) NOT NULL,
  nonce VARCHAR(64) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  PRIMARY KEY(author_id, nonce)
);

-- 6.1. CONVITES CRIPTOGRAFADOS DE USO ÚNICO (Conexão Mútua à Distância)
CREATE TABLE IF NOT EXISTS invites (
    id VARCHAR(64) PRIMARY KEY,                  -- ID do convite (ex: inv_abc123)
    token VARCHAR(64) NOT NULL,                  -- Token aleatório ou segredo
    sender_identity VARCHAR(64) REFERENCES identities(id) ON DELETE CASCADE,
    sender_alias VARCHAR(64),
    signature TEXT,                              -- Assinatura criptográfica HMAC / Ed25519
    expires_at TIMESTAMPTZ NOT NULL,             -- Validade estrita do convite
    is_used BOOLEAN DEFAULT FALSE,               -- Uso único garantido
    used_by VARCHAR(64) REFERENCES identities(id) ON DELETE SET NULL,
    used_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- 7. RELAY DE MENSAGENS E2EE (Criptografia Ponta a Ponta - Servidor não lê conteúdo)
CREATE TABLE IF NOT EXISTS e2ee_messages (
    id VARCHAR(64) PRIMARY KEY,
    sender_identity VARCHAR(64) REFERENCES identities(id) ON DELETE CASCADE,
    recipient_identity VARCHAR(64) REFERENCES identities(id) ON DELETE CASCADE,
    ciphertext_payload TEXT NOT NULL,            -- Conteúdo criptografado no aparelho (Envelope cego)
    iv_nonce VARCHAR(64) NOT NULL,
    is_delivered BOOLEAN DEFAULT FALSE,
    is_cancelled BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    delivered_at TIMESTAMPTZ,
    cancelled_at TIMESTAMPTZ
);

-- 8. RELATÓRIOS DE MODERAÇÃO E SEGURANÇA (Tolerância Zero para Violações Graves)
CREATE TABLE IF NOT EXISTS moderation_reports (
    id VARCHAR(64) PRIMARY KEY,
    reporter_identity VARCHAR(64),
    accused_identity VARCHAR(64),
    violation_type VARCHAR(64) DEFAULT 'ABUSE',  -- HARASSMENT, SCAM_FRAUD, ILLICIT_CONTENT, SPAM, OTHER
    reason TEXT NOT NULL,
    evidence_payload TEXT,                       -- Pacote de evidências / mensagens
    status VARCHAR(32) DEFAULT 'PENDING',        -- PENDING, UNDER_REVIEW, STRIKE_ISSUED, BANNED, DISMISSED
    moderator_notes TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    resolved_at TIMESTAMPTZ
);

-- 9. PETIÇÕES E CHAMADOS DE SUPORTE AO CLIENTE (Helpdesk Integrado)
CREATE TABLE IF NOT EXISTS support_tickets (
    id VARCHAR(64) PRIMARY KEY,
    author_identity VARCHAR(64) NOT NULL,
    author_alias VARCHAR(64),
    subject VARCHAR(128) NOT NULL,
    category VARCHAR(64) DEFAULT 'GERAL',        -- GERAL, DUVIDA, RECUPERACAO, BUG, OUTRO
    message TEXT NOT NULL,
    status VARCHAR(32) DEFAULT 'OPEN',           -- OPEN, ANSWERED, CLOSED
    moderator_reply TEXT,
    replied_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- ÍNDICES PARA ALTA VELOCIDADE DE CONSULTA EM TEMPO REAL
CREATE INDEX IF NOT EXISTS idx_connections_pair ON connections(from_identity, to_identity);
CREATE INDEX IF NOT EXISTS idx_connections_mutual ON connections(is_mutual);
CREATE INDEX IF NOT EXISTS idx_recovery_key ON recovery_authorizations(recovery_key);
CREATE INDEX IF NOT EXISTS idx_e2ee_recipient ON e2ee_messages(recipient_identity, is_delivered);
CREATE INDEX IF NOT EXISTS idx_devices_active ON devices(identity_id, is_active);
