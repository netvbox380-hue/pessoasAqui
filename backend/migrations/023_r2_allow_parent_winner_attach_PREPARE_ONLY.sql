-- PessoasAqui / Stage 30Y-D1-FIX — winner-specific parent ATTACH.
-- PREPARE ONLY. Do NOT apply to Supabase production or activate R2.
-- Requires migrations 001-022. Preserves legacy 008 state machine and GC.
BEGIN;

-- Explicit, immutable discriminator. All prior rows get false and unchanged
-- legacy CHECK. Only a fully verified physical winner can set true, and ONLY
-- in the same RESERVED->ATTACHED UPDATE. The old logical key is never served.
ALTER TABLE public.message_r2_objects
  ADD COLUMN r2_winner_attach_mode boolean NOT NULL DEFAULT FALSE;

ALTER TABLE public.message_r2_objects
  DROP CONSTRAINT message_r2_objects_completed_fields_chk;
ALTER TABLE public.message_r2_objects
  ADD CONSTRAINT message_r2_objects_completed_fields_chk CHECK (
    (r2_winner_attach_mode=FALSE AND state IN ('RESERVED','ORPHAN_QUEUED','ORPHAN_DELETED'))
    OR (r2_winner_attach_mode=FALSE
        AND content_sha256 IS NOT NULL
        AND content_bytes IS NOT NULL
        AND uploaded_at IS NOT NULL)
    OR (r2_winner_attach_mode=TRUE AND state='ATTACHED'
        AND content_sha256 IS NULL
        AND content_bytes IS NULL
        AND uploaded_at IS NULL
        AND attached_at IS NOT NULL)
  );

-- Replacement keeps every allowed legacy transition and immutable field
-- guard from migration 008; inserts exactly one conditional new transition.
-- Validity of winner must be reconfirmed AT THE POINT OF PARENT UPDATE.
CREATE OR REPLACE FUNCTION public.pessoasaqui_guard_media_state_transition()
RETURNS trigger LANGUAGE plpgsql
SET search_path=pg_catalog,public
AS $fn$
DECLARE
  winner_ok boolean := FALSE;
BEGIN
  IF OLD.r2_winner_attach_mode=TRUE AND
      (NEW.r2_winner_attach_mode IS DISTINCT FROM OLD.r2_winner_attach_mode
       OR NEW.state IS DISTINCT FROM OLD.state) THEN
    RAISE EXCEPTION 'R2 winner attached parent is immutable pending audited GC'
      USING ERRCODE='23514';
  END IF;

  IF NEW.r2_winner_attach_mode IS DISTINCT FROM OLD.r2_winner_attach_mode
     AND NOT (
       OLD.r2_winner_attach_mode=FALSE
       AND NEW.r2_winner_attach_mode=TRUE
       AND OLD.state='RESERVED' AND NEW.state='ATTACHED'
     ) THEN
    RAISE EXCEPTION 'R2 winner attach mode transition invalid'
      USING ERRCODE='23514';
  END IF;

  -- The only new transition requires the special mode flag, immutable legacy
  -- parent descriptors, and a freshly authorized physical winning attempt.
  IF OLD.state='RESERVED' AND NEW.state='ATTACHED'
     AND OLD.r2_winner_attach_mode=FALSE
     AND NEW.r2_winner_attach_mode=TRUE
  THEN
    IF NEW.object_key IS DISTINCT FROM OLD.object_key
       OR NEW.message_id IS DISTINCT FROM OLD.message_id
       OR NEW.sender_identity IS DISTINCT FROM OLD.sender_identity
       OR NEW.recipient_identity IS DISTINCT FROM OLD.recipient_identity
       OR NEW.content_sha256 IS DISTINCT FROM OLD.content_sha256
       OR NEW.content_bytes IS DISTINCT FROM OLD.content_bytes
       OR NEW.uploaded_at IS DISTINCT FROM OLD.uploaded_at
       OR NEW.upload_generation IS DISTINCT FROM OLD.upload_generation
       OR NEW.upload_lease_expires_at IS DISTINCT FROM OLD.upload_lease_expires_at
       OR OLD.content_sha256 IS NOT NULL OR OLD.content_bytes IS NOT NULL
       OR OLD.uploaded_at IS NOT NULL
       OR NEW.attached_at IS NULL
       OR OLD.upload_generation<>1
       OR OLD.upload_lease_expires_at IS NULL
       OR OLD.upload_lease_expires_at<=clock_timestamp()
    THEN
      RAISE EXCEPTION 'R2 winner parent immutable fields or lease invalid'
        USING ERRCODE='23514';
    END IF;
    SELECT EXISTS (
      SELECT 1
      FROM public.message_r2_winning_attempts w
      JOIN public.message_r2_upload_attempts a
        ON a.object_key=w.object_key AND a.message_id=w.message_id
           AND a.generation=w.generation
      JOIN public.r2_attempt_verification_claims c
        ON c.claim_id=w.claim_id AND c.object_key=w.object_key
           AND c.message_id=w.message_id
           AND c.generation=w.generation
      JOIN public.e2ee_messages e
        ON e.id=w.message_id AND e.r2_winning_object_key=w.object_key
      WHERE w.message_id=NEW.message_id
        AND w.sender_identity=NEW.sender_identity
        AND w.recipient_identity=NEW.recipient_identity
        AND w.object_key<>NEW.object_key
        AND a.state='VERIFIED'
        AND a.sender_identity=w.sender_identity
        AND a.recipient_identity=w.recipient_identity
        AND c.state='COMPLETED' AND c.completed_at IS NOT NULL
        AND c.identity_id=w.sender_identity
        AND c.content_sha256=w.content_sha256
        AND c.content_bytes=w.content_bytes
        AND e.sender_identity=NEW.sender_identity
        AND e.recipient_identity=NEW.recipient_identity
        AND e.delivery_state='PENDING'
        AND e.is_delivered=FALSE AND e.is_cancelled IS NOT TRUE
        AND NOT EXISTS (
          SELECT 1 FROM public.message_delivery_receipts d
           WHERE d.message_id=NEW.message_id
        )
    ) INTO winner_ok;
    IF NOT winner_ok THEN
      RAISE EXCEPTION 'R2 winner attach requires completed verified exact E2EE binding'
        USING ERRCODE='23514';
    END IF;
    RETURN NEW;
  END IF;

  -- Unchanged migration 008 immutable legacy fields contract.
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
      RAISE EXCEPTION 'R2 media immutable binding violated'
        USING ERRCODE='23514';
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
      RAISE EXCEPTION 'Invalid R2 media state transition'
        USING ERRCODE='23514';
    END IF;

    IF NEW.state='ORPHAN_QUEUED' THEN
      IF OLD.upload_lease_expires_at IS NULL
         OR OLD.upload_lease_expires_at > clock_timestamp()-interval '3 hours'
         OR OLD.reserved_at > clock_timestamp()-interval '4 hours'
         OR (OLD.state='UPLOADED'
             AND (OLD.uploaded_at IS NULL
                  OR OLD.uploaded_at > clock_timestamp()-interval '4 hours'))
         OR EXISTS (SELECT 1 FROM public.e2ee_messages m WHERE m.id=OLD.message_id)
         OR EXISTS (SELECT 1 FROM public.message_delivery_receipts d
                    WHERE d.message_id=OLD.message_id)
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
$fn$;
COMMIT;

-- Security scope:
-- (1) Winner mode can never transition to legacy DELETE_QUEUED: old GC uses
--     the logical object's key, NOT the physical winner. GC remains blocked.
-- (2) A DB owner could still forge rows/states; restrict application DML
--     to audited SECURITY DEFINER functions before production rollout.
-- (3) SQL trigger checks transactional relational state, not R2 bucket WORM,
--     E2EE authenticity or actual bytes: cutover still prohibited.
