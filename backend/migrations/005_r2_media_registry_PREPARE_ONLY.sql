-- PessoasAqui / R2 MEDIA REGISTRY — PREPARATORY ONLY
-- NAO EXECUTAR NO SUPABASE DE PRODUCAO. SEM ALTERACOES EM server.js.
-- Faz rastreio de objetos ANTES do upload; permite limpeza de upload orfao.
-- Upload R2 somente apos COMMIT da reserva. O worker de lixo nao deve
-- remover um objeto enquanto upload/finalizacao estiverem em progresso.
BEGIN;

CREATE TABLE IF NOT EXISTS public.message_r2_objects (
  object_key text PRIMARY KEY,
  message_id varchar(256) NOT NULL UNIQUE,
  sender_identity varchar(128) NOT NULL,
  recipient_identity varchar(128) NOT NULL,
  content_sha256 char(64),
  content_bytes bigint,
  state text NOT NULL DEFAULT 'RESERVED'
    CHECK (state IN ('RESERVED','UPLOADED','ATTACHED','DELETE_QUEUED','DELETED')),
  reserved_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  uploaded_at timestamptz,
  attached_at timestamptz,
  last_changed_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  CONSTRAINT message_r2_objects_content_size_chk
    CHECK (content_bytes IS NULL OR content_bytes BETWEEN 1 AND 26214400),
  CONSTRAINT message_r2_objects_sha256_chk
    CHECK (content_sha256 IS NULL OR content_sha256 ~ '^[a-f0-9]{64}$'),
  CONSTRAINT message_r2_objects_completed_fields_chk
    CHECK (state = 'RESERVED' OR (content_sha256 IS NOT NULL
                                  AND content_bytes IS NOT NULL
                                  AND uploaded_at IS NOT NULL))
);

ALTER TABLE public.message_r2_objects ENABLE ROW LEVEL SECURITY;
-- No anon/authenticated policies: backend PostgreSQL role only.
CREATE INDEX IF NOT EXISTS idx_r2_objects_state_reserved
  ON public.message_r2_objects (reserved_at) WHERE state = 'RESERVED';
CREATE INDEX IF NOT EXISTS idx_r2_objects_state_uploaded
  ON public.message_r2_objects (uploaded_at) WHERE state = 'UPLOADED';

COMMIT;

-- Nao ativar ate:
-- * integrar validação da identidade remetente e vinculo mutuo em DB;
-- * persistir envelope E2EE + referencia R2 em uma mesma transacao;
-- * garantir limpeza de orphan de RESERVED/UPLOADED com lock e tempo seguro;
-- * planejar reenvio idempotente, concorrencia, reinicio e revogacao;
-- * confirmar ACK apenas depois de download integral e persistencia Android;
-- * enfileirar DeleteObject no mesmo commit do estado terminal.
