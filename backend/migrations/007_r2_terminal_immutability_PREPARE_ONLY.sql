-- PessoasAqui R2 / Etapa 8 — terminais imutaveis, ESTAGIADO
-- PREPARE ONLY: executar apenas em PostgreSQL isolado; NAO em producao.
-- Depends on 004, 005 and 006. No mutation of existing data.
--
-- Previous audits confirmed application-level state checks but PRIMARY KEY
-- and CHECK constraints alone do not prevent updates to terminal receipts.
-- These triggers close that gap for ordinary DB writes.
BEGIN;

CREATE OR REPLACE FUNCTION public.pessoasaqui_guard_terminal_receipt()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $function$
BEGIN
  IF TG_OP = 'UPDATE' THEN
    RAISE EXCEPTION 'Terminal delivery receipt cannot be modified'
      USING ERRCODE = '23514';
  END IF;
  IF TG_OP = 'DELETE' THEN
    IF OLD.cleanup_after > clock_timestamp() THEN
      RAISE EXCEPTION 'Terminal receipt retention still active'
        USING ERRCODE = '23514';
    END IF;
    -- The receipt must survive while any R2 delete job remains unfinished.
    IF EXISTS (
      SELECT 1 FROM public.message_r2_delete_outbox AS o
       WHERE o.message_id = OLD.message_id AND o.state <> 'DONE'
    ) THEN
      RAISE EXCEPTION 'Cannot purge receipt with unfinished R2 deletion'
        USING ERRCODE = '23514';
    END IF;
  END IF;
  RETURN OLD;
END
$function$;

DROP TRIGGER IF EXISTS pessoasaqui_terminal_receipt_immutability_trg
  ON public.message_delivery_receipts;

CREATE TRIGGER pessoasaqui_terminal_receipt_immutability_trg
BEFORE UPDATE OR DELETE ON public.message_delivery_receipts
FOR EACH ROW EXECUTE FUNCTION public.pessoasaqui_guard_terminal_receipt();

CREATE OR REPLACE FUNCTION public.pessoasaqui_guard_media_state_transition()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $function$
BEGIN
  IF NEW.object_key IS DISTINCT FROM OLD.object_key
     OR NEW.message_id IS DISTINCT FROM OLD.message_id
     OR NEW.sender_identity IS DISTINCT FROM OLD.sender_identity
     OR NEW.recipient_identity IS DISTINCT FROM OLD.recipient_identity
     OR NEW.content_sha256 IS DISTINCT FROM OLD.content_sha256
     OR NEW.content_bytes IS DISTINCT FROM OLD.content_bytes THEN
    -- Completion of upload is the only phase permitted to add digest/size.
    IF NOT (
       OLD.state = 'RESERVED' AND NEW.state = 'UPLOADED'
       AND NEW.object_key = OLD.object_key
       AND NEW.message_id = OLD.message_id
       AND NEW.sender_identity = OLD.sender_identity
       AND NEW.recipient_identity = OLD.recipient_identity
       AND OLD.content_sha256 IS NULL AND OLD.content_bytes IS NULL
       AND NEW.content_sha256 IS NOT NULL AND NEW.content_bytes IS NOT NULL
    ) THEN
      RAISE EXCEPTION 'R2 media immutable binding violated'
        USING ERRCODE = '23514';
    END IF;
  END IF;

  IF NEW.state IS DISTINCT FROM OLD.state THEN
    IF NOT (
      (OLD.state = 'RESERVED' AND NEW.state = 'UPLOADED')
      OR (OLD.state = 'UPLOADED' AND NEW.state = 'ATTACHED')
      OR (OLD.state = 'ATTACHED' AND NEW.state = 'DELETE_QUEUED')
      OR (OLD.state = 'DELETE_QUEUED' AND NEW.state = 'DELETED')
    ) THEN
      RAISE EXCEPTION 'Invalid R2 media state transition'
        USING ERRCODE = '23514';
    END IF;
    IF NEW.state = 'DELETED' AND NOT EXISTS (
      SELECT 1 FROM public.message_r2_delete_outbox o
       WHERE o.object_key = NEW.object_key
         AND o.message_id = NEW.message_id AND o.state = 'DONE'
    ) THEN
      RAISE EXCEPTION 'R2 media requires completed deletion outbox'
        USING ERRCODE = '23514';
    END IF;
  END IF;
  RETURN NEW;
END
$function$;

DROP TRIGGER IF EXISTS pessoasaqui_media_state_transition_trg
  ON public.message_r2_objects;

CREATE TRIGGER pessoasaqui_media_state_transition_trg
BEFORE UPDATE ON public.message_r2_objects
FOR EACH ROW EXECUTE FUNCTION public.pessoasaqui_guard_media_state_transition();

COMMIT;

-- Orphan cleanup must use a separately reviewed workflow; the linear
-- transition guard intentionally disallows deleting RESERVED/UPLOADED objects.
-- PostgreSQL table owners/superusers can disable triggers; protect DB roles.
-- Test all legitimate transitions, GC and concurrent ACK/expiry in isolation.
