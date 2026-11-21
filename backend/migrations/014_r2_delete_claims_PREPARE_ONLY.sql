-- PESSOASAQUI R2 Etapa 30B — durable GC claims (PREPARE ONLY).
-- Apply ONLY to an isolated PostgreSQL 18 test database after migrations 004–013.
-- No production migration, GC process, R2 DELETE or server activation.
BEGIN;
CREATE TABLE IF NOT EXISTS public.r2_delete_claims (
  object_key varchar(256) PRIMARY KEY,
  message_id varchar(256) NOT NULL,
  claim_token uuid NOT NULL,
  generation bigint NOT NULL DEFAULT 1
    CHECK (generation >= 1),
  lease_until timestamptz NOT NULL,
  attempts integer NOT NULL DEFAULT 1
    CHECK (attempts >= 1 AND attempts <= 1000000),
  completed_at timestamptz,
  changed_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  CONSTRAINT r2_gc_claim_key CHECK (
    object_key ~ '^pending/[0-9a-f]{2}/[0-9a-f]{2}/[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
  ),
  CONSTRAINT r2_gc_message_id CHECK (
    length(message_id) BETWEEN 1 AND 256
  )
);
CREATE INDEX IF NOT EXISTS r2_delete_claims_retry
  ON public.r2_delete_claims (lease_until)
  WHERE completed_at IS NULL;
ALTER TABLE public.r2_delete_claims ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.r2_delete_claims FROM PUBLIC;
DO $roles$
BEGIN
 IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='anon') THEN
   REVOKE ALL ON public.r2_delete_claims FROM anon;
 END IF;
 IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='authenticated') THEN
   REVOKE ALL ON public.r2_delete_claims FROM authenticated;
 END IF;
END $roles$;
COMMIT;
