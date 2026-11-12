-- PESSOASAQUI / ETAPA 4B: RECONCILIACAO CONTROLADA DE REVISOES
-- NAO EXECUTAR EM PRODUCAO ANTES DE:
--   (1) aplicar backend/migrations/003_connection_monotonic_revisions.sql;
--   (2) suspender TODAS as instancias/escritores (inclusive convites LINK/QR);
--   (3) comprovar ausencia de writes em connections;
--   (4) realizar backup/snapshot de seguranca.
-- Executar SOMENTE no cutover, com escritores parados, e iniciar o backend
-- diretamente com CONNECTION_REVISIONS_ENABLED=true apos validar o resultado.
-- Um LOCK do PostgreSQL durante a transacao NAO substitui a suspensao
-- dos escritores apos o COMMIT.
--
-- Comparacao canonica: lower(id) || '<->' || lower(id), como server.js.
-- Nao fabricar eventos historicos; eventos 'PURGE' inseridos AQUI documentam
-- apenas reconciliacao administrativa, com actor_identity NULL.
--
-- Caso o SQL falhe: a transacao inteira e revertida. NAO remover validacoes.
BEGIN;

SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '120s';

-- Protecao adicional contra writes concorrentes DURANTE a reconciliacao.
-- Antes e depois desta transacao, a suspensao operacional e obrigatoria.
LOCK TABLE public.connections IN SHARE ROW EXCLUSIVE MODE;
LOCK TABLE public.connection_revisions IN SHARE ROW EXCLUSIVE MODE;
LOCK TABLE public.connection_events IN SHARE ROW EXCLUSIVE MODE;

DO $$
DECLARE
    row_pair RECORD;
    old_state VARCHAR(16);
    previous_revision BIGINT;
    next_revision BIGINT;
BEGIN
    IF EXISTS (
        SELECT 1 FROM public.identities
        GROUP BY LOWER(id) HAVING COUNT(*) > 1
    ) THEN
        RAISE EXCEPTION 'Cutover bloqueado: IDs em identities ambiguos sem considerar caixa';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM public.connections c
        LEFT JOIN public.identities i1 ON LOWER(i1.id) = LOWER(c.from_identity)
        LEFT JOIN public.identities i2 ON LOWER(i2.id) = LOWER(c.to_identity)
        WHERE c.from_identity IS NULL OR c.to_identity IS NULL
           OR LOWER(c.from_identity) = LOWER(c.to_identity)
           OR i1.id IS NULL OR i2.id IS NULL
    ) THEN
        RAISE EXCEPTION 'Cutover bloqueado: connections contem pares invalidos ou orfaos';
    END IF;

    -- Cada par ativo aparece uma vez, independentemente da direcao e
    -- da caixa historica dos IDs. Ambas as identidades devem existir.
    FOR row_pair IN
        WITH directions AS (
            SELECT
                CASE WHEN LOWER(c.from_identity) < LOWER(c.to_identity)
                    THEN LOWER(c.from_identity) || '<->' || LOWER(c.to_identity)
                    ELSE LOWER(c.to_identity) || '<->' || LOWER(c.from_identity)
                END AS pair_key,
                CASE WHEN LOWER(c.from_identity) < LOWER(c.to_identity)
                    THEN i1.id ELSE i2.id END AS identity_low,
                CASE WHEN LOWER(c.from_identity) < LOWER(c.to_identity)
                    THEN i2.id ELSE i1.id END AS identity_high,
                LOWER(c.from_identity) < LOWER(c.to_identity) AS low_marked
            FROM public.connections c
            INNER JOIN public.identities i1 ON LOWER(i1.id) = LOWER(c.from_identity)
            INNER JOIN public.identities i2 ON LOWER(i2.id) = LOWER(c.to_identity)
        )
        SELECT
            pair_key,
            MIN(identity_low) AS identity_low,
            MIN(identity_high) AS identity_high,
            CASE
                WHEN BOOL_OR(low_marked) AND BOOL_OR(NOT low_marked) THEN 'MUTUAL'
                WHEN BOOL_OR(low_marked) THEN 'MARKED_LOW'
                ELSE 'MARKED_HIGH'
            END::varchar(16) AS derived_state
        FROM directions GROUP BY pair_key
    LOOP
        SELECT current_state, revision INTO old_state, previous_revision
        FROM public.connection_revisions
        WHERE pair_key = row_pair.pair_key
        FOR UPDATE;

        IF NOT FOUND THEN
            -- Par sem revisao anterior: baseline atual; sem inventar
            -- evento de marcacao historico.
            INSERT INTO public.connection_revisions
                (pair_key, identity_low, identity_high, current_state, revision)
            VALUES
                (row_pair.pair_key, row_pair.identity_low, row_pair.identity_high,
                 row_pair.derived_state, 1);
        ELSIF old_state IS DISTINCT FROM row_pair.derived_state THEN
            UPDATE public.connection_revisions
            SET current_state = row_pair.derived_state,
                revision = revision + 1,
                updated_at = NOW()
            WHERE pair_key = row_pair.pair_key
            RETURNING revision INTO next_revision;

            INSERT INTO public.connection_events
                (pair_key, actor_identity, target_identity, action, resulting_state, revision)
            VALUES (row_pair.pair_key, NULL, NULL, 'PURGE',
                    row_pair.derived_state, next_revision);
        END IF;
    END LOOP;

    -- Tombstone duravel para pares que existem em revisions mas ja nao
    -- existem na tabela autoritativa connections (revogacao no legado).
    -- Nao excluir a linha nem zerar revision.
    FOR row_pair IN
        SELECT cr.pair_key, cr.current_state
        FROM public.connection_revisions cr
        WHERE cr.current_state <> 'NONE'
          AND NOT EXISTS (
              SELECT 1 FROM public.connections c
              WHERE
                (CASE WHEN LOWER(c.from_identity) < LOWER(c.to_identity)
                    THEN LOWER(c.from_identity) || '<->' || LOWER(c.to_identity)
                    ELSE LOWER(c.to_identity) || '<->' || LOWER(c.from_identity)
                END) = cr.pair_key
          )
        FOR UPDATE
    LOOP
        UPDATE public.connection_revisions
        SET current_state = 'NONE',
            revision = revision + 1,
            updated_at = NOW()
        WHERE pair_key = row_pair.pair_key
        RETURNING revision INTO next_revision;

        INSERT INTO public.connection_events
            (pair_key, actor_identity, target_identity, action, resulting_state, revision)
        VALUES (row_pair.pair_key, NULL, NULL, 'PURGE', 'NONE', next_revision);
    END LOOP;

    -- Verificacao estrutural: todo estado ativo deve bater com o legado.
    -- Inclui os pares que foram recentemente revogados (state NONE).
    IF EXISTS (
        WITH directions AS (
            SELECT
                CASE WHEN LOWER(from_identity) < LOWER(to_identity)
                    THEN LOWER(from_identity) || '<->' || LOWER(to_identity)
                    ELSE LOWER(to_identity) || '<->' || LOWER(from_identity)
                END AS pair_key,
                LOWER(from_identity) < LOWER(to_identity) AS low_marked
            FROM public.connections
        ), expected AS (
            SELECT pair_key,
                CASE
                    WHEN BOOL_OR(low_marked) AND BOOL_OR(NOT low_marked) THEN 'MUTUAL'
                    WHEN BOOL_OR(low_marked) THEN 'MARKED_LOW'
                    ELSE 'MARKED_HIGH'
                END AS state
            FROM directions GROUP BY pair_key
        )
        SELECT 1 FROM expected e
        LEFT JOIN public.connection_revisions cr ON cr.pair_key = e.pair_key
        WHERE cr.pair_key IS NULL OR cr.current_state IS DISTINCT FROM e.state
    ) THEN
        RAISE EXCEPTION 'Cutover bloqueado: revisoes nao correspondem a connections';
    END IF;
END $$;

COMMIT;

-- POST-CUTOVER: antes de restabelecer trafego, executar a verificacao
-- independente abaixo em modo somente leitura e confirmar zero divergencias.
-- A janela entre COMMIT e reinicio revisionado DEVE permanecer sem writes.
