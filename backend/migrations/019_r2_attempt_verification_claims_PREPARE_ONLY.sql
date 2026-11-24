-- PessoasAqui / Etapa 30Y-B: physical-attempt verification claims.
-- PREPARE ONLY: requires migrations 001-018. NO PRODUCTION MIGRATION.
-- Never substitute this table for a verified ciphertext hash or an attachment.
BEGIN;
CREATE TABLE IF NOT EXISTS public.r2_attempt_verification_claims (
  object_key text PRIMARY KEY
    REFERENCES public.message_r2_upload_attempts(object_key) ON DELETE RESTRICT,
  message_id varchar(256) NOT NULL,
  identity_id varchar(16) NOT NULL
    CHECK (identity_id ~ '^[A-F0-9]{16}$'),
  generation integer NOT NULL CHECK (generation BETWEEN 1 AND 5),
  claim_id uuid NOT NULL UNIQUE,
  content_sha256 char(64) NOT NULL
    CHECK (content_sha256 ~ '^[a-f0-9]{64}$'),
  content_bytes bigint NOT NULL CHECK (content_bytes BETWEEN 1 AND 26214400),
  state varchar(16) NOT NULL DEFAULT 'RUNNING'
    CHECK (state IN ('RUNNING','COMPLETED','FAILED')),
  started_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  lease_expires_at timestamptz NOT NULL,
  completed_at timestamptz,
  CONSTRAINT r2_attempt_claim_binding UNIQUE
    (object_key,message_id,identity_id,generation)
);
CREATE INDEX IF NOT EXISTS idx_r2_attempt_claims_live
  ON public.r2_attempt_verification_claims(lease_expires_at)
  WHERE state='RUNNING';
ALTER TABLE public.r2_attempt_verification_claims ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.r2_attempt_verification_claims FROM PUBLIC;
DO $roles$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname='anon') THEN
    REVOKE ALL ON public.r2_attempt_verification_claims FROM anon;
  END IF;
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname='authenticated') THEN
    REVOKE ALL ON public.r2_attempt_verification_claims FROM authenticated;
  END IF;
END $roles$;
COMMIT;
-- The claim is not media UPLOADED. No GET/PUT, no winner selection and no
-- physical delete are permitted solely because this record exists.
