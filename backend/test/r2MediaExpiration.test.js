'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { expireR2Message } = require('../src/r2DeliveryFinalizer');

const messageId = 'm-media';
const noticeId = '12345678-1234-4234-8234-123456789abc';
const media = {
  object_key: 'pending/12/34/12345678-1234-4234-8234-123456789abc',
  message_id: messageId, sender_identity: 'AAAABBBBCCCCDDDD',
  recipient_identity: '1111222233334444', state: 'ATTACHED',
  content_sha256: 'b'.repeat(64), content_bytes: 200
};
const msg = {
  id: messageId, sender_identity: media.sender_identity,
  recipient_identity: media.recipient_identity,
  ciphertext_payload: 'ENC_ECDH:iv:ciphertext', iv_nonce: 'iv',
  expires_at: '2026-11-15T12:00:00Z',
  delivery_state: 'PENDING', is_cancelled: false, is_delivered: false
};
function fakeDatabase({ object = media, row = msg, receipt = null,
                        now = '2026-11-15T12:00:00Z',
                        fail = '', deleteRowCount = 1,
                        putWindow = { sealed_at: null, expires_at: '2026-11-14T12:00:00Z' } } = {}) {
  const statements = [];
  const committed = [];
  let rollbacks = 0;
  const withTransaction = async fn => {
    const pending = [];
    try {
      const result = await fn(async (sql, params = []) => {
        statements.push({ sql, params });
        if (fail && sql.includes(fail)) throw new Error('INJECTED_DB_FAILURE');
        if (sql.includes('DELETE FROM public.e2ee_messages')) {
          if (deleteRowCount === 1) pending.push({ sql, params });
          return { rowCount: deleteRowCount };
        }
        if (sql.includes('FROM public.message_r2_objects')) {
          return { rows: object ? [object] : [], rowCount: object ? 1 : 0 };
        }
        if (sql.includes('FROM public.message_delivery_receipts')) {
          return { rows: receipt ? [receipt] : [], rowCount: receipt ? 1 : 0 };
        }
        if (sql.includes('FROM public.e2ee_messages')) {
          return { rows: row ? [row] : [], rowCount: row ? 1 : 0 };
        }
        if (sql.includes('clock_timestamp() AS now')) return { rows: [{ now }] };
        if (sql.includes('UPDATE public.r2_put_capability_windows')) {
          assert.deepEqual(params, [object.object_key]);
          assert.match(sql, /sealed_at=COALESCE\(sealed_at,clock_timestamp\(\)\)/);
          if (!putWindow) return { rows: [], rowCount: 0 };
          const sealed = { ...putWindow, sealed_at: putWindow.sealed_at || now };
          pending.push({ sql, params, sealed });
          return { rows: [sealed], rowCount: 1 };
        }
        if (sql.includes('INSERT INTO public.message_delivery_receipts') ||
            sql.includes('INSERT INTO public.message_expiration_notices') ||
            sql.includes('INSERT INTO public.message_r2_delete_outbox') ||
            sql.includes('UPDATE public.message_r2_objects')) {
          pending.push({ sql, params });
          return { rowCount: 1 };
        }
        throw Error('UNKNOWN_SQL');
      });
      committed.push(...pending);
      return result;
    } catch (error) {
      rollbacks++;
      throw error;
    }
  };
  return { statements, committed, withTransaction, get rollbacks() { return rollbacks; } };
}
function expire(db) {
  return expireR2Message({ withTransaction: db.withTransaction, messageId, noticeId });
}
test('at deadline expiration atomically creates receipt, notice, outbox and removes ciphertext', async () => {
  const db = fakeDatabase();
  const result = await expire(db);
  assert.deepEqual(result, { success: true, state: 'EXPIRED', noticeId });
  const sqls = db.statements.map(x => x.sql);
  const iReceipt = sqls.findIndex(x => x.includes('INSERT INTO public.message_delivery_receipts'));
  const iNotice = sqls.findIndex(x => x.includes('INSERT INTO public.message_expiration_notices'));
  const iMedia = sqls.findIndex(x => x.includes('UPDATE public.message_r2_objects'));
  const iSeal = sqls.findIndex(x => x.includes('UPDATE public.r2_put_capability_windows'));
  const iOutbox = sqls.findIndex(x => x.includes('INSERT INTO public.message_r2_delete_outbox'));
  const iDelete = sqls.findIndex(x => x.includes('DELETE FROM public.e2ee_messages'));
  assert.ok(iReceipt >= 0 && iNotice > iReceipt && iMedia > iNotice &&
    iSeal > iMedia && iOutbox > iSeal && iDelete > iOutbox);
  assert.equal(db.committed.length, 6);
  assert.equal(db.rollbacks, 0);
  assert.equal(db.committed.find(x => x.sealed).sealed.sealed_at, '2026-11-15T12:00:00Z');
  assert.match(sqls[iDelete], /expires_at <= clock_timestamp\(\)/);
  assert.match(sqls[0], /FOR UPDATE/);
  assert.match(sqls[2], /FOR UPDATE/);
  const noticeParams = db.statements[iNotice].params;
  assert.deepEqual(noticeParams.slice(0, 4),
    [noticeId, messageId, media.sender_identity, media.recipient_identity]);
  assert.equal(JSON.stringify(noticeParams).includes(msg.ciphertext_payload), false);
});
test('before exact 48h deadline no state changes are attempted', async () => {
  const db = fakeDatabase({ now: '2026-11-15T11:59:59.999Z' });
  const result = await expire(db);
  assert.equal(result.code, 'NOT_EXPIRED');
  assert.equal(db.statements.some(x => x.sql.includes('INSERT INTO')), false);
});
test('existing delivered receipt blocks expiration and duplicate notices', async () => {
  const db = fakeDatabase({ receipt: { state: 'DELIVERED' } });
  const result = await expire(db);
  assert.equal(result.code, 'ALREADY_FINALIZED');
  assert.equal(result.state, 'DELIVERED');
  assert.equal(db.statements.some(x => x.sql.includes('INSERT INTO')), false);
});
test('cancelled and delivered messages are never expired as pending', async () => {
  for (const update of [{ is_cancelled: true }, { is_delivered: true },
                        { delivery_state: 'CANCELLED' }]) {
    const db = fakeDatabase({ row: { ...msg, ...update } });
    assert.equal((await expire(db)).code, 'NOT_EXPIRABLE_PENDING');
  }
});
test('missing object or orphan upload is never expired as delivery', async () => {
  for (const update of [{ object: null }, { object: { ...media, state: 'UPLOADED' } }]) {
    const db = fakeDatabase(update);
    const result = await expire(db);
    assert.equal(result.success, false);
    assert.equal(db.statements.some(x => x.sql.includes('INSERT INTO')), false);
  }
});
test('missing message cannot create receipt or delete media', async () => {
  const db = fakeDatabase({ row: null });
  assert.equal((await expire(db)).code, 'NOT_EXPIRABLE_PENDING');
  assert.equal(db.statements.some(x => x.sql.includes('INSERT INTO')), false);
});
test('database failure in notice insertion propagates for transaction rollback', async () => {
  const db = fakeDatabase({ fail: 'INSERT INTO public.message_expiration_notices' });
  await assert.rejects(expire(db), /INJECTED_DB_FAILURE/);
  assert.equal(db.committed.length, 0);
  assert.equal(db.rollbacks, 1);
});
test('DB conflict in final deadline check must rollback all side effects', async () => {
  const db = fakeDatabase({ deleteRowCount: 0 });
  await assert.rejects(expire(db), /R2_EXPIRY_DEADLINE_CONFLICT/);
  assert.equal(db.committed.length, 0);
  assert.equal(db.rollbacks, 1);
});
test('invalid object key blocks all SQL mutations', async () => {
  const db = fakeDatabase({ object: { ...media, object_key: '../secret' } });
  await assert.rejects(expire(db), /Invalid R2 object key/);
  assert.equal(db.statements.some(x => x.sql.includes('INSERT INTO')), false);
});
test('invalid notice ID is rejected before any database access', async () => {
  const db = fakeDatabase();
  const r = await expireR2Message({ withTransaction: db.withTransaction, messageId,
    noticeId: 'NOT-A-UUID' });
  assert.equal(r.code, 'INVALID_ARGUMENT');
  assert.equal(db.statements.length, 0);
});

test('unknown PUT window aborts expiration before outbox or ciphertext deletion', async () => {
  const db = fakeDatabase({ putWindow: null });
  await assert.rejects(expire(db), /R2_PUT_WINDOW_UNKNOWN/);
  assert.equal(db.committed.length, 0);
  assert.equal(db.rollbacks, 1);
  assert.equal(db.statements.some(x => x.sql.includes('INSERT INTO public.message_r2_delete_outbox')), false);
  assert.equal(db.statements.some(x => x.sql.includes('DELETE FROM public.e2ee_messages')), false);
});
test('PUT fence SQL failure aborts all staged expiration mutations', async () => {
  const db = fakeDatabase({ fail: 'UPDATE public.r2_put_capability_windows' });
  await assert.rejects(expire(db), /INJECTED_DB_FAILURE/);
  assert.equal(db.committed.length, 0);
  assert.equal(db.rollbacks, 1);
  assert.equal(db.statements.some(x => x.sql.includes('INSERT INTO public.message_r2_delete_outbox')), false);
});
test('already sealed PUT window preserves original retirement timestamp', async () => {
  const original = '2026-11-14T13:00:00Z';
  const db = fakeDatabase({ putWindow: { sealed_at: original, expires_at: '2026-11-14T12:00:00Z' } });
  assert.equal((await expire(db)).success, true);
  assert.equal(db.committed.find(x => x.sealed).sealed.sealed_at, original);
});
test('existing expired receipt blocks repeat expiration and PUT retirement', async () => {
  const db = fakeDatabase({ receipt: { state: 'EXPIRED' } });
  assert.equal((await expire(db)).code, 'ALREADY_FINALIZED');
  assert.equal(db.committed.length, 0);
  assert.equal(db.statements.some(x => /UPDATE|INSERT INTO|DELETE FROM/.test(x.sql.replace('FOR UPDATE',''))), false);
});
