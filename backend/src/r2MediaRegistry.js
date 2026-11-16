'use strict';

/**
 * PessoasAqui / MEDIA R2 — staged two-phase registry, NO PRODUCTION IMPORTS.
 *
 * Reserve an object in PostgreSQL BEFORE uploading to R2, so crashes after
 * upload leave a durable record for an eventual orphan cleanup worker.
 *
 * This is NOT an authorization layer. Callers MUST have authenticated sender,
 * checked bilateral connectivity, and assigned a unique message ID before
 * reserving. Never call upload with plaintext or E2EE keys.
 */
const { validateOpaqueObjectKey } = require('./r2CiphertextAdapter');

function validId(value, maxLen) {
  return typeof value === 'string' && value.length >= 1 &&
    value.length <= maxLen && !/[\u0000-\u001f\u007f]/.test(value);
}

function validateReservation({ objectKey, messageId, senderIdentity, recipientIdentity }) {
  validateOpaqueObjectKey(objectKey);
  if (!validId(messageId, 256) ||
      !validId(senderIdentity, 128) || !validId(recipientIdentity, 128) ||
      senderIdentity.toLowerCase() === recipientIdentity.toLowerCase()) {
    throw new TypeError('Invalid media object reservation');
  }
}

function validateContentDescriptor({ contentSha256, contentBytes }) {
  if (typeof contentSha256 !== 'string' || !/^[a-f0-9]{64}$/.test(contentSha256) ||
      !Number.isSafeInteger(contentBytes) || contentBytes < 1 ||
      contentBytes > 25 * 1024 * 1024) {
    throw new TypeError('Invalid encrypted object descriptor');
  }
}

/**
 * FASE 14 — ENFORCED LEASE CONTRACT, STAGED ONLY, NOT CALLED BY server.js.
 *
 * The authenticated API caller MUST verify the cryptographic signature
 * independently. authenticatedSenderIdentity is taken from that verified
 * context, never from a user-supplied JSON claim.
 *
 * The descriptor stays an encrypted ECDH envelope, not raw media bytes.
 * Exact ciphertext content hash/size are already stored in the registry;
 * the API must have verified those against the actual R2 object before
 * calling this function. The backend MUST NOT decrypt the descriptor.
 *
 * Order is deliberately connections FOR SHARE, then media FOR UPDATE:
 * matches the existing Mode B pair authorization lock order.
 */
async function attachMediaObject({
  withTransaction, messageId, objectKey, senderIdentity, recipientIdentity,
  authenticatedSenderIdentity, senderAlias, ciphertextPayload, ivNonce,
  contentSha256, contentBytes, uploadGeneration
}) {
  if (typeof withTransaction !== 'function') throw new TypeError('withTransaction required');
  validateReservation({ objectKey, messageId, senderIdentity, recipientIdentity });
  if (uploadGeneration !== 1) {
    return { success: false, attached: false, code: 'INVALID_UPLOAD_GENERATION', statusCode: 400 };
  }
  if (!validId(authenticatedSenderIdentity, 128) ||
      authenticatedSenderIdentity.toLowerCase() !== senderIdentity.toLowerCase()) {
    return { success: false, attached: false, code: 'UNAUTHORIZED_SENDER', statusCode: 403 };
  }
  validateContentDescriptor({ contentSha256, contentBytes });
  if (typeof ciphertextPayload !== 'string' ||
      !ciphertextPayload.startsWith('ENC_ECDH:') ||
      Buffer.byteLength(ciphertextPayload, 'utf8') > 65536 ||
      Buffer.byteLength(ciphertextPayload, 'utf8') < 16 ||
      !validId(ivNonce, 128) ||
      typeof senderAlias !== 'string' ||
      Buffer.byteLength(senderAlias, 'utf8') > 256) {
    return { success: false, attached: false, code: 'INVALID_ENCRYPTED_DESCRIPTOR', statusCode: 400 };
  }
  return withTransaction(async (tx) => {
    // Server-side bilateral authorization is mandatory. A pre-reserved
    // object does NOT establish a permanent relationship.
    const connection = await tx(
      `SELECT from_identity, to_identity, is_mutual
         FROM public.connections
        WHERE (LOWER(from_identity)=LOWER($1) AND LOWER(to_identity)=LOWER($2))
           OR (LOWER(from_identity)=LOWER($2) AND LOWER(to_identity)=LOWER($1))
        FOR SHARE`,
      [senderIdentity, recipientIdentity]
    );
    const a = senderIdentity.toLowerCase(), b = recipientIdentity.toLowerCase();
    const forward = connection.rows.some(r =>
      r.from_identity.toLowerCase() === a && r.to_identity.toLowerCase() === b && r.is_mutual === true);
    const reverse = connection.rows.some(r =>
      r.from_identity.toLowerCase() === b && r.to_identity.toLowerCase() === a && r.is_mutual === true);
    if (connection.rowCount !== 2 || !forward || !reverse) {
      return { success: false, attached: false, code: 'NO_MUTUAL_CONNECTION', statusCode: 403 };
    }
    const mediaResult = await tx(
      `SELECT object_key, message_id, sender_identity, recipient_identity,
              content_sha256, content_bytes, state,
              upload_generation, upload_lease_expires_at, uploaded_at,
              (uploaded_at >= clock_timestamp() - interval '2 hours') AS fresh_upload
         FROM public.message_r2_objects
        WHERE object_key=$1 AND message_id=$2 FOR UPDATE`,
      [objectKey, messageId]
    );
    const media = mediaResult.rows[0];
    if (!media) return { success: false, attached: false, code: 'MEDIA_NOT_FOUND', statusCode: 404 };
    if (media.sender_identity.toLowerCase() !== a ||
        media.recipient_identity.toLowerCase() !== b) {
      return { success: false, attached: false, code: 'IDENTITY_MISMATCH', statusCode: 403 };
    }
    if (media.content_sha256 !== contentSha256 ||
        Number(media.content_bytes) !== contentBytes) {
      return { success: false, attached: false, code: 'CONTENT_MISMATCH', statusCode: 409 };
    }
    if (media.state !== 'UPLOADED' && media.state !== 'ATTACHED') {
      return { success: false, attached: false, code: 'INVALID_STATE_FOR_ATTACH', statusCode: 409 };
    }

    // New R2 attachment contract: generation and the two-hour window must
    // be checked under the media row lock, never in an earlier read.
    // Already ATTACHED retries retain the original accepted_at and remain
    // idempotent even when uploaded_at is older than two hours.
    if (media.upload_generation !== uploadGeneration ||
        !media.upload_lease_expires_at || !media.uploaded_at) {
      return { success: false, attached: false, code: 'UNLEASED_OR_WRONG_GENERATION', statusCode: 409 };
    }
    if (media.state === 'UPLOADED' && media.fresh_upload !== true) {
      return { success: false, attached: false, code: 'UPLOAD_WINDOW_EXPIRED', statusCode: 409 };
    }

    // Reject reuse of IDs that were already finalized and removed from
    // e2ee_messages. Idempotent sender retries must not resurrect content.
    const receipt = await tx(
      'SELECT state FROM public.message_delivery_receipts WHERE message_id=$1',
      [messageId]
    );
    if (receipt.rowCount) {
      return { success: false, attached: false, code: 'MESSAGE_ALREADY_TERMINAL', statusCode: 409 };
    }
    const existing = await tx(
      `SELECT sender_identity, recipient_identity, sender_alias,
              ciphertext_payload, iv_nonce, delivery_state, is_cancelled,
              is_delivered
         FROM public.e2ee_messages WHERE id=$1 FOR UPDATE`,
      [messageId]
    );
    const old = existing.rows[0];
    const matches = old &&
      old.sender_identity.toLowerCase() === a &&
      old.recipient_identity.toLowerCase() === b &&
      old.sender_alias === senderAlias &&
      old.ciphertext_payload === ciphertextPayload &&
      (old.iv_nonce || '') === ivNonce &&
      old.delivery_state === 'PENDING' &&
      old.is_delivered === false &&
      old.is_cancelled !== true;

    if (old && !matches) {
      return { success: false, attached: false, code: 'MESSAGE_ID_COLLISION', statusCode: 409 };
    }
    if (media.state === 'ATTACHED') {
      if (matches) return { success: true, attached: true, duplicate: true, messageId, objectKey };
      return { success: false, attached: false, code: 'ATTACH_STATE_MISMATCH', statusCode: 409 };
    }
    // Even when descriptor row preexists, it must be exact and pending.
    if (!old) {
      const insert = await tx(
        `INSERT INTO public.e2ee_messages (
           id, sender_identity, recipient_identity, sender_alias,
           ciphertext_payload, iv_nonce, is_delivered,
           accepted_at, expires_at, delivery_state
         )
         SELECT $1,$2,$3,$4,$5,$6,FALSE,
                accepted.ts,accepted.ts + interval '48 hours','PENDING'
           FROM (SELECT clock_timestamp() AS ts) AS accepted
         ON CONFLICT (id) DO NOTHING RETURNING id`,
        [messageId, senderIdentity, recipientIdentity, senderAlias, ciphertextPayload, ivNonce]
      );
      if (insert.rowCount !== 1) {
        // Conflict in concurrent transaction: roll back instead of claiming
        // success based on a stale or different message.
        throw new Error('ATTACH_MESSAGE_INSERT_CONFLICT');
      }
    }
    const updated = await tx(
      `UPDATE public.message_r2_objects SET state='ATTACHED',
              attached_at=clock_timestamp(), last_changed_at=clock_timestamp()
        WHERE object_key=$1 AND message_id=$2 AND state='UPLOADED'
        RETURNING object_key`,
      [objectKey, messageId]
    );
    if (updated.rowCount !== 1) throw new Error('ATTACH_TRANSITION_FAILED');
    return { success: true, attached: true, duplicate: false, messageId, objectKey };
  });
}

/**
 * Staged for the future authenticated R2 API only. Migration 008 required.
 * HTTP signature verification and actual R2 ciphertext validation are
 * required separately before calling this function.
 */
function attachLeasedMediaObject(args) {
  return attachMediaObject(args);
}

module.exports = {
  validateReservation, validateContentDescriptor,
  attachMediaObject, attachLeasedMediaObject
};
