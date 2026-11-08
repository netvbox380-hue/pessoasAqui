-- =========================================================================
-- PESSOASAQUI — MIGRAÇÃO ADITIVA V2.1: SEPARAÇÃO DE ESTADOS DE REVOGAÇÃO E ENTREGA
-- Status: PENDENTE DE APROVAÇÃO EXPRESSA DO PROPRIETÁRIO
-- AVISO: NÃO EXECUTAR AUTOMATICAMENTE EM PRODUÇÃO
-- =========================================================================
--
-- OBJETIVO:
-- Adicionar as colunas is_cancelled e cancelled_at na tabela e2ee_messages
-- para separar inequivocamente mensagens entregues (is_delivered = TRUE)
-- de mensagens canceladas por revogação bilateral (is_cancelled = TRUE).
--
-- CARACTERÍSTICAS:
-- 1. 100% Aditiva e não-destrutiva (não apaga dados, não bloqueia tabelas).
-- 2. Retrocompatível com clientes anteriores e consultas legadas.
-- 3. Totalmente reversível pelo bloco DOWN documentado abaixo.
-- =========================================================================

-- -------------------------------------------------------------------------
-- UP: APLICAÇÃO DA MIGRAÇÃO
-- -------------------------------------------------------------------------
ALTER TABLE e2ee_messages ADD COLUMN IF NOT EXISTS is_cancelled BOOLEAN DEFAULT FALSE;
ALTER TABLE e2ee_messages ADD COLUMN IF NOT EXISTS cancelled_at TIMESTAMPTZ;
ALTER TABLE e2ee_messages ADD COLUMN IF NOT EXISTS delivered_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_e2ee_messages_undelivered_uncancelled 
  ON e2ee_messages (recipient_identity, is_delivered) 
  WHERE is_delivered = FALSE AND (is_cancelled IS NULL OR is_cancelled = FALSE);

-- -------------------------------------------------------------------------
-- DOWN: REVERSÃO COMPLETA E SEGURA (Caso necessário reverter)
-- -------------------------------------------------------------------------
-- DROP INDEX IF EXISTS idx_e2ee_messages_undelivered_uncancelled;
-- ALTER TABLE e2ee_messages DROP COLUMN IF EXISTS delivered_at;
-- ALTER TABLE e2ee_messages DROP COLUMN IF EXISTS cancelled_at;
-- ALTER TABLE e2ee_messages DROP COLUMN IF EXISTS is_cancelled;
