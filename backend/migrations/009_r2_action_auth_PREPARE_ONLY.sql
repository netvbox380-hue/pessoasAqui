-- PessoasAqui / Etapa 15 — R2 signed action nonces, PREPARE ONLY.
-- Must be tested on isolated PostgreSQL alongside migrations 004–008.
-- DO NOT APPLY TO SUPABASE PRODUCTION OR ENABLE R2 ROUTES.
BEGIN;

CREATE TABLE IF NOT EXISTS public.r2_action_nonces (
  identity_id varchar(128) NOT NULL,
  nonce uuid NOT NULL,
  consumed_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY(identity_id, nonce)
);

ALTER TABLE public.r2_action_nonces ENABLE ROW LEVEL SECURITY;
-- No policies are granted to anon/authenticated. Backend's trusted database
-- role must have only the necessary INSERT/SELECT privileges; service
-- credentials must never be present on the client.
REVOKE ALL ON public.r2_action_nonces FROM PUBLIC;
DO $block$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname='anon') THEN
    REVOKE ALL ON public.r2_action_nonces FROM anon;
  END IF;
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname='authenticated') THEN
    REVOKE ALL ON public.r2_action_nonces FROM authenticated;
  END IF;
END $block$;

CREATE INDEX IF NOT EXISTS idx_r2_action_nonces_consumed
  ON public.r2_action_nonces (consumed_at);
COMMIT;

-- Maintenance policy to be audited separately: retain nonce entries >=
-- the maximum accepted proof window + clock skew, then prune in bounded
-- batches. DO NOT implement a periodic production worker in this stage.
-- SECURITY: never call external R2 APIs from the nonce-consuming transaction;
-- the callback must contain only transactional database operations.
