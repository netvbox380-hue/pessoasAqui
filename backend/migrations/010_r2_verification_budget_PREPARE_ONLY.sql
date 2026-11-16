-- PessoasAqui R2 / ETAPA 18 — DB-backed verification admission control.
-- PREPARE ONLY. NEVER APPLY TO PRODUCTION WITHOUT SEPARATE APPROVAL.
-- Requires message_r2_objects (004-008) and r2_action_nonces (009).
BEGIN;

CREATE TABLE IF NOT EXISTS public.r2_verification_daily_budget (
  identity_id varchar(128) NOT NULL,
  day_utc date NOT NULL,
  attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
  reserved_bytes bigint NOT NULL DEFAULT 0 CHECK (reserved_bytes >= 0),
  PRIMARY KEY (identity_id, day_utc)
);

CREATE TABLE IF NOT EXISTS public.r2_verification_claims (
  object_key text PRIMARY KEY REFERENCES public.message_r2_objects(object_key),
  message_id varchar(256) NOT NULL,
  identity_id varchar(128) NOT NULL,
  generation integer NOT NULL CHECK (generation = 1),
  claim_id uuid NOT NULL,
  state varchar(16) NOT NULL DEFAULT 'RUNNING'
    CHECK (state IN ('RUNNING','COMPLETED','FAILED')),
  started_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  lease_expires_at timestamptz NOT NULL,
  completed_at timestamptz
);

CREATE INDEX IF NOT EXISTS idx_r2_verification_claims_lease
  ON public.r2_verification_claims (lease_expires_at)
  WHERE state='RUNNING';

ALTER TABLE public.r2_verification_daily_budget ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.r2_verification_claims ENABLE ROW LEVEL SECURITY;

REVOKE ALL ON public.r2_verification_daily_budget FROM PUBLIC;
REVOKE ALL ON public.r2_verification_claims FROM PUBLIC;

DO $roles$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname='anon') THEN
    REVOKE ALL ON public.r2_verification_daily_budget FROM anon;
    REVOKE ALL ON public.r2_verification_claims FROM anon;
  END IF;
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname='authenticated') THEN
    REVOKE ALL ON public.r2_verification_daily_budget FROM authenticated;
    REVOKE ALL ON public.r2_verification_claims FROM authenticated;
  END IF;
END $roles$;

COMMIT;

-- Maintenance of daily budget and completed claims is deliberately absent.
-- Retain quotas through each UTC day and a safe retention margin; audit
-- data retention and least-privilege backend DB roles before deployment.
