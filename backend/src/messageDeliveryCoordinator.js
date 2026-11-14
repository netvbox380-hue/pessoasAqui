'use strict';

/**
 * PessoasAqui / R2 48h — FASE 2 (NAO ATIVADA).
 *
 * Transactional delivery transition prototype for INLINE (PostgreSQL) payloads.
 * This file is deliberately NOT imported by server.js. It is NOT safe to enable
 * without a reviewed migration, Android/legacy compatibility and R2 outbox wiring.
 *
 * R2-backed messages MUST use a distinct transition that atomically inserts
 * a deletion-outbox entry. Never invoke this module for R2 content.
 */
const crypto = require('node:crypto');
const TERMINAL = new Set(['DELIVERED', 'EXPIRED', 'CANCELLED']);
const RECEIPT_RETENTION_DAYS = 30; // Proposed; must be approved before rollout.
const NOTICE_RETENTION_DAYS = 90;  // Proposed; must be approved before rollout.

function validIdentity(value) {
  return typeof value === 'string' && /^[A-Za-z0-9_-]{8,64}$/.test(value);
}

function canonical(value) {
  return String(value).toLowerCase();
}

function stablePayloadDigest(row) {
  // Must match the eventual send-time canonical digest format before activation.
  const fields = [
    row.sender_identity,
    row.recipient_identity,
    row.ciphertext_payload,
    row.iv_nonce || ''
  ];
  const hash = crypto.createHash('sha256');
  for (const field of fields) {
    const data = Buffer.from(String(field), 'utf8');
    const length = Buffer.allocUnsafe(4);
    length.writeUInt32BE(data.length);
    hash.update(length);
    hash.update(data);
  }
  return hash.digest('hex');
}

function terminalResponse(row) {
  if (!row || !TERMINAL.has(row.state)) return null;
  return {
    success: row.state === 'DELIVERED',
    state: row.state,
    acknowledged: row.state === 'DELIVERED',
    duplicate: true
  };
}

/**
 * tx is injected to allow real PostgreSQL test transactions without touching
 * production. Each query executes in the SAME transaction/connection.
 *
 * For ACKs at the 48h boundary, a row is considered deliverable only if
 * expires_at > PostgreSQL's transaction clock.
 *
 * The caller authenticates the recipient independently. This method validates
 * ownership again and returns no ciphertext in any response.
 */
async function confirmInlineDelivery({ withTransaction, messageId, recipientIdentity }) {
  if (typeof withTransaction !== 'function') throw new TypeError('withTransaction required');
  if (typeof messageId !== 'string' || messageId.length === 0 || messageId.length > 256 ||
      !validIdentity(recipientIdentity)) {
    return { success: false, statusCode: 400, code: 'INVALID_ARGUMENT' };
  }
  return withTransaction(async (tx) => {
    const locked = await tx(
      `SELECT id, sender_identity, recipient_identity, ciphertext_payload, iv_nonce,
              expires_at, delivery_state, is_cancelled
         FROM public.e2ee_messages WHERE id = $1 FOR UPDATE`,
      [messageId]
    );
    const row = locked.rows[0];
    if (!row) {
      const receipt = await tx(
        'SELECT recipient_identity, state FROM public.message_delivery_receipts WHERE message_id = $1',
        [messageId]
      );
      const prev = receipt.rows[0];
      if (!prev) return { success: false, statusCode: 404, code: 'UNKNOWN_MESSAGE' };
      if (canonical(prev.recipient_identity) !== canonical(recipientIdentity)) {
        return { success: false, statusCode: 403, code: 'NOT_RECIPIENT' };
      }
      return { ...terminalResponse(prev), statusCode: prev.state === 'DELIVERED' ? 200 : 410 };
    }
    if (canonical(row.recipient_identity) !== canonical(recipientIdentity)) {
      return { success: false, statusCode: 403, code: 'NOT_RECIPIENT' };
    }
    if (row.is_cancelled || row.delivery_state === 'CANCELLED') {
      return { success: false, statusCode: 410, code: 'CANCELLED' };
    }
    if (row.delivery_state !== 'PENDING' || !row.expires_at) {
      return { success: false, statusCode: 503, code: 'STATE_NOT_MIGRATED' };
    }
    if (typeof row.ciphertext_payload !== 'string') {
      // R2 content must NOT be finalized through the inline-only path.
      return { success: false, statusCode: 503, code: 'NOT_INLINE_PAYLOAD' };
    }
    const clock = await tx('SELECT clock_timestamp() AS now', []);
    const now = new Date(clock.rows[0].now).getTime();
    if (new Date(row.expires_at).getTime() <= now) {
      // The expiry worker owns the transition. Never claim delivery here.
      return { success: false, statusCode: 410, code: 'EXPIRED' };
    }
    const digest = stablePayloadDigest(row);
    await tx(
      `INSERT INTO public.message_delivery_receipts
         (message_id, sender_identity, recipient_identity, payload_digest,
          state, terminal_at, cleanup_after)
       VALUES ($1,$2,$3,$4,'DELIVERED',clock_timestamp(),
               clock_timestamp() + ($5::integer * interval '1 day'))`,
      [messageId, row.sender_identity, row.recipient_identity, digest, RECEIPT_RETENTION_DAYS]
    );
    const deleted = await tx(
      `DELETE FROM public.e2ee_messages
        WHERE id=$1 AND delivery_state='PENDING' AND expires_at > clock_timestamp() RETURNING id`,
      [messageId]
    );
    if (deleted.rowCount !== 1) {
      // Throw, not return: caller's withTransaction MUST roll back the receipt.
      throw new Error('DELIVERY_TRANSITION_CONFLICT');
    }
    return { success: true, acknowledged: true, state: 'DELIVERED', duplicate: false, statusCode: 200 };
  });
}

/**
 * One expired inline message. Production batch worker must select candidates
 * using SKIP LOCKED and call this transactional transition for each candidate.
 * The notice carries identifiers only, never payload or object links.
 */
async function expireInlineMessage({ withTransaction, messageId, noticeId }) {
  if (typeof withTransaction !== 'function') throw new TypeError('withTransaction required');
  if (typeof messageId !== 'string' || !messageId || messageId.length > 256 ||
      typeof noticeId !== 'string' || !/^[a-f0-9-]{36}$/i.test(noticeId)) {
    return { success: false, code: 'INVALID_ARGUMENT' };
  }
  return withTransaction(async (tx) => {
    const locked = await tx(
      `SELECT id, sender_identity, recipient_identity, ciphertext_payload, iv_nonce,
              expires_at, delivery_state, is_cancelled
         FROM public.e2ee_messages WHERE id=$1 FOR UPDATE`,
      [messageId]
    );
    const row = locked.rows[0];
    if (!row) return { success: false, code: 'ALREADY_FINALIZED' };
    if (row.delivery_state !== 'PENDING' || row.is_cancelled || !row.expires_at ||
        typeof row.ciphertext_payload !== 'string') {
      return { success: false, code: 'NOT_EXPIRABLE_INLINE' };
    }
    const clock = await tx('SELECT clock_timestamp() AS now', []);
    if (new Date(row.expires_at).getTime() > new Date(clock.rows[0].now).getTime()) {
      return { success: false, code: 'NOT_EXPIRED' };
    }
    await tx(
      `INSERT INTO public.message_delivery_receipts
        (message_id, sender_identity, recipient_identity, payload_digest,
         state, terminal_at, cleanup_after)
       VALUES ($1,$2,$3,$4,'EXPIRED',clock_timestamp(),
              clock_timestamp() + ($5::integer * interval '1 day'))`,
      [messageId, row.sender_identity, row.recipient_identity,
        stablePayloadDigest(row), RECEIPT_RETENTION_DAYS]
    );
    await tx(
      `INSERT INTO public.message_expiration_notices
        (notice_id, message_id, sender_identity, recipient_identity,
         expired_at, cleanup_after)
       VALUES ($1,$2,$3,$4,clock_timestamp(),
              clock_timestamp() + ($5::integer * interval '1 day'))`,
      [noticeId, messageId, row.sender_identity, row.recipient_identity, NOTICE_RETENTION_DAYS]
    );
    const deleted = await tx(
      `DELETE FROM public.e2ee_messages
         WHERE id=$1 AND delivery_state='PENDING' AND expires_at <= clock_timestamp() RETURNING id`,
      [messageId]
    );
    if (deleted.rowCount !== 1) throw new Error('EXPIRY_TRANSITION_CONFLICT');
    return { success: true, state: 'EXPIRED', noticeId };
  });
}

module.exports = {
  stablePayloadDigest,
  confirmInlineDelivery,
  expireInlineMessage,
  terminalResponse
};
