-- PessoasAqui / R2 / Etapa 11 - ORPHAN GC: PREPARATORY ONLY
-- DO NOT APPLY TO PRODUCTION. Requires migrations 004,005,006,007.
-- DO NOT START WORKERS until upload lease and signed URL enforcement exist.
-- No user rows are purged; no production code imports these objects.
BEGIN;

-- Explicit upload lease: nullable so old RESERVED records CANNOT be GC'd
-- without an audited backfill. Lease timestamps must be set by PostgreSQL
-- when a new authenticated reservation is made, never from Android clocks.
ALTER TABLE public.message_r2_objects
  ADD COLUMN IF NOT EXISTS upload_lease_expires_at timestamptz,
  ADD COLUMN IF NOT EXISTS upload_generation integer NOT NULL DEFAULT 0;

ALTER TABLE public.message_r2_objects
  ADD CONSTRAINT message_r2_upload_generation_valid
    CHECK (upload_generation >= 0);

-- Separate orphan terminal states. Never fake DELIVERY receipts or bypass
-- the migration 006 live-message outbox protections.
ALTER TABLE public.message_r2_objects
  DROP CONSTRAINT IF EXISTS message_r2_objects_state_check;
ALTER TABLE public.message_r2_objects
  ADD CONSTRAINT message_r2_objects_state_check CHECK (
    state IN ('RESERVED','UPLOADED','ATTACHED','DELETE_QUEUED','DELETED',
              'ORPHAN_QUEUED','ORPHAN_DELETED')
  );
ALTER TABLE public.message_r2_objects
  DROP CONSTRAINT IF EXISTS message_r2_objects_completed_fields_chk;
ALTER TABLE public.message_r2_objects
  ADD CONSTRAINT message_r2_objects_completed_fields_chk CHECK (
    state IN ('RESERVED','ORPHAN_QUEUED','ORPHAN_DELETED')
    OR (content_sha256 IS NOT NULL AND content_bytes IS NOT NULL AND uploaded_at IS NOT NULL)
  );

CREATE TABLE IF NOT EXISTS public.message_r2_orphan_outbox (
  object_key text PRIMARY KEY REFERENCES public.message_r2_objects(object_key),
  message_id varchar(256) NOT NULL,
  generation integer NOT NULL CHECK (generation >= 0),
  state text NOT NULL DEFAULT 'PENDING'
    CHECK (state IN ('PENDING','RETRY','DONE')),
  requested_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
  next_attempt_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  completed_at timestamptz,
  last_error_code varchar(64)
);
ALTER TABLE public.message_r2_orphan_outbox ENABLE ROW LEVEL SECURITY;
CREATE INDEX IF NOT EXISTS idx_r2_orphan_retry
  ON public.message_r2_orphan_outbox(next_attempt_at)
  WHERE state IN ('PENDING','RETRY');

-- Replace 007 state guard rather than disable it: retain every existing
-- protection and add only a narrowly conditional orphan path.
CREATE OR REPLACE FUNCTION public.pessoasaqui_guard_media_state_transition()
RETURNS trigger LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $func$
BEGIN
  IF NEW.object_key IS DISTINCT FROM OLD.object_key
     OR NEW.message_id IS DISTINCT FROM OLD.message_id
     OR NEW.sender_identity IS DISTINCT FROM OLD.sender_identity
     OR NEW.recipient_identity IS DISTINCT FROM OLD.recipient_identity
     OR NEW.content_sha256 IS DISTINCT FROM OLD.content_sha256
     OR NEW.content_bytes IS DISTINCT FROM OLD.content_bytes
     OR NEW.upload_generation IS DISTINCT FROM OLD.upload_generation
     OR NEW.upload_lease_expires_at IS DISTINCT FROM OLD.upload_lease_expires_at
  THEN
    IF NOT (
      OLD.state='RESERVED' AND NEW.state='UPLOADED'
      AND NEW.object_key=OLD.object_key AND NEW.message_id=OLD.message_id
      AND NEW.sender_identity=OLD.sender_identity
      AND NEW.recipient_identity=OLD.recipient_identity
      AND OLD.content_sha256 IS NULL AND OLD.content_bytes IS NULL
      AND NEW.content_sha256 IS NOT NULL AND NEW.content_bytes IS NOT NULL
      AND NEW.upload_generation=OLD.upload_generation
      AND NEW.upload_lease_expires_at IS NOT DISTINCT FROM OLD.upload_lease_expires_at
    ) THEN
      RAISE EXCEPTION 'R2 media immutable binding violated' USING ERRCODE='23514';
    END IF;
  END IF;

  IF NEW.state IS DISTINCT FROM OLD.state THEN
    IF NOT (
      (OLD.state='RESERVED' AND NEW.state='UPLOADED')
      OR (OLD.state='UPLOADED' AND NEW.state='ATTACHED')
      OR (OLD.state='ATTACHED' AND NEW.state='DELETE_QUEUED')
      OR (OLD.state='DELETE_QUEUED' AND NEW.state='DELETED')
      OR (OLD.state IN ('RESERVED','UPLOADED') AND NEW.state='ORPHAN_QUEUED')
      OR (OLD.state='ORPHAN_QUEUED' AND NEW.state='ORPHAN_DELETED')
    ) THEN
      RAISE EXCEPTION 'Invalid R2 media state transition' USING ERRCODE='23514';
    END IF;

    IF NEW.state='ORPHAN_QUEUED' THEN
      -- IMPORTANT: a 4h quarantine alone is NOT proof that a presigned PUT
      -- cannot finish late. Actual GC must also ensure upload is fenced by
      -- an authenticated gateway or immutable per-attempt object key.
      IF OLD.upload_lease_expires_at IS NULL
         OR OLD.upload_lease_expires_at > clock_timestamp()-interval '3 hours'
         OR OLD.reserved_at > clock_timestamp()-interval '4 hours'
         OR (OLD.state='UPLOADED'
             AND (OLD.uploaded_at IS NULL
                  OR OLD.uploaded_at > clock_timestamp()-interval '4 hours'))
         OR EXISTS (SELECT 1 FROM public.e2ee_messages m WHERE m.id=OLD.message_id)
         OR EXISTS (SELECT 1 FROM public.message_delivery_receipts r
                    WHERE r.message_id=OLD.message_id)
      THEN
        RAISE EXCEPTION 'Orphan GC lease or ownership not proven'
          USING ERRCODE='23514';
      END IF;
    END IF;

    IF NEW.state='DELETED' AND NOT EXISTS (
       SELECT 1 FROM public.message_r2_delete_outbox o
        WHERE o.object_key=NEW.object_key AND o.message_id=NEW.message_id
          AND o.state='DONE'
    ) THEN
      RAISE EXCEPTION 'R2 media requires completed deletion outbox'
        USING ERRCODE='23514';
    END IF;
    IF NEW.state='ORPHAN_DELETED' AND NOT EXISTS (
       SELECT 1 FROM public.message_r2_orphan_outbox o
        WHERE o.object_key=NEW.object_key AND o.message_id=NEW.message_id
          AND o.generation=NEW.upload_generation AND o.state='DONE'
    ) THEN
      RAISE EXCEPTION 'Orphan media requires completed orphan outbox'
        USING ERRCODE='23514';
    END IF;
  END IF;
  RETURN NEW;
END
$func$;

-- Independent orphan outbox is guarded at SQL layer. The ordinary
-- message deletion outbox remains governed by migration 006.
CREATE OR REPLACE FUNCTION public.pessoasaqui_guard_r2_orphan_outbox()
RETURNS trigger LANGUAGE plpgsql
SET search_path=pg_catalog, public
AS $func$
DECLARE
  media_state text;
  media_generation integer;
  media_message_id varchar(256);
BEGIN
  SELECT state, upload_generation, message_id
    INTO media_state, media_generation, media_message_id
    FROM public.message_r2_objects
   WHERE object_key=NEW.object_key FOR UPDATE;
  IF NOT FOUND OR media_state <> 'ORPHAN_QUEUED'
     OR media_generation IS DISTINCT FROM NEW.generation
     OR media_message_id IS DISTINCT FROM NEW.message_id
     OR EXISTS (SELECT 1 FROM public.e2ee_messages m WHERE m.id=NEW.message_id)
     OR EXISTS (SELECT 1 FROM public.message_delivery_receipts r
                WHERE r.message_id=NEW.message_id)
  THEN
    RAISE EXCEPTION 'R2 orphan deletion not authorized' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END
$func$;
DROP TRIGGER IF EXISTS pessoasaqui_guard_r2_orphan_outbox_trg
  ON public.message_r2_orphan_outbox;
CREATE TRIGGER pessoasaqui_guard_r2_orphan_outbox_trg
BEFORE INSERT OR UPDATE OF object_key, message_id, generation
ON public.message_r2_orphan_outbox FOR EACH ROW
EXECUTE FUNCTION public.pessoasaqui_guard_r2_orphan_outbox();

COMMIT;

-- OPEN SECURITY GATE:
-- * A presigned URL issued before GC may still allow late PUT: SQL lease
--   does not revoke that URL. Guarantee no same-key re-upload after DELETE,
--   e.g. server-mediated upload or per-attempt immutable key with verification.
-- * Do not activate orphan workers or insert orphan jobs until hardened
--   URL/PUT contract, request authentication, R2 lifecycle and recovery tests.
-- * Do not backfill upload_lease_expires_at for old objects automatically.
