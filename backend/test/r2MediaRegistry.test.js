'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { reserveMediaObject, markMediaUploaded, validateContentDescriptor } = require('../src/r2MediaRegistry');
const K = 'pending/12/34/12345678-1234-4234-8234-123456789abc';
const H = 'a'.repeat(64);
const reservation = {
  objectKey: K, messageId: 'msg-1',
  senderIdentity: 'SENDER0001', recipientIdentity: 'RECEIVER01'
};
test('reserve creates a durable record before any upload begins', async () => {
  const calls = [];
  const result = await reserveMediaObject({
    query: async (sql, p) => {
      calls.push({ sql, p });
      return { rowCount: 1, rows: [{ object_key: K }] };
    }, ...reservation
  });
  assert.deepEqual(result, { reserved: true, duplicate: false });
  assert.match(calls[0].sql, /INSERT INTO public.message_r2_objects/);
  assert.match(calls[0].sql, /ON CONFLICT DO NOTHING/);
  assert.equal(calls[0].p[0], K);
});

test('reservation retry is idempotent with exact identity and object binding', async () => {
  const result = await reserveMediaObject({
    query: async sql => sql.includes('INSERT') ?
      { rowCount: 0 } : { rows: [{ object_key: K, sender_identity: 'sender0001', recipient_identity: 'receiver01', state: 'RESERVED' }] },
    ...reservation
  });
  assert.deepEqual(result, { reserved: true, duplicate: true, state: 'RESERVED' });
});

test('reservation collision cannot repoint object or identities', async () => {
  const result = await reserveMediaObject({
    query: async sql => sql.includes('INSERT') ?
      { rowCount: 0 } : { rows: [{ object_key: 'other-key', sender_identity: 'SENDER0001', recipient_identity: 'RECEIVER01', state: 'RESERVED' }] },
    ...reservation
  });
  assert.equal(result.code, 'CONFLICT');
  assert.equal(result.reserved, false);
});

test('uploaded media is bound to hash and byte count', async () => {
  const statements = [];
  const result = await markMediaUploaded({
    query: async (sql, args) => {
      statements.push({ sql, args });
      return { rowCount: 1, rows: [{ object_key: K }] };
    },
    objectKey: K, messageId: 'msg-1', contentSha256: H, contentBytes: 1024
  });
  assert.deepEqual(result, { uploaded: true, duplicate: false });
  assert.match(statements[0].sql, /state='RESERVED'/);
  assert.deepEqual(statements[0].args, [K, 'msg-1', H, 1024]);
});

test('repeated upload confirmation checks exact digest, not just the message ID', async () => {
  const good = await markMediaUploaded({
    query: async sql => sql.includes('UPDATE') ? { rowCount: 0 } :
      { rows: [{ state: 'UPLOADED', content_sha256: H, content_bytes: '1024' }] },
    objectKey: K, messageId: 'msg-1', contentSha256: H, contentBytes: 1024
  });
  assert.equal(good.duplicate, true);
  const bad = await markMediaUploaded({
    query: async sql => sql.includes('UPDATE') ? { rowCount: 0 } :
      { rows: [{ state: 'UPLOADED', content_sha256: 'b'.repeat(64), content_bytes: '1024' }] },
    objectKey: K, messageId: 'msg-1', contentSha256: H, contentBytes: 1024
  });
  assert.equal(bad.uploaded, false);
});

test('invalid object identifiers and payload metadata fail closed', async () => {
  await assert.rejects(reserveMediaObject({
    query: async () => { throw new Error('SHOULD_NOT_REACH_DB'); },
    ...reservation, objectKey: '../private'
  }), /Invalid R2 object key/);
  assert.throws(() => validateContentDescriptor({ contentSha256: H, contentBytes: 0 }), /Invalid/);
  assert.throws(() => validateContentDescriptor({ contentSha256: 'bad', contentBytes: 3 }), /Invalid/);
  assert.throws(() => validateContentDescriptor({ contentSha256: H, contentBytes: 26214401 }), /Invalid/);
});

test('upload is not marked successful when database update fails', async () => {
  await assert.rejects(markMediaUploaded({
    query: async () => { throw Error('POSTGRES_DOWN'); },
    objectKey: K, messageId: 'msg-1', contentSha256: H, contentBytes: 1024
  }), /POSTGRES_DOWN/);
});
