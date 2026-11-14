'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const {
  makeOpaqueObjectKey, validateOpaqueObjectKey, sha256Hex,
  uploadCiphertext, fetchCiphertext, deleteCiphertext
} = require('../src/r2CiphertextAdapter');

const FIXED_UUID = '12345678-1234-4234-8234-123456789abc';
class PutObjectCommand { constructor(input) { this.input = input; } }
class GetObjectCommand { constructor(input) { this.input = input; } }
class DeleteObjectCommand { constructor(input) { this.input = input; } }

test('R2 key contains UUID v4 only and no user identity', () => {
  const key = makeOpaqueObjectKey(() => FIXED_UUID);
  assert.equal(key, 'pending/12/34/12345678-1234-4234-8234-123456789abc');
  assert.equal(validateOpaqueObjectKey(key), key);
  assert.throws(() => validateOpaqueObjectKey('../other-secret'), /Invalid/);
  assert.throws(() => makeOpaqueObjectKey(() => 'user-id'), /UUID/);
});

test('uploads only opaque encrypted bytes into private bucket', async () => {
  const commands = [];
  const ciphertext = Buffer.from('OPAQUE-ENCRYPTED-CONTENT');
  const result = await uploadCiphertext({
    client: { send: async c => { commands.push(c); return {}; } },
    PutObjectCommand,
    bucket: 'pessoasaqui',
    ciphertext,
    randomUUID: () => FIXED_UUID
  });
  assert.equal(result.objectKey, 'pending/12/34/12345678-1234-4234-8234-123456789abc');
  assert.equal(result.sha256, sha256Hex(ciphertext));
  assert.equal(result.bytes, ciphertext.length);
  assert.equal(commands.length, 1);
  assert.deepEqual(commands[0].input, {
    Bucket: 'pessoasaqui',
    Key: result.objectKey,
    Body: ciphertext,
    ContentType: 'application/octet-stream',
    ContentLength: ciphertext.length
  });
  assert.equal(commands[0].input.Metadata, undefined);
  assert.equal(commands[0].input.ACL, undefined);
});

test('rejects empty/oversized payload before any R2 request', async () => {
  const cmds = [];
  for (const ciphertext of [Buffer.alloc(0), Buffer.alloc(5)]) {
    await assert.rejects(
      uploadCiphertext({
        client: { send: async c => { cmds.push(c); } },
        PutObjectCommand, bucket: 'pessoasaqui', ciphertext, maxBytes: 4
      }), /Invalid encrypted payload size/
    );
  }
  assert.equal(cmds.length, 0);
});

test('rejects invalid bucket and driver before sending', async () => {
  await assert.rejects(uploadCiphertext({
    client: { send: async () => {} }, PutObjectCommand,
    bucket: 'bad/bucket', ciphertext: Buffer.from('secret')
  }), /bucket/);
  await assert.rejects(uploadCiphertext({
    client: null, PutObjectCommand, bucket: 'pessoasaqui', ciphertext: Buffer.from('secret')
  }), /client/);
});

test('R2 upload errors propagate and do not claim durable queue state', async () => {
  await assert.rejects(uploadCiphertext({
    client: { send: async () => { throw new Error('R2_DOWN'); } },
    PutObjectCommand, bucket: 'pessoasaqui', ciphertext: Buffer.from('enc')
  }), /R2_DOWN/);
});

test('download refuses oversized content before consuming stream', async () => {
  let destroyed = false;
  const r = await fetchCiphertext({
    client: { send: async () => ({
      ContentLength: 2,
      Body: { destroy() { destroyed = true; } }
    }) },
    GetObjectCommand, bucket: 'pessoasaqui',
    objectKey: makeOpaqueObjectKey(() => FIXED_UUID),
    maxBytes: 8
  });
  assert.equal(r.contentLength, 2);
  assert.equal(destroyed, false);
  await assert.rejects(fetchCiphertext({
    client: { send: async () => ({
      ContentLength: 11, Body: { destroy() { destroyed = true; } }
    }) },
    GetObjectCommand, bucket: 'pessoasaqui',
    objectKey: makeOpaqueObjectKey(() => FIXED_UUID),
    maxBytes: 8
  }), /exceeds/);
  assert.equal(destroyed, true);
});

test('S3 deletion uses exact validated key and bubbles failures for outbox retry', async () => {
  const key = makeOpaqueObjectKey(() => FIXED_UUID);
  const calls = [];
  const result = await deleteCiphertext({
    client: { send: async cmd => { calls.push(cmd); return {}; } },
    DeleteObjectCommand, bucket: 'pessoasaqui', objectKey: key
  });
  assert.deepEqual(result, { deleted: true, objectKey: key });
  assert.deepEqual(calls[0].input, { Bucket: 'pessoasaqui', Key: key });
  await assert.rejects(deleteCiphertext({
    client: { send: async () => { throw Error('503'); } },
    DeleteObjectCommand, bucket: 'pessoasaqui', objectKey: key
  }), /503/);
});

test('rejects object traversal on download and deletion', async () => {
  const client = { send: async () => { throw Error('SHOULD_NOT_SEND'); } };
  await assert.rejects(fetchCiphertext({
    client, GetObjectCommand, bucket: 'pessoasaqui', objectKey: '../test'
  }), /Invalid R2 object key/);
  await assert.rejects(deleteCiphertext({
    client, DeleteObjectCommand, bucket: 'pessoasaqui', objectKey: '/private-key'
  }), /Invalid R2 object key/);
});
