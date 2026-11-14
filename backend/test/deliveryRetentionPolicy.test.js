'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const {
  RETENTION_HOURS, RETENTION_MS, DELIVERY_STATES,
  expiryFromAcceptedAt, isExpired, canTransition, groupExpirationNotices
} = require('../src/deliveryRetentionPolicy');

test('retention is exactly 48 hours from server acceptance', () => {
  assert.equal(RETENTION_HOURS, 48);
  assert.equal(RETENTION_MS, 172800000);
  assert.equal(expiryFromAcceptedAt('2026-11-14T10:00:00Z').toISOString(), '2026-11-16T10:00:00.000Z');
  assert.equal(isExpired('2026-11-14T10:00:00Z', '2026-11-16T09:59:59.999Z'), false);
  assert.equal(isExpired('2026-11-14T10:00:00Z', '2026-11-16T10:00:00.000Z'), true);
  assert.equal(isExpired('2026-11-14T10:00:00Z', '2026-11-17T10:00:00Z'), true);
  assert.throws(() => isExpired('not a date', new Date()), /Invalid/);
});

test('pending is the only state allowed to transition', () => {
  for (const target of ['DELIVERED', 'EXPIRED', 'CANCELLED']) {
    assert.equal(canTransition(DELIVERY_STATES.PENDING, target), true);
  }
  for (const state of ['DELIVERED', 'EXPIRED', 'CANCELLED']) {
    for (const target of Object.values(DELIVERY_STATES)) {
      assert.equal(canTransition(state, target), false);
    }
  }
  assert.equal(canTransition('UNKNOWN', 'DELIVERED'), false);
});

test('three expired messages from one sender produce one notice group', () => {
  const events = [1, 2, 3].map(n => ({
    noticeId: `notice-${n}`,
    senderIdentity: 'FERNANDO',
    recipientIdentity: 'MARIA',
    messageId: `msg-${n}`
  }));
  const groups = groupExpirationNotices([...events, events[1]]);
  assert.equal(groups.length, 1);
  assert.equal(groups[0].count, 3);
  assert.deepEqual(groups[0].messageIds, ['msg-1', 'msg-2', 'msg-3']);
  assert.equal(groups[0].noticeIds.length, 3);
});

test('different conversations remain separate and contain no payload', () => {
  const groups = groupExpirationNotices([
    { noticeId: 'a', senderIdentity: 'A', recipientIdentity: 'B', messageId: 'm1', ciphertextPayload: 'secret' },
    { noticeId: 'b', senderIdentity: 'C', recipientIdentity: 'B', messageId: 'm2' },
    { noticeId: 'c', senderIdentity: 'A', recipientIdentity: 'D', messageId: 'm3' }
  ]);
  assert.equal(groups.length, 3);
  assert.equal(JSON.stringify(groups).includes('secret'), false);
});

test('invalid events fail closed instead of emitting misleading notices', () => {
  assert.throws(() => groupExpirationNotices(null), /array/);
  assert.throws(() => groupExpirationNotices([{ noticeId: 'a' }]), /Missing/);
});
