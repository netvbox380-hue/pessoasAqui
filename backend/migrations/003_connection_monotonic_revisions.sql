-- PESSOASAQUI / ETAPA 4 — PREPARACAO ADITIVA DAS REVISOES DE CONEXAO
-- NAO EXECUTAR EM PRODUCAO SEM HOMOLOGACAO E AUTORIZACAO ESPECIFICA.
--
-- Esta migracao NAO habilita o protocolo de revisoes: o backend legado
-- continua lendo/escrevendo exclusivamente a tabela connections.
-- A ativacao exige deploy coordenado, verificacao de schema e transacoes
-- atomicas para toda transicao de estado do par.
--
-- OBSERVACAO: chaves tecnicas podem ter ate 64 caracteres cada;
-- pair_key usa TEXT para nao truncar pares com separador.
-- pair_key segue exatamente server.js pairKey(a,b):
-- [cleanId(a).toLowerCase(), cleanId(b).toLowerCase()].sort().join('<->')
--
-- Executar em PostgreSQL de testes isolado antes de qualquer producao.
BEGIN;

CREATE TABLE IF NOT EXISTS connection_revisions (
    pair_key TEXT PRIMARY KEY,
    identity_low VARCHAR(64) NOT NULL REFERENCES identities(id) ON DELETE CASCADE,
    identity_high VARCHAR(64) NOT NULL REFERENCES identities(id) ON DELETE CASCADE,
    current_state VARCHAR(16) NOT NULL,
    revision BIGINT NOT NULL DEFAULT 1 CHECK (revision > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT connection_revisions_distinct_peers
        CHECK (LOWER(identity_low) <> LOWER(identity_high)),
    CONSTRAINT connection_revisions_valid_state
        CHECK (current_state IN ('NONE', 'MARKED_LOW', 'MARKED_HIGH', 'MUTUAL'))
);
CREATE INDEX IF NOT EXISTS idx_connection_revisions_low ON connection_revisions(identity_low);
CREATE INDEX IF NOT EXISTS idx_connection_revisions_high ON connection_revisions(identity_high);

CREATE TABLE IF NOT EXISTS connection_events (
    id BIGSERIAL PRIMARY KEY,
    pair_key TEXT NOT NULL REFERENCES connection_revisions(pair_key) ON DELETE CASCADE,
    actor_identity VARCHAR(64) REFERENCES identities(id) ON DELETE SET NULL,
    target_identity VARCHAR(64) REFERENCES identities(id) ON DELETE SET NULL,
    action VARCHAR(24) NOT NULL
        CHECK (action IN ('MARK', 'MUTUAL', 'UNMARK', 'PURGE')),
    resulting_state VARCHAR(16) NOT NULL
        CHECK (resulting_state IN ('NONE', 'MARKED_LOW', 'MARKED_HIGH', 'MUTUAL')),
    revision BIGINT NOT NULL CHECK (revision > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT connection_events_pair_revision_unique UNIQUE (pair_key, revision)
);
CREATE INDEX IF NOT EXISTS idx_connection_events_actor ON connection_events(actor_identity, id);
CREATE INDEX IF NOT EXISTS idx_connection_events_target ON connection_events(target_identity, id);

-- Backfill de TODOS os pares existentes, inclusive marcacoes unilaterais.
-- Nao confundir is_mutual de uma linha com duas marcacoes efetivas.
-- A leitura das duas direcoes e a fonte da verdade.
WITH canonical_directions AS (
    SELECT
        CASE WHEN LOWER(c.from_identity) < LOWER(c.to_identity)
            THEN LOWER(c.from_identity) || '<->' || LOWER(c.to_identity)
            ELSE LOWER(c.to_identity) || '<->' || LOWER(c.from_identity)
        END AS pair_key,
        CASE WHEN LOWER(c.from_identity) < LOWER(c.to_identity)
            THEN i1.id ELSE i2.id END AS identity_low,
        CASE WHEN LOWER(c.from_identity) < LOWER(c.to_identity)
            THEN i2.id ELSE i1.id END AS identity_high,
        LOWER(c.from_identity) < LOWER(c.to_identity) AS low_marked,
        c.created_at
    FROM connections c
    -- Bases historicas podem conter linhas orfas. Somente vinculos
    -- com ambas as identidades validas entram no backfill; utilizar i1.id
    -- e i2.id preserva a grafia canonica exigida pelas foreign keys.
    INNER JOIN identities i1 ON LOWER(i1.id) = LOWER(c.from_identity)
    INNER JOIN identities i2 ON LOWER(i2.id) = LOWER(c.to_identity)
    WHERE c.from_identity IS NOT NULL
      AND c.to_identity IS NOT NULL
      AND LOWER(c.from_identity) <> LOWER(c.to_identity)
),
grouped AS (
    SELECT
        pair_key,
        MIN(identity_low) AS identity_low,
        MIN(identity_high) AS identity_high,
        BOOL_OR(low_marked) AS low_marked,
        BOOL_OR(NOT low_marked) AS high_marked,
        MIN(created_at) AS first_created_at
    FROM canonical_directions
    GROUP BY pair_key
)
INSERT INTO connection_revisions
    (pair_key, identity_low, identity_high, current_state, revision, created_at, updated_at)
SELECT pair_key, identity_low, identity_high,
    CASE
        WHEN low_marked AND high_marked THEN 'MUTUAL'
        WHEN low_marked THEN 'MARKED_LOW'
        ELSE 'MARKED_HIGH'
    END,
    1, COALESCE(first_created_at, NOW()), NOW()
FROM grouped
ON CONFLICT (pair_key) DO NOTHING;

-- NAO fabricar eventos historicos de revogacao: DELETEs anteriores
-- nao podem ser reconstruidos a partir do estado atual.
COMMIT;

-- ROLLBACK OPERACIONAL: desabilitar primeiro os consumidores do novo
-- protocolo. Nao remover tabelas enquanto eventos/revisoes forem usados.
-- Nenhum DROP automatico nem alteracao da tabela connections.
