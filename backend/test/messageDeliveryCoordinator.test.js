'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const {
  stablePayloadDigest, confirmInlineDelivery, expireInlineMessage
} = require('../src/messageDeliveryCoordinator');
const NOTICE = '00000000-0000-4000-8000-000000000001';
const BASE = Object.freeze({
  id: 'msg-1',
  sender_identity: 'SENDER0001',
  recipient_identity: 'RECEIVER01',
  ciphertext_payload: 'encrypted-only',
  iv_nonce: 'nonce',
  expires_at: '2026-11-17T12:00:00Z',
  delivery_state: 'PENDING',
  is_cancelled: false
});

function fakeTx({ row = BASE, now = '2026-11-16T10:00:00Z', receipt = null, deletedRows = 1 } = {}) {
  const queries = [];
  let inserted = 0;
  let deleted = 0;
  return {
    queries,
    get inserted() { return inserted; },
    get deleted() { return deleted; },
    withTransaction: async fn => fn(async (sql, params) => {
      queries.push({ sql, params });
      if (sql.includes('FROM public.e2ee_messages WHERE id')) return { rows: row ? [{ ...row }] : [], rowCount: row ? 1 : 0 };
      if (sql.includes('FROM public.message_delivery_receipts WHERE message_id')) return { rows: receipt ? [{ ...receipt }] : [] };
      if (sql.includes('clock_timestamp() AS now')) return { rows: [{ now }] };
      if (sql.includes('INSERT INTO public.message_delivery_receipts')) {
        inserted += 1;
        return { rowCount: 1 };
      }
      if (sql.includes('INSERT INTO public.message_expiration_notices')) return { rowCount: 1 };
      if (sql.includes('DELETE FROM public.e2ee_messages')) {
        deleted += 1;
        return { rows: deletedRows ? [{ id: 'msg-1' }] : [], rowCount: deletedRows };
      }
      throw new Error(`Unexpected SQL ${sql}`);
    })
  };
}

test('canonical digest is deterministic and separated by length', () => {
  const d = stablePayloadDigest(BASE);
  assert.match(d, /^[0-9a-f]{64}$/);
  assert.equal(d, stablePayloadDigest({ ...BASE }));
  assert.notEqual(d, stablePayloadDigest({ ...BASE, ciphertext_payload: 'other' }));
});

test('ACK after local persistence deletes an inline pending payload transactionally', async () => {
  const db = fakeTx();
  const result = await confirmInlineDelivery({
    withTransaction: db.withTransaction, messageId: 'msg-1', recipientIdentity: 'receiver01'
  });
  assert.deepEqual(result, {
    success: true, acknowledged: true, state: 'DELIVERED',
    duplicate: false, statusCode: 200
  });
  assert.equal(db.inserted, 1);
  assert.equal(db.deleted, 1);
  assert.equal(db.queries.some(q => q.sql.includes('FOR UPDATE')), true);
});

test('ACK not owned by recipient is rejected without changing state', async () => {
  const db = fakeTx();
  const result = await confirmInlineDelivery({
    withTransaction: db.withTransaction, messageId: 'msg-1', recipientIdentity: 'STRANGER01'
  });
  assert.equal(result.statusCode, 403);
  assert.equal(db.inserted, 0);
  assert.equal(db.deleted, 0);
});

test('unmigrated rows and R2-backed payloads fail closed', async () => {
  for (const row of [
    { ...BASE, expires_at: null },
    { ...BASE, delivery_state: null },
    { ...BASE, ciphertext_payload: null }
  ]) {
    const db = fakeTx({ row });
    const result = await confirmInlineDelivery({
      withTransaction: db.withTransaction, messageId: 'msg-1', recipientIdentity: 'RECEIVER01'
    });
    assert.equal(result.success, false);
    assert.equal(db.deleted, 0);
  }
});

test('expired ACK cannot claim delivery', async () => {
  const db = fakeTx({ now: '2026-11-17T12:00:00Z' });
  const result = await confirmInlineDelivery({
    withTransaction: db.withTransaction, messageId: 'msg-1', recipientIdentity: 'RECEIVER01'
  });
  assert.equal(result.code, 'EXPIRED');
  assert.equal(db.inserted, 0);
  assert.equal(db.deleted, 0);
});

test('ACK retry recognizes receipt even after removal from ciphertext table', async () => {
  const db = fakeTx({
    row: null, receipt: { recipient_identity: 'RECEIVER01', state: 'DELIVERED' }
  });
  const result = await confirmInlineDelivery({
    withTransaction: db.withTransaction, messageId: 'msg-1', recipientIdentity: 'receiver01'
  });
  assert.equal(result.duplicate, true);
  assert.equal(result.acknowledged, true);
  assert.equal(db.deleted, 0);
});

test('expiry after 48 hours writes content-free notice, receipt and deletes', async () => {
  const db = fakeTx({ now: '2026-11-17T12:00:00Z' });
  const result = await expireInlineMessage({
    withTransaction: db.withTransaction, messageId: 'msg-1', noticeId: NOTICE
  });
  assert.equal(result.state, 'EXPIRED');
  assert.equal(db.inserted, 1);
  assert.equal(db.deleted, 1);
  const notice = db.queries.find(q => q.sql.includes('INSERT INTO public.message_expiration_notices'));
  assert.ok(notice);
  assert.deepEqual(notice.params.slice(0, 4), [NOTICE, 'msg-1', 'SENDER0001', 'RECEIVER01']);
  assert.equal(JSON.stringify(notice).includes(BASE.ciphertext_payload), false);
});

test('expiry before deadline is rejected', async () => {
  const db = fakeTx({ now: '2026-11-17T11:59:59Z' });
  const result = await expireInlineMessage({
    withTransaction: db.withTransaction, messageId: 'msg-1', noticeId: NOTICE
  });
  assert.equal(result.code, 'NOT_EXPIRED');
  assert.equal(db.deleted, 0);
});

test('expiry cannot override a cancelled message', async () => {
  const db = fakeTx({ row: { ...BASE, is_cancelled: true } });
  const result = await expireInlineMessage({
    withTransaction: db.withTransaction, messageId: 'msg-1', noticeId: NOTICE
  });
  assert.equal(result.code, 'NOT_EXPIRABLE_INLINE');
  assert.equal(db.deleted, 0);
});

test('storage deletion conflict throws for transactional rollback', async () => {
  const db = fakeTx({ deletedRows: 0 });
  await assert.rejects(
    confirmInlineDelivery({
      withTransaction: db.withTransaction, messageId: 'msg-1', recipientIdentity: 'RECEIVER01'
    }),
    /DELIVERY_TRANSITION_CONFLICT/
  );
});
