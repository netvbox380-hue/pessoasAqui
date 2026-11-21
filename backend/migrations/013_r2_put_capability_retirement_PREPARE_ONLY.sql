-- PessoasAqui R2 Etapa 28: durable PUT capability retirement fence.
-- PREPARE ONLY — never apply to Supabase production without authorization.
-- Each object key gets one persistent ledger row; never delete this row when
-- deleting the corresponding R2 object (tombstone prevents late reissuance).
BEGIN;
CREATE TABLE IF NOT EXISTS public.r2_put_capability_windows (
  object_key varchar(256) PRIMARY KEY,
  expires_at timestamptz NOT NULL,
  sealed_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  changed_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  CONSTRAINT r2_put_window_key CHECK (
    object_key ~ '^pending/[0-9a-f]{2}/[0-9a-f]{2}/[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
  ),
  CONSTRAINT r2_put_window_seal CHECK (sealed_at IS NULL OR sealed_at >= created_at)
);
CREATE INDEX IF NOT EXISTS r2_put_windows_seal_deadline
  ON public.r2_put_capability_windows (expires_at)
  WHERE sealed_at IS NOT NULL;
ALTER TABLE public.r2_put_capability_windows ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.r2_put_capability_windows FROM PUBLIC;
DO $roles$
BEGIN
  IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='anon') THEN
    REVOKE ALL ON public.r2_put_capability_windows FROM anon;
  END IF;
  IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='authenticated') THEN
    REVOKE ALL ON public.r2_put_capability_windows FROM authenticated;
  END IF;
END $roles$;
COMMIT;
-- IMPORTANT: This is only a metadata fence. Old capabilities not recorded
-- before migration are NOT covered and must never be assumed expired.
-- No automatic worker or R2 DeleteObject is activated by this migration.
