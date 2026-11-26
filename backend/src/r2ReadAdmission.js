'use strict';

/**
 * PessoasAqui — staged R2 recipient download admission.
 *
 * Combines the Etapa 15 authenticated R2_READ gate, Etapa 20 exact per-object
 * authorization and a persistent quota in ONE PostgreSQL transaction.
 * This deliberately does NOT emit a URL, serve bytes or acknowledge delivery.
 *
 * IMPORTANT: the caller must pass identity/peer from a validated request
 * subject and the proof from Android, never spoofed authenticatedIdentity.
 * The active public key is always read from the identity binding in the DB.
 */
const { executeAuthenticatedR2Action } = require('./r2ActionAuth');
const { authorizeRecipientR2Read } = require('./r2RecipientReadAuth');

const { authorizeWinningRecipientR2Read } = require('./r2WinningRecipientReadAuth');

const MAX_DAILY_READS = 24;
const MAX_DAILY_READ_BYTES = 300 * 1024 * 1024;
const MAX_READ_BYTES = 25 * 1024 * 1024;

async function admitReadWithMode({
  withTransaction, identity, peerIdentity, messageId, objectKey, proof,
  now = Date.now()
}, winnerMode) {
  if (typeof withTransaction !== 'function') {
    throw new TypeError('PostgreSQL transaction required');
  }
  const verification = await executeAuthenticatedR2Action({
    withTransaction, operation: 'R2_READ', identity, peerIdentity,
    fields: { messageId, objectKey }, proof, now,
    action: async (tx, ctx) => {
      const authorize = winnerMode ? authorizeWinningRecipientR2Read : authorizeRecipientR2Read;
      const allowed = await authorize({
        tx, authenticatedIdentity: ctx.authenticatedIdentity,
        peerIdentity: ctx.peerIdentity, messageId, objectKey
      });
      if (!allowed.allowed) return allowed;
      const recipient = allowed.authenticatedIdentity;
      if (!Number.isSafeInteger(allowed.expectedBytes) ||
          allowed.expectedBytes < 1 || allowed.expectedBytes > MAX_READ_BYTES) {
        return { allowed: false, code: 'R2_READ_SIZE_INVALID', statusCode: 409 };
      }
      // Both quota modifications occur in the SAME transaction as the
      // authenticated proof nonce and the ownership/terminal checks.
      // Postgres clock is authoritative for the UTC accounting day.
      await tx(
        `INSERT INTO public.r2_read_daily_budget(identity_id,day_utc)
          VALUES($1,(clock_timestamp() AT TIME ZONE 'UTC')::date)
          ON CONFLICT DO NOTHING`, [recipient]
      );
      const charged = await tx(
        `UPDATE public.r2_read_daily_budget
            SET attempts=attempts+1,
                reserved_bytes=reserved_bytes+$2
          WHERE identity_id=$1
            AND day_utc=(clock_timestamp() AT TIME ZONE 'UTC')::date
            AND attempts < $3
            AND reserved_bytes <= ($4::bigint - $2::bigint)
          RETURNING attempts,reserved_bytes`,
        [recipient, allowed.expectedBytes, MAX_DAILY_READS, MAX_DAILY_READ_BYTES]
      );
      if (charged.rowCount !== 1) {
        return { allowed: false, statusCode: 429, code: 'R2_READ_QUOTA_EXCEEDED' };
      }
      // All fields below are server-derived and informational ONLY.
      // They are not a transferable bearer token / public download URL.
      return Object.freeze({
        allowed: true,
        authenticatedIdentity: recipient,
        peerIdentity: allowed.peerIdentity,
        messageId: allowed.messageId, objectKey: allowed.objectKey,
        expectedSha256: allowed.expectedSha256,
        expectedBytes: allowed.expectedBytes,
        ...(winnerMode ? { winnerMode: true, identityKey: proof.publicKey } : {})
      });
    }
  });
  if (!verification.authorized) return verification;
  if (!verification.value.allowed) return {
    authorized: false, ...verification.value
  };
  return { authorized: true, admitted: true, value: verification.value };
}

function admitAuthenticatedR2Read(args) { return admitReadWithMode(args, false); }
function admitAuthenticatedWinningR2Read(args) { return admitReadWithMode(args, true); }

module.exports = {
  admitAuthenticatedWinningR2Read,
  MAX_DAILY_READS, MAX_DAILY_READ_BYTES,
  admitAuthenticatedR2Read
};
