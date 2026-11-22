-- PessoasAqui Etapa 30E — index de busca de outbox por mensagem no GC.
-- PREPARE ONLY: aplicar somente em PostgreSQL isolado após 004–015.
-- Migration 004 already defines next_attempt_at and idx_r2_delete_retry;
-- this index addresses EXISTS(message_id, object_key) in discovery 30D.
BEGIN;
CREATE INDEX IF NOT EXISTS idx_r2_delete_outbox_pending_message_key
  ON public.message_r2_delete_outbox (message_id, object_key)
  WHERE state IN ('PENDING','RETRY');
COMMIT;
-- Do not apply in production or claim that it fixes all nested-loop plans
-- before comparing EXPLAIN (ANALYZE, BUFFERS) in test data.
