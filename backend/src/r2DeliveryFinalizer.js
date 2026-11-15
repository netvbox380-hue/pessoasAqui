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

module.exports = { confirmR2Delivery };
