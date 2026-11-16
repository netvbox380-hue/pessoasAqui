'use strict';

/**
 * PessoasAqui — R2 recipient object authorization (STAGED ONLY).
 * No network access, no presigned GET and no production route.
 *
 * Call only inside the executeAuthenticatedR2Action(R2_READ) callback,
 * using the verified identity from that gate, not client JSON.
 *
 * The caller must release the transaction before fetching from R2, and
 * must enforce a short read capability, abuse limits and streamed digest
 * verification for actual delivery in a later stage.
 */
const { validateOpaqueObjectKey } = require('./r2CiphertextAdapter');

function deny(code, statusCode = 403) {
  return { allowed: false, code, statusCode };
}
function validIdentity(s) {
  return typeof s === 'string' && /^[A-F0-9]{16}$/i.test(s);
}
function validMessageId(s) {
  return typeof s === 'string' && s.length >= 1 && s.length <= 256 &&
    !/[\u0000-\u001f\u007f]/.test(s);
}

/**
 * This is deliberately a database-only operation.
 * tx MUST be the transaction from the cryptographic R2_READ action gate.
 *
 * Security order: auth gate already holds bilateral FOR SHARE connection
 * locks; media and message rows are read/locked with FOR SHARE, then
 * terminal receipt checked while the transaction is still open.
 */
async function authorizeRecipientR2Read({
  tx, authenticatedIdentity, peerIdentity, messageId, objectKey
}) {
  if (typeof tx !== 'function') throw new TypeError('Transaction callback required');
  validateOpaqueObjectKey(objectKey);
  if (!validIdentity(authenticatedIdentity) || !validIdentity(peerIdentity) ||
      authenticatedIdentity.toUpperCase() === peerIdentity.toUpperCase() ||
      !validMessageId(messageId)) return deny('INVALID_R2_READ_REQUEST',400);
  const recipient = authenticatedIdentity.toUpperCase();
  const sender = peerIdentity.toUpperCase();

  // Fail closed on a wrong object key, missing message, revocation,
  // cancellation, missing expiry or invalid delivery state.
  // Do not return presigned URLs or ciphertext; this is an authorization
  // decision only. The message row and media row remain locked until COMMIT.
  const rows = await tx(
    `SELECT o.object_key,o.message_id,o.sender_identity,o.recipient_identity,
            o.content_sha256,o.content_bytes,o.state,
            m.id,m.sender_identity AS message_sender,
            m.recipient_identity AS message_recipient,m.delivery_state,
            m.is_cancelled,m.expires_at,
            (m.expires_at > clock_timestamp()) AS is_active
       FROM public.message_r2_objects o
       JOIN public.e2ee_messages m ON m.id=o.message_id
      WHERE o.object_key=$1 AND o.message_id=$2
        AND UPPER(o.sender_identity)=$3
        AND UPPER(o.recipient_identity)=$4
        AND UPPER(m.sender_identity)=$3
        AND UPPER(m.recipient_identity)=$4
      FOR SHARE OF o,m`,
    [objectKey,messageId,sender,recipient]
  );
  if (rows.rowCount !== 1) return deny('R2_OBJECT_NOT_AUTHORIZED');
  const row = rows.rows[0];
  if (row.state !== 'ATTACHED' || row.is_cancelled === true ||
      row.is_active !== true || row.delivery_state !== 'PENDING') {
    return deny('R2_MESSAGE_NOT_DOWNLOADABLE',409);
  }
  if (typeof row.content_sha256 !== 'string' ||
      !/^[a-f0-9]{64}$/.test(row.content_sha256) ||
      !Number.isSafeInteger(Number(row.content_bytes)) ||
      Number(row.content_bytes) < 1 ||
      Number(row.content_bytes) > 25 * 1024 * 1024) {
    return deny('R2_INVALID_STORED_DESCRIPTOR',409);
  }

  const terminal = await tx(
    `SELECT state FROM public.message_delivery_receipts
      WHERE message_id=$1`,
    [messageId]
  );
  if (terminal.rowCount !== 0) return deny('R2_MESSAGE_TERMINAL',410);
  return Object.freeze({
    allowed: true,
    authenticatedIdentity: recipient,
    peerIdentity: sender,
    objectKey, messageId,
    expectedSha256: row.content_sha256,
    expectedBytes: Number(row.content_bytes)
  });
}

module.exports = { authorizeRecipientR2Read };
