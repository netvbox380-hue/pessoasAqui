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

async function reserveMediaObject({ query, objectKey, messageId, senderIdentity, recipientIdentity }) {
  if (typeof query !== 'function') throw new TypeError('query required');
  validateReservation({ objectKey, messageId, senderIdentity, recipientIdentity });
  // Do not ON CONFLICT DO UPDATE: the same ID must NEVER be repointed
  // at a different object or identity on retries.
  const created = await query(
    `INSERT INTO public.message_r2_objects
      (object_key, message_id, sender_identity, recipient_identity)
      VALUES ($1,$2,$3,$4) ON CONFLICT DO NOTHING RETURNING object_key`,
    [objectKey, messageId, senderIdentity, recipientIdentity]
  );
  if (created.rowCount === 1) return { reserved: true, duplicate: false };
  const existing = await query(
    `SELECT object_key, sender_identity, recipient_identity, state
       FROM public.message_r2_objects WHERE message_id=$1`, [messageId]
  );
  const row = existing.rows[0];
  const same = row && row.object_key === objectKey &&
    row.sender_identity.toLowerCase() === senderIdentity.toLowerCase() &&
    row.recipient_identity.toLowerCase() === recipientIdentity.toLowerCase();
  if (!same) return { reserved: false, duplicate: false, code: 'CONFLICT' };
  if (row.state !== 'RESERVED') {
    return { reserved: false, duplicate: true, state: row.state, code: 'ALREADY_ADVANCED' };
  }
  return { reserved: true, duplicate: true, state: row.state };
}

function validateContentDescriptor({ contentSha256, contentBytes }) {
  if (typeof contentSha256 !== 'string' || !/^[a-f0-9]{64}$/.test(contentSha256) ||
      !Number.isSafeInteger(contentBytes) || contentBytes < 1 ||
      contentBytes > 25 * 1024 * 1024) {
    throw new TypeError('Invalid encrypted object descriptor');
  }
}

async function markMediaUploaded({ query, objectKey, messageId, contentSha256, contentBytes }) {
  if (typeof query !== 'function') throw new TypeError('query required');
  validateOpaqueObjectKey(objectKey);
  if (!validId(messageId, 256)) throw new TypeError('Invalid message ID');
  validateContentDescriptor({ contentSha256, contentBytes });
  const result = await query(
    `UPDATE public.message_r2_objects
        SET state='UPLOADED', content_sha256=$3, content_bytes=$4,
            uploaded_at=clock_timestamp(), last_changed_at=clock_timestamp()
      WHERE object_key=$1 AND message_id=$2 AND state='RESERVED'
      RETURNING object_key`,
    [objectKey, messageId, contentSha256, contentBytes]
  );
  if (result.rowCount === 1) return { uploaded: true, duplicate: false };
  const existing = await query(
    `SELECT state, content_sha256, content_bytes
       FROM public.message_r2_objects WHERE object_key=$1 AND message_id=$2`,
    [objectKey, messageId]
  );
  const row = existing.rows[0];
  if (row && ['UPLOADED','ATTACHED'].includes(row.state) &&
      row.content_sha256 === contentSha256 && Number(row.content_bytes) === contentBytes) {
    return { uploaded: true, duplicate: true, state: row.state };
  }
  return { uploaded: false, code: 'CONFLICT_OR_NOT_RESERVED' };
}

/**
 * IMPORTANT: the future attachMediaObject(tx) MUST also insert the actual
 * e2ee_messages delivery envelope in the SAME PostgreSQL transaction, verify
 * bilateral authorization and ensure a READY upload with matching digest.
 * No such integration is enabled in this stage.
 */
module.exports = {
  validateReservation, validateContentDescriptor,
  reserveMediaObject, markMediaUploaded
};
