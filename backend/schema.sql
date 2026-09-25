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
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- 8. RELATÓRIOS DE MODERAÇÃO E SEGURANÇA (Tolerância Zero para Violações Graves)
CREATE TABLE IF NOT EXISTS moderation_reports (
    id VARCHAR(64) PRIMARY KEY,
    reporter_identity VARCHAR(64),
    accused_identity VARCHAR(64),
    reason TEXT NOT NULL,
    evidence_payload TEXT,                       -- Pacote de evidências criptografadas
    status VARCHAR(32) DEFAULT 'PENDING',        -- PENDING, REVIEWED, RESOLVED, BANNED
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- ÍNDICES PARA ALTA VELOCIDADE DE CONSULTA EM TEMPO REAL
CREATE INDEX IF NOT EXISTS idx_connections_pair ON connections(from_identity, to_identity);
CREATE INDEX IF NOT EXISTS idx_connections_mutual ON connections(is_mutual);
CREATE INDEX IF NOT EXISTS idx_recovery_key ON recovery_authorizations(recovery_key);
CREATE INDEX IF NOT EXISTS idx_e2ee_recipient ON e2ee_messages(recipient_identity, is_delivered);
CREATE INDEX IF NOT EXISTS idx_devices_active ON devices(identity_id, is_active);
