-- PessoasAqui 30Y-E2: physical retirement intent and permanent PUT fence.
-- PREPARE ONLY. Apply only to disposable PG after 001-023.
-- No storage deletion, worker activation or changes to legacy transitions.
BEGIN;
CREATE FUNCTION public.pessoasaqui_guard_put_window_immutable()
RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog,public AS $fn$
BEGIN
  IF TG_OP='DELETE' THEN
    RAISE EXCEPTION 'R2 PUT provenance cannot be deleted' USING ERRCODE='23514';
  END IF;
  IF NEW.object_key IS DISTINCT FROM OLD.object_key
     OR NEW.created_at IS DISTINCT FROM OLD.created_at
     OR NEW.expires_at < OLD.expires_at
     OR (OLD.sealed_at IS NOT NULL AND
        (NEW.sealed_at IS DISTINCT FROM OLD.sealed_at OR NEW.expires_at IS DISTINCT FROM OLD.expires_at))
  THEN
    RAISE EXCEPTION 'R2 PUT window immutable or monotonicity violated' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $fn$;
CREATE TRIGGER pessoasaqui_put_window_immutable_trg
BEFORE UPDATE OR DELETE ON public.r2_put_capability_windows
FOR EACH ROW EXECUTE FUNCTION public.pessoasaqui_guard_put_window_immutable();

-- Exact physical provenance, no duplicate digest or ciphertext. This is an
-- irreversible retirement INTENT, not authorization for physical DELETE.
-- Keep the logical parent ATTACHED and the winner VERIFIED permanently.
ALTER TABLE public.message_r2_upload_attempts
  ADD CONSTRAINT r2_attempt_retirement_exact_ref UNIQUE(object_key,message_id,generation);
CREATE TABLE public.r2_physical_retirement_intents (
  object_key text PRIMARY KEY,
  message_id varchar(256) NOT NULL,
  generation integer NOT NULL CHECK(generation BETWEEN 1 AND 5),
  reason text NOT NULL CHECK(reason IN ('DELIVERED','EXPIRED','CANCELLED','ABANDONED')),
  retired_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  CONSTRAINT r2_retirement_attempt_exact_fk FOREIGN KEY(object_key,message_id,generation)
    REFERENCES public.message_r2_upload_attempts(object_key,message_id,generation)
    ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT r2_retirement_put_window_fk FOREIGN KEY(object_key)
    REFERENCES public.r2_put_capability_windows(object_key) ON UPDATE RESTRICT ON DELETE RESTRICT
);
CREATE INDEX r2_retirement_message_idx ON public.r2_physical_retirement_intents(message_id);
ALTER TABLE public.r2_physical_retirement_intents ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.r2_physical_retirement_intents FROM PUBLIC;
DO $roles$
BEGIN
  IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='anon') THEN
    REVOKE ALL ON public.r2_physical_retirement_intents FROM anon;
  END IF;
  IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='authenticated') THEN
    REVOKE ALL ON public.r2_physical_retirement_intents FROM authenticated;
  END IF;
END $roles$;

CREATE FUNCTION public.pessoasaqui_guard_physical_retirement()
RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog,public AS $fn$
DECLARE
  p public.message_r2_objects%ROWTYPE;
  a public.message_r2_upload_attempts%ROWTYPE;
  w public.message_r2_winning_attempts%ROWTYPE;
  d public.message_delivery_receipts%ROWTYPE;
BEGIN
  IF TG_OP<>'INSERT' THEN
    RAISE EXCEPTION 'R2 physical retirement intent immutable' USING ERRCODE='23514';
  END IF;
  -- Match the parent-first lock order of allocation, issuance and attach.
  SELECT * INTO p FROM public.message_r2_objects WHERE message_id=NEW.message_id FOR UPDATE;
  IF NOT FOUND OR p.object_key=NEW.object_key THEN
    RAISE EXCEPTION 'R2 retirement requires distinct physical key' USING ERRCODE='23514';
  END IF;
  SELECT * INTO a FROM public.message_r2_upload_attempts
    WHERE object_key=NEW.object_key AND message_id=NEW.message_id AND generation=NEW.generation FOR UPDATE;
  IF NOT FOUND OR a.sender_identity<>p.sender_identity OR a.recipient_identity<>p.recipient_identity THEN
    RAISE EXCEPTION 'R2 retirement attempt binding invalid' USING ERRCODE='23514';
  END IF;
  SELECT * INTO w FROM public.message_r2_winning_attempts WHERE message_id=NEW.message_id;
  IF NEW.reason='ABANDONED' THEN
    IF a.state<>'ABANDONED' OR w.object_key=NEW.object_key OR
       EXISTS(SELECT 1 FROM public.e2ee_messages WHERE r2_winning_object_key=NEW.object_key)
       OR EXISTS(SELECT 1 FROM public.r2_attempt_verification_claims WHERE object_key=NEW.object_key AND state='RUNNING')
    THEN
      RAISE EXCEPTION 'R2 abandoned retirement not proven' USING ERRCODE='23514';
    END IF;
  ELSE
    SELECT * INTO d FROM public.message_delivery_receipts WHERE message_id=NEW.message_id FOR SHARE;
    IF NOT FOUND OR d.state<>NEW.reason OR d.sender_identity<>p.sender_identity
       OR d.recipient_identity<>p.recipient_identity OR p.r2_winner_attach_mode IS NOT TRUE
       OR p.state<>'ATTACHED' OR a.state<>'VERIFIED'
       OR w.object_key IS DISTINCT FROM NEW.object_key OR w.generation IS DISTINCT FROM NEW.generation
       OR EXISTS(SELECT 1 FROM public.e2ee_messages WHERE id=NEW.message_id)
    THEN
      RAISE EXCEPTION 'R2 winner retirement requires terminal receipt and absent envelope' USING ERRCODE='23514';
    END IF;
  END IF;
  PERFORM 1 FROM public.r2_put_capability_windows WHERE object_key=NEW.object_key AND sealed_at IS NOT NULL FOR UPDATE;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'R2 retirement requires sealed PUT window' USING ERRCODE='23514';
  END IF;
  NEW.retired_at:=clock_timestamp();
  RETURN NEW;
END $fn$;
CREATE TRIGGER pessoasaqui_physical_retirement_guard_trg
BEFORE INSERT OR UPDATE OR DELETE ON public.r2_physical_retirement_intents
FOR EACH ROW EXECUTE FUNCTION public.pessoasaqui_guard_physical_retirement();

-- Permanent message tombstone uses the winner's retirement intent, not
-- expiring receipts. Lock parent to serialize with terminalization.
CREATE FUNCTION public.pessoasaqui_guard_retired_message_insert()
RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog,public AS $fn$
BEGIN
  PERFORM 1 FROM public.message_r2_objects WHERE message_id=NEW.id FOR UPDATE;
  IF EXISTS(SELECT 1 FROM public.r2_physical_retirement_intents
      WHERE message_id=NEW.id AND reason<>'ABANDONED') THEN
    RAISE EXCEPTION 'R2 terminal message cannot be resurrected' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $fn$;
CREATE TRIGGER pessoasaqui_retired_message_insert_trg
BEFORE INSERT ON public.e2ee_messages
FOR EACH ROW EXECUTE FUNCTION public.pessoasaqui_guard_retired_message_insert();
COMMIT;
-- Still blocked: role isolation, cross-registry key collision, distributed
-- read drain, late PUT proof, physical claim/outcome protocol, retention.
-- No intent grants permission to call DeleteObject.
