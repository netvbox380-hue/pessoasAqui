-- PessoasAqui R2 / ETAPA 30X-IMPL: immutable per-attempt object keys.
-- PREPARE ONLY. NEVER EXECUTE ON SUPABASE PRODUCTION WITHOUT APPROVAL.
-- This adds an attempt registry; it does NOT replace or backfill existing
-- message_r2_objects rows. Do NOT switch PUT/CONFIRM/GC to this schema yet.
BEGIN;

CREATE TABLE IF NOT EXISTS public.message_r2_upload_attempts (
  object_key text PRIMARY KEY,
  message_id varchar(256) NOT NULL
    REFERENCES public.message_r2_objects(message_id) ON DELETE RESTRICT,
  generation integer NOT NULL CHECK (generation BETWEEN 1 AND 5),
  sender_identity varchar(16) NOT NULL
    CHECK (sender_identity ~ '^[A-F0-9]{16}$'),
  recipient_identity varchar(16) NOT NULL
    CHECK (recipient_identity ~ '^[A-F0-9]{16}$' AND recipient_identity <> sender_identity),
  state varchar(20) NOT NULL DEFAULT 'ALLOCATED'
    CHECK (state IN ('ALLOCATED','PUT_ISSUED','VERIFYING',
                    'VERIFIED','ABANDONED')),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  CONSTRAINT r2_attempt_unique_generation UNIQUE (message_id,generation),
  CONSTRAINT r2_attempt_object_key_format CHECK (
    object_key ~ '^pending/[0-9a-f]{2}/[0-9a-f]{2}/[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
  ),
  CONSTRAINT r2_attempt_prefix_matches_uuid CHECK (
    split_part(object_key,'/',2)=substring(split_part(object_key,'/',4),1,2)
    AND split_part(object_key,'/',3)=substring(split_part(object_key,'/',4),3,2)
  )
);

-- Prevent reassignment, key reuse, generation rewrites and deletion of
-- provenance records; physical R2 GC must not delete this SQL tombstone.
CREATE OR REPLACE FUNCTION public.pessoasaqui_guard_r2_attempt_binding()
RETURNS trigger LANGUAGE plpgsql
SET search_path=pg_catalog,public
AS $func$
BEGIN
  IF TG_OP='DELETE' THEN
    RAISE EXCEPTION 'R2 attempt provenance cannot be deleted' USING ERRCODE='23514';
  END IF;
  IF NEW.object_key IS DISTINCT FROM OLD.object_key
     OR NEW.message_id IS DISTINCT FROM OLD.message_id
     OR NEW.generation IS DISTINCT FROM OLD.generation
     OR NEW.sender_identity IS DISTINCT FROM OLD.sender_identity
     OR NEW.recipient_identity IS DISTINCT FROM OLD.recipient_identity
     OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
    RAISE EXCEPTION 'R2 attempt binding is immutable' USING ERRCODE='23514';
  END IF;
  IF NEW.state IS DISTINCT FROM OLD.state AND NOT (
       (OLD.state='ALLOCATED' AND NEW.state IN ('PUT_ISSUED','ABANDONED'))
    OR (OLD.state='PUT_ISSUED' AND NEW.state IN ('VERIFYING','ABANDONED'))
    OR (OLD.state='VERIFYING' AND NEW.state IN ('VERIFIED','ABANDONED'))
  ) THEN
    RAISE EXCEPTION 'Invalid R2 attempt state transition' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END
$func$;

DROP TRIGGER IF EXISTS pessoasaqui_guard_r2_attempt_binding_trg
  ON public.message_r2_upload_attempts;
CREATE TRIGGER pessoasaqui_guard_r2_attempt_binding_trg
BEFORE UPDATE OR DELETE ON public.message_r2_upload_attempts
FOR EACH ROW EXECUTE FUNCTION public.pessoasaqui_guard_r2_attempt_binding();

CREATE INDEX IF NOT EXISTS idx_r2_attempt_message_state
  ON public.message_r2_upload_attempts(message_id,state);
ALTER TABLE public.message_r2_upload_attempts ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.message_r2_upload_attempts FROM PUBLIC;
DO $roles$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname='anon') THEN
    REVOKE ALL ON public.message_r2_upload_attempts FROM anon;
  END IF;
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname='authenticated') THEN
    REVOKE ALL ON public.message_r2_upload_attempts FROM authenticated;
  END IF;
END $roles$;
COMMIT;

-- Independent pending work: ensure all old client PUT contracts are retired
-- before cutover; require signed R2_ISSUE_PUT for each physical attempt,
-- no key reuse, live-object hash/bytes check and atomic chosen-attempt binding.
-- This migration alone does NOT resolve TOCTOU or validate Bucket Locks.
