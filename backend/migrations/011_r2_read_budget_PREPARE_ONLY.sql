-- PessoasAqui / R2 Stage 21 — download admission budget, PREPARE ONLY.
-- Requires migrations 004-010. NEVER APPLY TO PRODUCTION WITHOUT APPROVAL.
BEGIN;
CREATE TABLE IF NOT EXISTS public.r2_read_daily_budget (
  identity_id varchar(128) NOT NULL,
  day_utc date NOT NULL,
  attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
  reserved_bytes bigint NOT NULL DEFAULT 0 CHECK (reserved_bytes >= 0),
  PRIMARY KEY (identity_id, day_utc)
);
ALTER TABLE public.r2_read_daily_budget ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.r2_read_daily_budget FROM PUBLIC;
DO $roles$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname='anon') THEN
    REVOKE ALL ON public.r2_read_daily_budget FROM anon;
  END IF;
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname='authenticated') THEN
    REVOKE ALL ON public.r2_read_daily_budget FROM authenticated;
  END IF;
END $roles$;
COMMIT;
-- This only tracks admission, NOT transfer completion, GET authorization,
-- durable delivery receipts, object immutability, or cancellation midstream.
