-- PessoasAqui / ETAPA 30Y-C1: physical claim binding + immutable winner pin.
-- PREPARE ONLY. Never apply to production before isolated PG18.1 audit.
-- Requires migrations 018 and 019; no existing data is backfilled.
BEGIN;

-- The existing FK on object_key alone does NOT protect against forged
-- message, generation or sender in a manually inserted claim.
ALTER TABLE public.message_r2_upload_attempts
  ADD CONSTRAINT r2_attempt_full_claim_reference
  UNIQUE (object_key,message_id,sender_identity,generation);

ALTER TABLE public.r2_attempt_verification_claims
  ADD CONSTRAINT r2_claim_attempt_exact_binding
  FOREIGN KEY (object_key,message_id,identity_id,generation)
  REFERENCES public.message_r2_upload_attempts
    (object_key,message_id,sender_identity,generation)
  ON UPDATE RESTRICT ON DELETE RESTRICT;

-- Immutable digest/length, actor and physical-attempt provenance survive
-- failed or expired claims. Retries may replace claim_id, never descriptor.
CREATE OR REPLACE FUNCTION public.pessoasaqui_guard_r2_attempt_claim_binding()
RETURNS trigger LANGUAGE plpgsql
SET search_path=pg_catalog,public
AS $fn$
BEGIN
  IF NEW.object_key IS DISTINCT FROM OLD.object_key
     OR NEW.message_id IS DISTINCT FROM OLD.message_id
     OR NEW.identity_id IS DISTINCT FROM OLD.identity_id
     OR NEW.generation IS DISTINCT FROM OLD.generation
     OR NEW.content_sha256 IS DISTINCT FROM OLD.content_sha256
     OR NEW.content_bytes IS DISTINCT FROM OLD.content_bytes
  THEN
    RAISE EXCEPTION 'R2 physical claim immutable binding violated'
      USING ERRCODE='23514';
  END IF;
  IF NEW.state IS DISTINCT FROM OLD.state
     AND NOT (
       (OLD.state='RUNNING' AND NEW.state IN ('FAILED','COMPLETED'))
       OR (OLD.state='FAILED' AND NEW.state='RUNNING')
     )
  THEN
    RAISE EXCEPTION 'R2 physical claim transition invalid'
      USING ERRCODE='23514';
  END IF;
  IF OLD.state='COMPLETED' AND
     (NEW.claim_id IS DISTINCT FROM OLD.claim_id
      OR NEW.lease_expires_at IS DISTINCT FROM OLD.lease_expires_at
      OR NEW.completed_at IS DISTINCT FROM OLD.completed_at)
  THEN
    RAISE EXCEPTION 'R2 completed claim immutable'
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END
$fn$;

DROP TRIGGER IF EXISTS pessoasaqui_guard_r2_attempt_claim_binding_trg
  ON public.r2_attempt_verification_claims;
CREATE TRIGGER pessoasaqui_guard_r2_attempt_claim_binding_trg
BEFORE UPDATE ON public.r2_attempt_verification_claims
FOR EACH ROW EXECUTE FUNCTION public.pessoasaqui_guard_r2_attempt_claim_binding();

-- Winner is separate from the legacy message_r2_objects.object_key.
-- Its unique message_id establishes first-verified-wins; after INSERT,
-- key/claim/digest/generation are never re-bound, not even after retries.
CREATE TABLE IF NOT EXISTS public.message_r2_winning_attempts (
  message_id varchar(256) PRIMARY KEY
    REFERENCES public.message_r2_objects(message_id) ON DELETE RESTRICT,
  object_key text NOT NULL UNIQUE,
  generation integer NOT NULL CHECK (generation BETWEEN 1 AND 5),
  sender_identity varchar(16) NOT NULL
    CHECK (sender_identity ~ '^[A-F0-9]{16}$'),
  recipient_identity varchar(16) NOT NULL
    CHECK (recipient_identity ~ '^[A-F0-9]{16}$'
       AND recipient_identity <> sender_identity),
  claim_id uuid NOT NULL UNIQUE,
  content_sha256 char(64) NOT NULL
    CHECK (content_sha256 ~ '^[a-f0-9]{64}$'),
  content_bytes bigint NOT NULL
    CHECK (content_bytes BETWEEN 1 AND 26214400),
  selected_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  CONSTRAINT r2_winner_exact_attempt
    FOREIGN KEY(object_key,message_id,sender_identity,generation)
    REFERENCES public.message_r2_upload_attempts
      (object_key,message_id,sender_identity,generation)
    ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT r2_winner_verified_claim
    FOREIGN KEY(claim_id) REFERENCES public.r2_attempt_verification_claims(claim_id)
    ON UPDATE RESTRICT ON DELETE RESTRICT
);

CREATE OR REPLACE FUNCTION public.pessoasaqui_guard_r2_winner_immutable()
RETURNS trigger LANGUAGE plpgsql
SET search_path=pg_catalog,public
AS $fn$
BEGIN
  RAISE EXCEPTION 'R2 winning attempt is permanently immutable'
    USING ERRCODE='23514';
END
$fn$;
DROP TRIGGER IF EXISTS pessoasaqui_guard_r2_winner_immutable_trg
  ON public.message_r2_winning_attempts;
CREATE TRIGGER pessoasaqui_guard_r2_winner_immutable_trg
BEFORE UPDATE OR DELETE ON public.message_r2_winning_attempts
FOR EACH ROW EXECUTE FUNCTION public.pessoasaqui_guard_r2_winner_immutable();

ALTER TABLE public.message_r2_winning_attempts ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.message_r2_winning_attempts FROM PUBLIC;
DO $roles$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname='anon') THEN
    REVOKE ALL ON public.message_r2_winning_attempts FROM anon;
  END IF;
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname='authenticated') THEN
    REVOKE ALL ON public.message_r2_winning_attempts FROM authenticated;
  END IF;
END $roles$;
COMMIT;

-- This table is only a future pinning primitive. Its FK proves claim row
-- existence, NOT that claim.state=COMPLETED, not an independent GET SHA256,
-- not that the PUT cannot still be in flight, nor that content is E2EE.
-- NEVER INSERT a winner from client-supplied verification flags.
-- A later audited, backend-only finalizer must validate all of those inside
-- the same transaction and block legacy ATTACH/READ/GC until integrated.
