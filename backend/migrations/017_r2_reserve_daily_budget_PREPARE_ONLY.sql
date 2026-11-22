-- PessoasAqui / R2 ETAPA 30S: persistent per-identity reservation quota.
-- PREPARE ONLY: depends on migrations 004-016.
-- NEVER apply to Supabase production without an explicit operator-approved
-- backup, migration plan, role audit and rollout.
BEGIN;
CREATE TABLE IF NOT EXISTS public.r2_reserve_daily_budget (
  identity_id varchar(16) NOT NULL CHECK (identity_id ~ '^[A-F0-9]{16}$'),
  day_utc date NOT NULL,
  reservations integer NOT NULL DEFAULT 0
    CHECK (reservations >= 0 AND reservations <= 24),
  PRIMARY KEY (identity_id, day_utc)
);
ALTER TABLE public.r2_reserve_daily_budget ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.r2_reserve_daily_budget FROM PUBLIC;
DO $roles$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname='anon') THEN
    REVOKE ALL ON public.r2_reserve_daily_budget FROM anon;
  END IF;
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname='authenticated') THEN
    REVOKE ALL ON public.r2_reserve_daily_budget FROM authenticated;
  END IF;
END $roles$;
COMMIT;
-- Only backend-controlled PostgreSQL transaction role may access quota.
-- A quota is not a substitute for cleanup, anti-abuse, storage budget or
-- per-message validation. No R2 network operations, cron or triggers here.
