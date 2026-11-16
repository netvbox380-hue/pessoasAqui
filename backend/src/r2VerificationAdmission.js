'use strict';

/**
 * PessoasAqui R2 — distributed integrity-verification admission (STAGED).
 *
 * Provides a persistent per-object single-flight claim and a conservative
 * per-identity UTC-day budget. No R2 I/O happens in this module.
 *
 * Must be called ONLY after verified R2_CONFIRM identity authorization
 * (Etapa 15), with actor from the cryptographic context, not request JSON.
 * A claim never proves that the object's bytes are valid, and never sets
 * media state to UPLOADED. The route must check real ciphertext bytes
 * outside a DB transaction and reauthorize/revalidate before confirmation.
 *
 * Requires migration 010; production imports are forbidden until rollout.
 */
const crypto = require('node:crypto');
const { validateOpaqueObjectKey } = require('./r2CiphertextAdapter');

const MAX_DAILY_VERIFICATIONS = 12;
const MAX_DAILY_VERIFIED_BYTES = 200 * 1024 * 1024;
const MAX_OBJECT_BYTES = 25 * 1024 * 1024;
// Must exceed the absolute maximum GET deadline (120s) plus settlement margin.
const VERIFICATION_CLAIM_SECONDS = 180;
const VALID_ID = /^[A-F0-9]{16}$/i;

function result(code, statusCode) {
  return { granted: false, code, statusCode };
}

/**
 * Serialize decisions on one media row, then one per-identity daily budget.
 * Both locks are transaction-scoped and shared across Render instances.
 * Charge *attempted* read bytes, including failure: prevents brute-force
 * retries from extracting unlimited bandwidth. No refund on failed GETs.
 */
async function claimR2Verification({
  withTransaction, authenticatedIdentity, recipientIdentity,
  objectKey, messageId, expectedBytes
}) {
  if (typeof withTransaction !== 'function') throw new TypeError('withTransaction required');
  validateOpaqueObjectKey(objectKey);
  if (!VALID_ID.test(authenticatedIdentity || '') ||
      !VALID_ID.test(recipientIdentity || '') ||
      authenticatedIdentity.toUpperCase() === recipientIdentity.toUpperCase() ||
      typeof messageId !== 'string' || !messageId || messageId.length > 256 ||
      /[\u0000-\u001f\u007f]/.test(messageId) ||
      !Number.isSafeInteger(expectedBytes) || expectedBytes < 1 ||
      expectedBytes > MAX_OBJECT_BYTES) {
    return result('INVALID_VERIFICATION_REQUEST', 400);
  }

  const identity = authenticatedIdentity.toUpperCase();
  const recipient = recipientIdentity.toUpperCase();
  const claimId = crypto.randomUUID();

  return withTransaction(async tx => {
    const mediaRes = await tx(
      `SELECT object_key, message_id, sender_identity, recipient_identity,
              state, upload_generation,
              (upload_lease_expires_at > clock_timestamp()) AS lease_active
         FROM public.message_r2_objects
        WHERE object_key=$1 AND message_id=$2 FOR UPDATE`,
      [objectKey, messageId]
    );
    if (mediaRes.rowCount !== 1) return result('R2_OBJECT_NOT_FOUND', 404);
    const media = mediaRes.rows[0];
    if (media.sender_identity.toUpperCase() !== identity ||
        media.recipient_identity.toUpperCase() !== recipient) {
      return result('R2_OBJECT_NOT_AUTHORIZED', 403);
    }
    // A previously confirmed object should never trigger another R2 GET.
    if (media.state === 'UPLOADED' || media.state === 'ATTACHED') {
      return result('R2_ALREADY_CONFIRMED', 409);
    }
    if (media.state !== 'RESERVED' || media.upload_generation !== 1 ||
        media.lease_active !== true) {
      return result('R2_RESERVATION_INACTIVE', 409);
    }

    await tx(
      `INSERT INTO public.r2_verification_daily_budget(identity_id,day_utc)
       VALUES($1,(clock_timestamp() AT TIME ZONE 'UTC')::date)
       ON CONFLICT DO NOTHING`,
      [identity]
    );
    const budgetRes = await tx(
      `SELECT attempts,reserved_bytes FROM public.r2_verification_daily_budget
        WHERE identity_id=$1 AND day_utc=(clock_timestamp() AT TIME ZONE 'UTC')::date
        FOR UPDATE`,
      [identity]
    );
    if (budgetRes.rowCount !== 1) throw Error('R2_BUDGET_UNAVAILABLE');
    const budget = budgetRes.rows[0];
    if (Number(budget.attempts) >= MAX_DAILY_VERIFICATIONS ||
        Number(budget.reserved_bytes) + expectedBytes > MAX_DAILY_VERIFIED_BYTES) {
      return result('R2_DAILY_VERIFY_QUOTA_EXCEEDED', 429);
    }

    // Claim is unique per object, across ALL backend instances.
    // Another ongoing claim cannot be replaced until its 90s lease expires.
    // Completed states can never be re-opened for duplicate downloads.
    const claimed = await tx(
      `INSERT INTO public.r2_verification_claims(
         object_key,message_id,identity_id,generation,claim_id,
         state,started_at,lease_expires_at)
       VALUES($1,$2,$3,1,$4,'RUNNING',clock_timestamp(),
              clock_timestamp()+($5::integer*interval '1 second'))
       ON CONFLICT(object_key) DO UPDATE SET
         claim_id=EXCLUDED.claim_id, state='RUNNING',
         started_at=clock_timestamp(),
         lease_expires_at=EXCLUDED.lease_expires_at,
         completed_at=NULL
       WHERE public.r2_verification_claims.state='FAILED'
          OR (public.r2_verification_claims.state='RUNNING'
              AND public.r2_verification_claims.lease_expires_at<clock_timestamp())
       RETURNING claim_id,lease_expires_at`,
      [objectKey,messageId,identity,claimId,VERIFICATION_CLAIM_SECONDS]
    );
    if (claimed.rowCount !== 1) return result('R2_VERIFICATION_IN_PROGRESS', 409);

    const charged = await tx(
      `UPDATE public.r2_verification_daily_budget
          SET attempts=attempts+1,reserved_bytes=reserved_bytes+$2
        WHERE identity_id=$1
          AND day_utc=(clock_timestamp() AT TIME ZONE 'UTC')::date
        RETURNING attempts,reserved_bytes`,
      [identity,expectedBytes]
    );
    if (charged.rowCount !== 1) throw Error('R2_BUDGET_CHARGE_FAILED');

    return { granted: true, claimId, objectKey, messageId,
      authenticatedIdentity: identity, recipientIdentity: recipient,
      generation: 1, expectedBytes, expiresAt: claimed.rows[0].lease_expires_at };
  });
}

/**
 * Releasing a failed attempt is best-effort, but completion must only be
 * called in the SAME DB transaction that finalizes the verified upload.
 * Callers must not trust a client-supplied claim ID.
 */
async function settleR2Verification({
  query, claimId, objectKey, authenticatedIdentity, successful
}) {
  if (typeof query !== 'function' || typeof claimId !== 'string' ||
      !/^[0-9a-f-]{36}$/i.test(claimId) ||
      !VALID_ID.test(authenticatedIdentity || '') ||
      typeof successful !== 'boolean') throw new TypeError('Invalid R2 claim');
  validateOpaqueObjectKey(objectKey);
  const r = await query(
    `UPDATE public.r2_verification_claims
        SET state=$4,completed_at=clock_timestamp()
      WHERE claim_id=$1 AND object_key=$2 AND identity_id=$3
        AND state='RUNNING' AND lease_expires_at>clock_timestamp()
      RETURNING claim_id`,
    [claimId,objectKey,authenticatedIdentity.toUpperCase(),
      successful ? 'COMPLETED':'FAILED']
  );
  return { settled: r.rowCount === 1 };
}

module.exports = {
  MAX_DAILY_VERIFICATIONS, MAX_DAILY_VERIFIED_BYTES, MAX_OBJECT_BYTES,
  VERIFICATION_CLAIM_SECONDS, claimR2Verification, settleR2Verification
};
