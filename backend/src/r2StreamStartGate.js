'use strict';

/**
 * PessoasAqui / R2 — revocation-aware preflight at the stream boundary.
 * STAGED ONLY. No S3 GET, presigned URL, HTTP route, or client secrets.
 *
 * This is an ADDITIONAL authorization check, not the initial authentication.
 * The caller MUST first obtain an approved result from
 * admitAuthenticatedR2Read() (P-256 signed R2_READ + anti-replay + quota).
 * Do not accept that approved result directly from Android JSON.
 *
 * Re-checks bilateral connections and message/object state in a NEW, short
 * transaction immediately before opening the R2 stream. Does not claim that
 * revocation while bytes are in flight is prevented. That requires a backend
 * gateway with active stream cancellation, coordinated invalidation and
 * persistent state checks; pre-signed GET URLs cannot be revoked this way.
 */
const { authorizeRecipientR2Read } = require('./r2RecipientReadAuth');

const { authorizeWinningRecipientR2Read } = require('./r2WinningRecipientReadAuth');

function rejected(code, statusCode = 403) {
  return { allowed: false, statusCode, code };
}

async function revalidateR2ReadAtStreamStart({ withTransaction, admission }) {
  if (typeof withTransaction !== 'function') {
    throw new TypeError('PostgreSQL transaction required');
  }
  if (!admission || admission.authorized !== true ||
      admission.admitted !== true || !admission.value ||
      admission.value.allowed !== true) {
    return rejected('R2_PRIOR_ADMISSION_REQUIRED', 401);
  }
  const {
    authenticatedIdentity: recipient, peerIdentity: sender,
    messageId, objectKey, expectedSha256, expectedBytes, winnerMode, identityKey
  } = admission.value;

  // Validate that the admitted values are syntactically valid. The original
  // identity proof, not a client-supplied structure, must bind these fields.
  if (typeof sender !== 'string' || !/^[A-F0-9]{16}$/.test(sender) ||
      typeof recipient !== 'string' || !/^[A-F0-9]{16}$/.test(recipient) ||
      sender === recipient ||
      typeof expectedSha256 !== 'string' || !/^[a-f0-9]{64}$/.test(expectedSha256) ||
      !Number.isSafeInteger(expectedBytes) || expectedBytes < 1 ||
      expectedBytes > 25 * 1024 * 1024) {
    return rejected('R2_INVALID_PRIOR_ADMISSION', 400);
  }

  return withTransaction(async tx => {
    if (winnerMode === true) {
      if (typeof identityKey !== 'string' || !identityKey) return rejected('R2_INVALID_PRIOR_ADMISSION',401);
      const binding = await tx('SELECT public_key_spki FROM public.identity_key_bindings WHERE identity_id=$1 FOR SHARE',[recipient]);
      if (binding.rowCount !== 1 || binding.rows[0].public_key_spki !== identityKey) return rejected('R2_IDENTITY_REVOKED',401);
    }
    const conn = await tx(
      `SELECT from_identity,to_identity,is_mutual
         FROM public.connections
        WHERE (UPPER(from_identity)=$1 AND UPPER(to_identity)=$2)
           OR (UPPER(from_identity)=$2 AND UPPER(to_identity)=$1)
        FOR SHARE`,
      [sender, recipient]
    );
    const both = conn.rowCount === 2 &&
      conn.rows.some(r => r.from_identity?.toUpperCase() === sender &&
        r.to_identity?.toUpperCase() === recipient && r.is_mutual === true) &&
      conn.rows.some(r => r.from_identity?.toUpperCase() === recipient &&
        r.to_identity?.toUpperCase() === sender && r.is_mutual === true);
    if (!both) return rejected('R2_CONNECTION_REVOKED');

    const authorize = winnerMode === true ? authorizeWinningRecipientR2Read : authorizeRecipientR2Read;
    const current = await authorize({
      tx, authenticatedIdentity: recipient, peerIdentity: sender,
      messageId, objectKey
    });
    if (!current.allowed) return current;
    if (current.expectedSha256 !== expectedSha256 ||
        current.expectedBytes !== expectedBytes) {
      return rejected('R2_READ_DESCRIPTOR_CHANGED', 409);
    }
    // No externally usable token is created. Caller must release the
    // transaction before performing network I/O.
    return Object.freeze({
      allowed: true,
      authenticatedIdentity: recipient,
      peerIdentity: sender,
      messageId, objectKey, expectedSha256, expectedBytes
    });
  });
}

module.exports = { revalidateR2ReadAtStreamStart };
