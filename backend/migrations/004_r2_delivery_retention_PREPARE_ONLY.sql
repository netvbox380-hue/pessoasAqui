-- PessoasAqui / Etapa R2-48h / MIGRACAO ADITIVA PROPOSTA — NAO EXECUTAR SEM HOMOLOGACAO
-- Versao de base: 35d3fd7. Somente DDL; nenhum DELETE, TRUNCATE ou UPDATE em registros.
-- O servidor atual ignora todas estas colunas e tabelas. R2 e TTL seguem DESATIVADOS.
-- IMPORTANTE: nao altera `ciphertext_payload`, `is_delivered` nem `is_cancelled`.
-- Aplicar em PostgreSQL isolado, executar suites; depois planejar janela de producao.
-- Na producao atual e2ee_messages foi esvaziada em 2026-11-14.
--
-- accepted_at e expires_at sao preenchidos por valores do SERVIDOR DB.
-- Para registros legados, nao inventar prazo historico: backfill auditado,
-- depois da inspeção de `created_at`; nenhum expurgo automatico nesta fase.

BEGIN;

ALTER TABLE public.e2ee_messages
  ADD COLUMN IF NOT EXISTS accepted_at timestamptz,
  ADD COLUMN IF NOT EXISTS expires_at timestamptz,
  ADD COLUMN IF NOT EXISTS delivery_state text;

ALTER TABLE public.e2ee_messages
  ADD CONSTRAINT e2ee_messages_delivery_state_chk
  CHECK (delivery_state IS NULL OR delivery_state IN ('PENDING','DELIVERED','EXPIRED','CANCELLED'));

-- Um recibo sem payload bloqueia reenvios com mesmo ID quando o conteudo sumir.
-- O digest deve ser construido no servidor com algoritmo e campos canonicos,
-- e nao conter plaintext, ciphertext ou segredos privados.
CREATE TABLE IF NOT EXISTS public.message_delivery_receipts (
  message_id varchar(256) PRIMARY KEY,
  sender_identity varchar(128) NOT NULL,
  recipient_identity varchar(128) NOT NULL,
  payload_digest char(64) NOT NULL,
  state text NOT NULL CHECK (state IN ('DELIVERED','EXPIRED','CANCELLED')),
  terminal_at timestamptz NOT NULL DEFAULT now(),
  cleanup_after timestamptz NOT NULL,
  CONSTRAINT message_receipt_valid_expiry CHECK (cleanup_after > terminal_at)
);

-- Eventos de expiracao e notificacoes sem conteudo: podem sobreviver a exclusao
-- do objeto R2 e das linhas e2ee_messages, com TTL independente e limite de quota.
CREATE TABLE IF NOT EXISTS public.message_expiration_notices (
  notice_id uuid PRIMARY KEY,
  message_id varchar(256) NOT NULL UNIQUE,
  sender_identity varchar(128) NOT NULL,
  recipient_identity varchar(128) NOT NULL,
  expired_at timestamptz NOT NULL,
  acknowledged_at timestamptz,
  cleanup_after timestamptz NOT NULL,
  CONSTRAINT message_notice_valid_expiry CHECK (cleanup_after > expired_at)
);

-- R2 e PostgreSQL nao compartilham transacao: outbox de DeleteObject duravel.
-- A chave opaca nunca deve conter identidade real nem informacao legivel.
-- Somente R2; nao armazenar token, URL assinada ou chave privada.
CREATE TABLE IF NOT EXISTS public.message_r2_delete_outbox (
  object_key text PRIMARY KEY,
  message_id varchar(256) NOT NULL,
  state text NOT NULL DEFAULT 'PENDING'
    CHECK (state IN ('PENDING','RETRY','DONE')),
  requested_at timestamptz NOT NULL DEFAULT now(),
  attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
  next_attempt_at timestamptz NOT NULL DEFAULT now(),
  completed_at timestamptz,
  last_error_code varchar(128)
);

CREATE INDEX IF NOT EXISTS idx_e2ee_pending_expires_at
  ON public.e2ee_messages (expires_at)
  WHERE delivery_state = 'PENDING';

CREATE INDEX IF NOT EXISTS idx_receipts_cleanup
  ON public.message_delivery_receipts (cleanup_after);
CREATE INDEX IF NOT EXISTS idx_expiration_notices_recipient
  ON public.message_expiration_notices (recipient_identity, expired_at);
CREATE INDEX IF NOT EXISTS idx_expiration_notices_cleanup
  ON public.message_expiration_notices (cleanup_after);
CREATE INDEX IF NOT EXISTS idx_r2_delete_retry
  ON public.message_r2_delete_outbox (next_attempt_at)
  WHERE state IN ('PENDING','RETRY');

COMMIT;

-- NOTAS DE IMPLEMENTACAO (fora da transacao):
-- 1) NAO habilitar expurgo automatico antes de cliente suportar expiração.
-- 2) Despacho, POLL, ACK e expiração devem concorrer sob lock no MESMO registro.
-- 3) Quando usar R2, salvar referencia autenticada de objeto em estrutura
--    adicional vinculada a messageId, sem misturar URLs temporarias em payload.
-- 4) A preservacao de tombstone deve ter limite de tempo e de espaco; definir
--    janela de retentativa antes de criar GC.
-- 5) A politica de 48h se aplica a entrega confirmada, nao leitura da UI.
-- 6) TTL R2 de 2 dias e complementar; nao substitui expurgo transacional.
