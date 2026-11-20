-- PESSOASAQUI / R2 ETAPA 24 — DURABLE REVOCATION HINTS.
-- PREPARE ONLY: do not run against Supabase production.
-- The writer MUST insert the event in the SAME transaction as the
-- authoritative connection/message revocation. pg_notify delivers on COMMIT.
BEGIN;
CREATE TABLE IF NOT EXISTS public.r2_stream_revocation_events (
  event_id uuid PRIMARY KEY,
  event_kind varchar(8) NOT NULL
    CHECK (event_kind IN ('MESSAGE','PAIR')),
  message_id varchar(256),
  identity_a varchar(16),
  identity_b varchar(16),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  CONSTRAINT r2_stream_revocation_shape CHECK (
    (event_kind='MESSAGE' AND message_id IS NOT NULL
      AND length(message_id)>0
      AND identity_a IS NULL AND identity_b IS NULL)
    OR
    (event_kind='PAIR' AND message_id IS NULL
      AND identity_a ~ '^[A-F0-9]{16}$'
      AND identity_b ~ '^[A-F0-9]{16}$'
      AND identity_a <> identity_b)
  )
);
CREATE INDEX IF NOT EXISTS r2_stream_revocations_recent
  ON public.r2_stream_revocation_events (created_at DESC, event_id);
ALTER TABLE public.r2_stream_revocation_events ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.r2_stream_revocation_events FROM PUBLIC;
DO $roles$
BEGIN
  IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='anon') THEN
    REVOKE ALL ON public.r2_stream_revocation_events FROM anon;
  END IF;
  IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='authenticated') THEN
    REVOKE ALL ON public.r2_stream_revocation_events FROM authenticated;
  END IF;
END $roles$;
COMMIT;
-- NO triggers, listeners, R2 routes, cleanup cron or production grants.
-- Retain events longer than the maximum permitted stream session duration
-- plus reconnection margin. Do NOT use a sequence cursor: event ID order
-- and transaction COMMIT order are not equivalent.
