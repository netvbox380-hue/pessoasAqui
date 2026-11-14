'use strict';

/**
 * PessoasAqui Mode B: delivery retention primitives.
 *
 * PHASE 1 / INERT: This module is deliberately not imported by server.js.
 * It does NOT change production delivery, ACKs, PostgreSQL or R2 behavior.
 *
 * Time authority: PostgreSQL clock at initial durable acceptance.
 * NEVER use an Android-supplied timestamp to determine expiry.
 * Expiry is about confirmed LOCAL PERSISTENCE, not opening/reading a chat.
 */
const RETENTION_HOURS = 48;
const RETENTION_MS = RETENTION_HOURS * 60 * 60 * 1000;
const DELIVERY_STATES = Object.freeze({
  PENDING: 'PENDING',
  DELIVERED: 'DELIVERED',
  EXPIRED: 'EXPIRED',
  CANCELLED: 'CANCELLED'
});

function assertDate(value, field) {
  const date = value instanceof Date ? value : new Date(value);
  if (!Number.isFinite(date.getTime())) {
    throw new TypeError(`Invalid ${field}`);
  }
  return date;
}

function expiryFromAcceptedAt(serverAcceptedAt) {
  const accepted = assertDate(serverAcceptedAt, 'serverAcceptedAt');
  return new Date(accepted.getTime() + RETENTION_MS);
}

/**
 * A message stops being deliverable at expiresAt, including equality.
 * This MUST be rechecked atomically in the database during any final
 * delivery/ACK transition; this helper alone cannot prevent races.
 */
function isExpired(serverAcceptedAt, serverNow) {
  const now = assertDate(serverNow, 'serverNow');
  return now.getTime() >= expiryFromAcceptedAt(serverAcceptedAt).getTime();
}

/**
 * Intended transactional state-machine contract.
 * A late ACK must not turn an already EXPIRED message into DELIVERED.
 * 'DELIVERED' requires authenticated recipient ACK after durable local save.
 * The transaction that persists this transition also creates a durable
 * object-delete outbox entry; external R2 deletion is retried separately.
 */
function canTransition(currentState, nextState) {
  if (!Object.values(DELIVERY_STATES).includes(currentState) ||
      !Object.values(DELIVERY_STATES).includes(nextState)) {
    return false;
  }
  return currentState === DELIVERY_STATES.PENDING && (
    nextState === DELIVERY_STATES.DELIVERED ||
    nextState === DELIVERY_STATES.EXPIRED ||
    nextState === DELIVERY_STATES.CANCELLED
  );
}

/**
 * Group only expiration notices belonging to the same conversation.
 * Does not inspect or retain ciphertext or plaintext.
 *
 * Each event must contain a durable noticeId, recipientIdentity,
 * senderIdentity and messageId. Duplicate retries are suppressed by noticeId.
 * The caller must authenticate recipient access before providing events.
 */
function groupExpirationNotices(events) {
  if (!Array.isArray(events)) throw new TypeError('events must be an array');
  const groups = new Map();
  const visitedNotices = new Set();

  for (const event of events) {
    if (!event || typeof event !== 'object') throw new TypeError('Invalid expiration event');
    const { noticeId, senderIdentity, recipientIdentity, messageId } = event;
    if ([noticeId, senderIdentity, recipientIdentity, messageId].some(
      value => typeof value !== 'string' || value.length === 0
    )) {
      throw new TypeError('Missing expiration notice identifiers');
    }
    if (visitedNotices.has(noticeId)) continue;
    visitedNotices.add(noticeId);
    const key = JSON.stringify([recipientIdentity.toLowerCase(), senderIdentity.toLowerCase()]);
    if (!groups.has(key)) {
      groups.set(key, {
        recipientIdentity,
        senderIdentity,
        count: 0,
        messageIds: [],
        noticeIds: []
      });
    }
    const group = groups.get(key);
    group.count += 1;
    group.messageIds.push(messageId);
    group.noticeIds.push(noticeId);
  }
  return Array.from(groups.values());
}

module.exports = {
  RETENTION_HOURS,
  RETENTION_MS,
  DELIVERY_STATES,
  expiryFromAcceptedAt,
  isExpired,
  canTransition,
  groupExpirationNotices
};
