-- PessoasAqui R2 / etapa 6 — reforço de integridade de exclusão
-- STAGING ONLY. NAO EXECUTAR EM SUPABASE DE PRODUCAO.
-- Depends on migrations 004 and 005.
--
-- AUDIT FIX: previous reports described an outbox terminal-state guarantee
-- that wasn't enforced by SQL. This migration makes it explicit.
--
-- IMPORTANT: atomic terminalization service must atomically:
--   1. lock the message AND its R2 object,
--   2. create terminal receipt,
--   3. transition media object ATTACHED => DELETE_QUEUED,
--   4. insert outbox deletion request,
-- in the SAME PostgreSQL transaction.
--
-- Reserved/uploaded orphan cleanup is NOT authorized by this trigger.
-- A dedicated verified orphan transition will be designed separately.
BEGIN;

CREATE OR REPLACE FUNCTION public.pessoasaqui_guard_r2_delete_outbox()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $function$
DECLARE
  object_state text;
  receipt_state text;
BEGIN
  -- Lock the exact media record so no concurrent transition can undo the guard.
  SELECT mo.state INTO object_state
    FROM public.message_r2_objects AS mo
   WHERE mo.object_key = NEW.object_key
     AND mo.message_id = NEW.message_id
   FOR UPDATE;
  IF NOT FOUND OR object_state <> 'DELETE_QUEUED' THEN
    RAISE EXCEPTION 'R2 deletion requires terminal queued media'
      USING ERRCODE = '23514';
  END IF;

  SELECT r.state INTO receipt_state
    FROM public.message_delivery_receipts AS r
   WHERE r.message_id = NEW.message_id;
  IF NOT FOUND OR receipt_state NOT IN ('DELIVERED','EXPIRED','CANCELLED') THEN
    RAISE EXCEPTION 'R2 deletion requires terminal message receipt'
      USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END
$function$;

DROP TRIGGER IF EXISTS pessoasaqui_guard_r2_delete_outbox_trg
  ON public.message_r2_delete_outbox;

CREATE TRIGGER pessoasaqui_guard_r2_delete_outbox_trg
BEFORE INSERT OR UPDATE OF object_key, message_id
ON public.message_r2_delete_outbox
FOR EACH ROW
EXECUTE FUNCTION public.pessoasaqui_guard_r2_delete_outbox();

COMMIT;

-- MIGRATION NOTE: no app imports / queries use the new trigger yet.
-- Validate DB-version compatibility and REVOKE/TRIGGER permissions in staging.
-- No public RLS policies are created.
