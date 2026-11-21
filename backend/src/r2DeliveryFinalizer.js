'use strict';

/**
 * PessoasAqui — R2 Mode B terminal delivery (STAGED / NOT ENABLED).
 *
 * This module is NOT imported by server.js. No R2 access, credentials,
 * migrations or HTTP routes are enabled by adding it.
 *
 * Endpoint integration MUST authenticate the recipient via existing
 * signed HTTP/WS identity proofs and call this method ONLY for an ACK
 * emitted after successful decrypt, integrity verification and DURABLE
 * encrypted local persistence on Android. A WebSocket send is not an ACK.
 *
 * Order matches attachMediaObject: media FOR UPDATE, then message FOR UPDATE.
 * Receipt + DELETE_QUEUED + outbox + removal of pending ciphertext commit
 * atomically. The R2 DeleteObject call happens asynchronously by outbox.
 */
const { stablePayloadDigest } = require('./messageDeliveryCoordinator');
const { validateOpaqueObjectKey } = require('./r2CiphertextAdapter');
const { sealPutKeyForRetirement } = require('./r2PutRetirementFence');

const RECEIPT_RETENTION_DAYS = 30; // Proposed retention, subject to final rollout review.
function validId(v, max) {
  return typeof v === 'string' && v.length >= 1 && v.length <= max &&
    !/[\u0000-\u001f\u007f]/.test(v);
}
const normalized = v => String(v).toLowerCase();

async function confirmR2Delivery({
  withTransaction, messageId, authenticatedRecipientIdentity
}) {
  if (typeof withTransaction !== 'function') throw new TypeError('withTransaction required');
  if (!validId(messageId, 256) || !validId(authenticatedRecipientIdentity, 128)) {
    return { success: false, acknowledged: false, statusCode: 400, code: 'INVALID_ARGUMENT' };
  }
  return withTransaction(async tx => {
    const objects = await tx(
      `SELECT object_key, message_id, sender_identity, recipient_identity,
              state, content_sha256, content_bytes
         FROM public.message_r2_objects
        WHERE message_id=$1 FOR UPDATE`,
      [messageId]
    );
    const object = objects.rows[0];
    if (!object) return { success: false, acknowledged: false, statusCode: 404, code: 'MEDIA_NOT_FOUND' };
    if (normalized(object.recipient_identity) !== normalized(authenticatedRecipientIdentity)) {
      return { success: false, acknowledged: false, statusCode: 403, code: 'NOT_RECIPIENT' };
    }
    validateOpaqueObjectKey(object.object_key);

    const receipt = await tx(
      `SELECT state, recipient_identity
         FROM public.message_delivery_receipts WHERE message_id=$1`,
      [messageId]
    );
    const previous = receipt.rows[0];
    if (previous) {
      if (normalized(previous.recipient_identity) !== normalized(authenticatedRecipientIdentity)) {
        return { success: false, acknowledged: false, statusCode: 403, code: 'NOT_RECIPIENT' };
      }
      if (previous.state === 'DELIVERED') {
        return { success: true, acknowledged: true, duplicate: true, state: 'DELIVERED', statusCode: 200 };
      }
      return { success: false, acknowledged: false, statusCode: 410, code: previous.state };
    }
    if (object.state !== 'ATTACHED') {
      return { success: false, acknowledged: false, statusCode: 409, code: 'MEDIA_NOT_ATTACHED' };
    }
    if (!object.content_sha256 || !object.content_bytes) {
      return { success: false, acknowledged: false, statusCode: 503, code: 'MEDIA_DESCRIPTOR_MISSING' };
    }

    const result = await tx(
      `SELECT id, sender_identity, recipient_identity, ciphertext_payload,
              iv_nonce, expires_at, delivery_state, is_cancelled, is_delivered
         FROM public.e2ee_messages WHERE id=$1 FOR UPDATE`,
      [messageId]
    );
    const message = result.rows[0];
    if (!message) {
      // No terminal receipt and no pending message means inconsistent data.
      return { success: false, acknowledged: false, statusCode: 503, code: 'PENDING_MESSAGE_MISSING' };
    }
    if (normalized(message.sender_identity) !== normalized(object.sender_identity) ||
        normalized(message.recipient_identity) !== normalized(object.recipient_identity) ||
        message.is_cancelled || message.is_delivered ||
        message.delivery_state !== 'PENDING' ||
        typeof message.ciphertext_payload !== 'string' ||
        !message.ciphertext_payload.startsWith('ENC_ECDH:') ||
        !message.expires_at) {
      return { success: false, acknowledged: false, statusCode: 409, code: 'INVALID_PENDING_STATE' };
    }
    // Terminal transition starts only while DB clock remains within TTL.
    const clock = await tx('SELECT clock_timestamp() AS now', []);
    if (new Date(message.expires_at).getTime() <= new Date(clock.rows[0].now).getTime()) {
      return { success: false, acknowledged: false, statusCode: 410, code: 'EXPIRED' };
    }

    await tx(
      `INSERT INTO public.message_delivery_receipts
         (message_id,sender_identity,recipient_identity,payload_digest,
          state,terminal_at,cleanup_after)
       VALUES ($1,$2,$3,$4,'DELIVERED',clock_timestamp(),
               clock_timestamp()+($5::integer*interval '1 day'))`,
      [messageId,message.sender_identity,message.recipient_identity,
        stablePayloadDigest(message),RECEIPT_RETENTION_DAYS]
    );
    const terminalObject = await tx(
      `UPDATE public.message_r2_objects
          SET state='DELETE_QUEUED', last_changed_at=clock_timestamp()
        WHERE object_key=$1 AND message_id=$2 AND state='ATTACHED'
        RETURNING object_key`,
      [object.object_key,messageId]
    );
    if (terminalObject.rowCount !== 1) throw new Error('R2_TERMINAL_MEDIA_CONFLICT');
    // Same DB transaction as DELETE_QUEUED + outbox. Unknown PUT history
    // fails closed; never enqueue a key whose old URLs might still work.
    await sealPutKeyForRetirement({ tx, objectKey: object.object_key });

    // Migration 006 checks DELETE_QUEUED + terminal receipt under row lock.
    const queued = await tx(
      `INSERT INTO public.message_r2_delete_outbox (object_key,message_id)
       VALUES ($1,$2) RETURNING object_key`,
      [object.object_key,messageId]
    );
    if (queued.rowCount !== 1) throw new Error('R2_OUTBOX_INSERT_CONFLICT');

    const deleted = await tx(
      `DELETE FROM public.e2ee_messages
        WHERE id=$1 AND delivery_state='PENDING'
          AND expires_at > clock_timestamp()
        RETURNING id`,
      [messageId]
    );
    if (deleted.rowCount !== 1) throw new Error('R2_DELIVERY_DEADLINE_CONFLICT');
    return { success: true, acknowledged: true, duplicate: false, state: 'DELIVERED', statusCode: 200 };
  });
}

/**
 * Stage 9: expire one ATTACHED R2 message at the DB-authoritative 48h
 * boundary. No server timers, routes or automatic worker are enabled.
 * Uses the SAME lock ordering as confirmR2Delivery() to serialize ACK,
 * expiry, and orphan processing. Receipts and notices contain NO media.
 *
 * ACK vs expiry: whichever transaction commits the terminal receipt wins.
 * The WHERE deadline predicate on the final DELETE rechecks the clock.
 * The caller must run a bounded, retryable batch worker in the future.
 */
async function expireR2Message({ withTransaction, messageId, noticeId }) {
  if (typeof withTransaction !== 'function') throw new TypeError('withTransaction required');
  if (!validId(messageId, 256) ||
      typeof noticeId !== 'string' ||
      !/^[a-f0-9]{8}-[a-f0-9]{4}-[1-8][a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/i.test(noticeId)) {
    return { success: false, statusCode: 400, code: 'INVALID_ARGUMENT' };
  }

  return withTransaction(async tx => {
    const objects = await tx(
      `SELECT object_key, message_id, sender_identity, recipient_identity,
              state, content_sha256, content_bytes
         FROM public.message_r2_objects
        WHERE message_id=$1 FOR UPDATE`,
      [messageId]
    );
    const object = objects.rows[0];
    if (!object) return { success: false, code: 'MEDIA_NOT_FOUND', statusCode: 404 };
    validateOpaqueObjectKey(object.object_key);

    // A committed receipt is authoritative and never overwritten.
    const receipt = await tx(
      'SELECT state FROM public.message_delivery_receipts WHERE message_id=$1',
      [messageId]
    );
    if (receipt.rowCount > 0) {
      return { success: false, code: 'ALREADY_FINALIZED', state: receipt.rows[0].state };
    }
    if (object.state !== 'ATTACHED' || !object.content_sha256 ||
        !object.content_bytes) {
      return { success: false, code: 'NOT_EXPIRABLE_ATTACHED' };
    }

    const messages = await tx(
      `SELECT id, sender_identity, recipient_identity, ciphertext_payload,
              iv_nonce, expires_at, delivery_state, is_cancelled, is_delivered
         FROM public.e2ee_messages WHERE id=$1 FOR UPDATE`,
      [messageId]
    );
    const row = messages.rows[0];
    if (!row || row.is_delivered || row.is_cancelled ||
        row.delivery_state !== 'PENDING' ||
        normalized(row.sender_identity) !== normalized(object.sender_identity) ||
        normalized(row.recipient_identity) !== normalized(object.recipient_identity) ||
        typeof row.ciphertext_payload !== 'string' ||
        !row.ciphertext_payload.startsWith('ENC_ECDH:') ||
        !row.expires_at) {
      return { success: false, code: 'NOT_EXPIRABLE_PENDING' };
    }

    const nowResult = await tx('SELECT clock_timestamp() AS now', []);
    if (new Date(row.expires_at).getTime() > new Date(nowResult.rows[0].now).getTime()) {
      return { success: false, code: 'NOT_EXPIRED' };
    }

    await tx(
      `INSERT INTO public.message_delivery_receipts
        (message_id,sender_identity,recipient_identity,payload_digest,
         state,terminal_at,cleanup_after)
       VALUES ($1,$2,$3,$4,'EXPIRED',clock_timestamp(),
               clock_timestamp()+($5::integer*interval '1 day'))`,
      [messageId,row.sender_identity,row.recipient_identity,
        stablePayloadDigest(row),RECEIPT_RETENTION_DAYS]
    );

    // Notice is identifiers only, never ciphertext, plaintext or R2 URL.
    await tx(
      `INSERT INTO public.message_expiration_notices
        (notice_id,message_id,sender_identity,recipient_identity,
         expired_at,cleanup_after)
       VALUES ($1,$2,$3,$4,clock_timestamp(),
               clock_timestamp()+($5::integer*interval '1 day'))`,
      [noticeId,messageId,row.sender_identity,row.recipient_identity,90]
    );

    const queuedMedia = await tx(
      `UPDATE public.message_r2_objects
          SET state='DELETE_QUEUED',last_changed_at=clock_timestamp()
        WHERE object_key=$1 AND message_id=$2 AND state='ATTACHED'
        RETURNING object_key`,
      [object.object_key,messageId]
    );
    if (queuedMedia.rowCount !== 1) throw new Error('R2_EXPIRY_MEDIA_CONFLICT');
    // Seal before queuing a physical delete. A missing ledger aborts the
    // entire terminalization transaction; legacy keys require quarantine.
    await sealPutKeyForRetirement({ tx, objectKey: object.object_key });

    const outbox = await tx(
      `INSERT INTO public.message_r2_delete_outbox
          (object_key,message_id)
        VALUES ($1,$2) RETURNING object_key`,
      [object.object_key,messageId]
    );
    if (outbox.rowCount !== 1) throw new Error('R2_EXPIRY_OUTBOX_CONFLICT');

    const deleted = await tx(
      `DELETE FROM public.e2ee_messages
        WHERE id=$1 AND delivery_state='PENDING' AND is_delivered=FALSE
          AND (is_cancelled IS NULL OR is_cancelled=FALSE)
          AND expires_at <= clock_timestamp()
        RETURNING id`,
      [messageId]
    );
    if (deleted.rowCount !== 1) throw new Error('R2_EXPIRY_DEADLINE_CONFLICT');

    return { success: true, state: 'EXPIRED', noticeId };
  });
}

module.exports = { confirmR2Delivery, expireR2Message };
