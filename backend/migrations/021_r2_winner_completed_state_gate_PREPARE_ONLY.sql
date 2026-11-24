-- PessoasAqui / Stage 30Y-C2A: mandatory state gate for winning attempt.
-- PREPARE ONLY; NEVER EXECUTE AGAINST PRODUCTION.
-- Requires migration 020. A composite FK alone cannot verify completion.
BEGIN;

CREATE OR REPLACE FUNCTION public.pessoasaqui_guard_r2_winner_verified_state()
RETURNS trigger LANGUAGE plpgsql
SET search_path=pg_catalog,public
AS $fn$
DECLARE
  verified_claim boolean;
  verified_attempt boolean;
  valid_parent boolean;
BEGIN
  -- Avoid acquiring additional row locks here. An authorized finalizer must
  -- lock parent -> attempt -> claim before INSERT in one transaction. These
  -- SELECTs see updates from that same transaction.
  SELECT EXISTS(
    SELECT 1 FROM public.r2_attempt_verification_claims c
     WHERE c.claim_id=NEW.claim_id
       AND c.object_key=NEW.object_key
       AND c.message_id=NEW.message_id
       AND c.identity_id=NEW.sender_identity
       AND c.generation=NEW.generation
       AND c.content_sha256=NEW.content_sha256
       AND c.content_bytes=NEW.content_bytes
       AND c.state='COMPLETED' AND c.completed_at IS NOT NULL
  ) INTO verified_claim;
  SELECT EXISTS(
    SELECT 1 FROM public.message_r2_upload_attempts a
     WHERE a.object_key=NEW.object_key AND a.message_id=NEW.message_id
       AND a.generation=NEW.generation
       AND a.sender_identity=NEW.sender_identity
       AND a.recipient_identity=NEW.recipient_identity
       AND a.state='VERIFIED'
  ) INTO verified_attempt;
  SELECT EXISTS(
    SELECT 1 FROM public.message_r2_objects m
     WHERE m.message_id=NEW.message_id AND
       m.sender_identity=NEW.sender_identity AND
       m.recipient_identity=NEW.recipient_identity AND
       m.state='RESERVED'
       AND m.upload_generation=1
       AND m.upload_lease_expires_at>clock_timestamp()
  ) INTO valid_parent;
  IF NOT verified_claim OR NOT verified_attempt OR NOT valid_parent THEN
    RAISE EXCEPTION 'R2 winner requires completed matching claim and verified attempt'
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END
$fn$;

DROP TRIGGER IF EXISTS pessoasaqui_guard_r2_winner_verified_state_trg
  ON public.message_r2_winning_attempts;
CREATE TRIGGER pessoasaqui_guard_r2_winner_verified_state_trg
BEFORE INSERT ON public.message_r2_winning_attempts
FOR EACH ROW EXECUTE FUNCTION public.pessoasaqui_guard_r2_winner_verified_state();

COMMIT;

-- This guard closes the known 30Y-C1 direct INSERT-from-RUNNING path.
-- It does NOT prove that COMPLETED was earned via physical GET rather than
-- malicious privileged direct SQL. Rollout requires dedicated DB roles /
-- function permissions and full READ/ATTACH/GC migration.
