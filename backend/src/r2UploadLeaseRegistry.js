'use strict';

/**
 * PessoasAqui / R2 LEASED upload registry — STAGING, NOT IMPORTED IN server.js.
 *
 * This module requires migration 008. It does not issue presigned URLs or
 * authorize network PUTs: the caller MUST verify sender identity, mutual
 * connection, and the actual R2 object bytes independently.
 *
 * Clock and lease are controlled exclusively by PostgreSQL. The key is
 * immutable for a single upload attempt; no lease renewal, overwrites or
 * reassignment. Generation is fixed to 1 for a fresh reservation only.
 *
 * IMPORTANT: SQL lease expiry cannot revoke an in-flight S3 request.
 * Do not enable destructive orphan GC until late PUT is bounded or
 * reconciled and the lifecycle safety net is configured.
 */
const {
  validateReservation, validateContentDescriptor
} = require('./r2MediaRegistry');
const { validateOpaqueObjectKey } = require('./r2CiphertextAdapter');

const LEASE_MINUTES = 60;
const INITIAL_GENERATION = 1;

function validMessageId(id) {
  return typeof id === 'string' && id.length > 0 && id.length <= 256 &&
    !/[\u0000-\u001f\u007f]/.test(id);
}

/**
 * New protocol only: INSERT with DB-authored lease, no client clock inputs.
 * A replayed reservation never renews the lease or substitutes another key.
 */
async function reserveLeasedMediaObject({
  query, objectKey, messageId, senderIdentity, recipientIdentity
}) {
  if (typeof query !== 'function') throw new TypeError('query required');
  validateReservation({ objectKey, messageId, senderIdentity, recipientIdentity });
  const created = await query(
    `INSERT INTO public.message_r2_objects
       (object_key, message_id, sender_identity, recipient_identity,
        upload_lease_expires_at, upload_generation)
       VALUES ($1,$2,$3,$4,clock_timestamp()+($5::integer*interval '1 minute'),$6)
       ON CONFLICT DO NOTHING
       RETURNING object_key, upload_generation, upload_lease_expires_at`,
    [objectKey, messageId, senderIdentity, recipientIdentity, LEASE_MINUTES, INITIAL_GENERATION]
  );
  if (created.rowCount === 1) {
    const row = created.rows[0];
    return { reserved: true, duplicate: false,
      generation: row.upload_generation, leaseExpiresAt: row.upload_lease_expires_at };
  }
  const existing = await query(
    `SELECT object_key, sender_identity, recipient_identity,
            state, upload_generation, upload_lease_expires_at,
            (upload_lease_expires_at > clock_timestamp()) AS lease_active
       FROM public.message_r2_objects WHERE message_id=$1`,
    [messageId]
  );
  const row = existing.rows[0];
  if (!row || row.object_key !== objectKey ||
      row.sender_identity.toLowerCase() !== senderIdentity.toLowerCase() ||
      row.recipient_identity.toLowerCase() !== recipientIdentity.toLowerCase()) {
    return { reserved: false, code: 'RESERVATION_CONFLICT' };
  }
  if (row.state !== 'RESERVED' || row.upload_generation !== INITIAL_GENERATION) {
    return { reserved: false, code: 'NOT_RESERVABLE', state: row.state };
  }
  if (!row.lease_active) return { reserved: false, code: 'LEASE_EXPIRED' };
  return { reserved: true, duplicate: true, generation: row.upload_generation,
    leaseExpiresAt: row.upload_lease_expires_at };
}

/**
 * Completes registration ONLY while the generation-matched lease is alive.
 * Refuses to transform ORPHAN_QUEUED back into UPLOADED.
 * The caller must compare observed R2 SHA256/size, not trust Android values.
 */
async function markLeasedMediaUploaded({
  query, objectKey, messageId, generation, contentSha256, contentBytes
}) {
  if (typeof query !== 'function') throw new TypeError('query required');
  validateOpaqueObjectKey(objectKey);
  if (!validMessageId(messageId) || !Number.isSafeInteger(generation) ||
      generation !== INITIAL_GENERATION) throw new TypeError('Invalid leased media identity');
  validateContentDescriptor({ contentSha256, contentBytes });
  const updated = await query(
    `UPDATE public.message_r2_objects
        SET state='UPLOADED',content_sha256=$4,content_bytes=$5,
            uploaded_at=clock_timestamp(),last_changed_at=clock_timestamp()
      WHERE object_key=$1 AND message_id=$2
        AND upload_generation=$3 AND state='RESERVED'
        AND upload_lease_expires_at > clock_timestamp()
      RETURNING object_key`,
    [objectKey,messageId,generation,contentSha256,contentBytes]
  );
  if (updated.rowCount === 1) return { uploaded: true, duplicate: false };
  const checked = await query(
    `SELECT state,upload_generation,content_sha256,content_bytes,
            (upload_lease_expires_at > clock_timestamp()) AS lease_active
       FROM public.message_r2_objects
      WHERE object_key=$1 AND message_id=$2`,
    [objectKey,messageId]
  );
  const row = checked.rows[0];
  if (!row || row.upload_generation !== generation) {
    return { uploaded: false, code: 'GENERATION_OR_OBJECT_MISMATCH' };
  }
  if (row.state === 'RESERVED' && !row.lease_active) {
    return { uploaded: false, code: 'LEASE_EXPIRED' };
  }
  if ((row.state === 'UPLOADED' || row.state === 'ATTACHED') &&
      row.content_sha256 === contentSha256 &&
      Number(row.content_bytes) === contentBytes) {
    return { uploaded: true, duplicate: true, state: row.state };
  }
  return { uploaded: false, code: 'STATE_OR_CONTENT_CONFLICT' };
}

module.exports = {
  LEASE_MINUTES, INITIAL_GENERATION,
  reserveLeasedMediaObject, markLeasedMediaUploaded
};
