'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { attachMediaObject } = require('../src/r2MediaRegistry');

const base = Object.freeze({
  messageId: 'r2-message-1',
  objectKey: 'pending/12/34/12345678-1234-4234-8234-123456789abc',
  senderIdentity: 'AAAABBBBCCCCDDDD',
  authenticatedSenderIdentity: 'AAAABBBBCCCCDDDD',
  recipientIdentity: '1111222233334444',
  senderAlias: 'Alice',
  ciphertextPayload: 'ENC_ECDH:iv:ciphertext',
  ivNonce: 'iv',
  contentSha256: 'a'.repeat(64),
  contentBytes: 300
});
function harness(options = {}) {
  let steps = [];
  const fwd = { from_identity: base.senderIdentity, to_identity: base.recipientIdentity, is_mutual: true };
  const rev = { from_identity: base.recipientIdentity, to_identity: base.senderIdentity, is_mutual: true };
  const media = {
    object_key: base.objectKey, message_id: base.messageId, sender_identity: base.senderIdentity,
    recipient_identity: base.recipientIdentity, content_sha256: base.contentSha256,
    content_bytes: base.contentBytes, state: 'UPLOADED',
    ...options.media
  };
  const stored = options.existing === undefined ? null : options.existing;
  const query = async (sql, params) => {
    steps.push({ sql, params });
    if (sql.includes('FROM public.connections')) {
      const rows = options.connections === undefined ? [fwd,rev] : options.connections;
      return { rows, rowCount: rows.length };
    }
    if (sql.includes('FROM public.message_r2_objects')) {
      const rows = options.missingMedia ? [] : [media];
      return { rows, rowCount: rows.length };
    }
    if (sql.includes('FROM public.message_delivery_receipts')) {
      const rows = options.receipt ? [{ state: 'DELIVERED' }] : [];
      return { rows, rowCount: rows.length };
    }
    if (sql.includes('FROM public.e2ee_messages')) {
      const rows = stored ? [stored] : [];
      return { rows, rowCount: rows.length };
    }
    if (sql.includes('INSERT INTO public.e2ee_messages')) {
      if (options.failInsert) throw Error('POSTGRES_DOWN');
      return { rowCount: options.insertConflict ? 0 : 1, rows: [{id: base.messageId}] };
    }
    if (sql.includes('UPDATE public.message_r2_objects')) {
      if (options.failUpdate) throw Error('ATTACH_DB_ERROR');
      return { rowCount: options.zeroUpdate ? 0 : 1, rows: [{object_key: base.objectKey}] };
    }
    throw Error('UNEXPECTED_SQL');
  };
  return { steps, withTransaction: async fn => fn(query) };
}
function oldMessage() {
  return {
    sender_identity: base.senderIdentity, recipient_identity: base.recipientIdentity,
    sender_alias: base.senderAlias, ciphertext_payload: base.ciphertextPayload,
    iv_nonce: base.ivNonce, delivery_state: 'PENDING', is_cancelled: false, is_delivered: false
  };
}
test('attaches only after mutual permission, media lock, and pending insert', async () => {
  const h = harness();
  const r = await attachMediaObject({ ...base, withTransaction: h.withTransaction });
  assert.equal(r.success,true);
  assert.equal(r.duplicate,false);
  assert.equal(h.steps.length, 6);
  assert.match(h.steps[0].sql,/FOR SHARE/);
  assert.match(h.steps[1].sql,/FOR UPDATE/);
  assert.match(h.steps[3].sql,/clock_timestamp\(\).*48 hours/s);
  assert.match(h.steps[5].sql,/state='ATTACHED'/);
});
test('no insert when connection is missing or unilateral', async () => {
  for (const connections of [[], [{ from_identity: base.senderIdentity, to_identity: base.recipientIdentity, is_mutual: true }]]) {
    const h=harness({connections});
    const r=await attachMediaObject({...base,withTransaction:h.withTransaction});
    assert.equal(r.code,'NO_MUTUAL_CONNECTION');
    assert.equal(h.steps.length,1);
  }
});
test('sender must match externally authenticated context',async()=>{
  const h=harness();
  const r=await attachMediaObject({...base,authenticatedSenderIdentity:'NOT_THE_SENDER',withTransaction:h.withTransaction});
  assert.equal(r.code,'UNAUTHORIZED_SENDER');
  assert.equal(h.steps.length,0);
});
test('missing upload and identity mismatch fail closed',async()=>{
  const missing=harness({missingMedia:true});
  assert.equal((await attachMediaObject({...base,withTransaction:missing.withTransaction})).code,'MEDIA_NOT_FOUND');
  const wrong=harness({media:{recipient_identity:'DIFFERENT'}});
  assert.equal((await attachMediaObject({...base,withTransaction:wrong.withTransaction})).code,'IDENTITY_MISMATCH');
});
test('bad SHA, size, or reserved media never create pending message',async()=>{
  const cases=[
    {media:{content_sha256:'b'.repeat(64)},code:'CONTENT_MISMATCH'},
    {media:{content_bytes:301},code:'CONTENT_MISMATCH'},
    {media:{state:'RESERVED'},code:'INVALID_STATE_FOR_ATTACH'}
  ];
  for(const c of cases){
    const h=harness({media:c.media});
    const r=await attachMediaObject({...base,withTransaction:h.withTransaction});
    assert.equal(r.code,c.code);
    assert.equal(h.steps.some(x=>x.sql.includes('INSERT INTO public.e2ee_messages')),false);
  }
});
test('no message reanimation after terminal receipt',async()=>{
  const h=harness({receipt:true});
  const r=await attachMediaObject({...base,withTransaction:h.withTransaction});
  assert.equal(r.code,'MESSAGE_ALREADY_TERMINAL');
  assert.equal(h.steps.some(x=>x.sql.includes('INSERT INTO public.e2ee_messages')),false);
});
test('exact ATTACHED retries are idempotent',async()=>{
  const h=harness({media:{state:'ATTACHED'},existing:oldMessage()});
  const r=await attachMediaObject({...base,withTransaction:h.withTransaction});
  assert.equal(r.duplicate,true);
  assert.equal(h.steps.some(x=>x.sql.includes('INSERT INTO public.e2ee_messages')),false);
});
test('same message id with different encrypted descriptor is rejected',async()=>{
  const h=harness({existing:{...oldMessage(),ciphertext_payload:'ENC_ECDH:different'}});
  const r=await attachMediaObject({...base,withTransaction:h.withTransaction});
  assert.equal(r.code,'MESSAGE_ID_COLLISION');
  assert.equal(h.steps.some(x=>x.sql.includes('UPDATE public.message_r2_objects')),false);
});
test('database failures propagate to withTransaction rollback',async()=>{
  for(const o of [{failInsert:true},{failUpdate:true},{zeroUpdate:true},{insertConflict:true}]){
    const h=harness(o);
    await assert.rejects(attachMediaObject({...base,withTransaction:h.withTransaction}));
  }
});
test('rejects plaintext descriptors and unbounded strings before accessing db',async()=>{
  for(const ciphertextPayload of ['[IMAGE:abc]','ENC_ECDH:'+'x'.repeat(70000)]){
    const h=harness();
    const r=await attachMediaObject({...base,ciphertextPayload,withTransaction:h.withTransaction});
    assert.equal(r.code,'INVALID_ENCRYPTED_DESCRIPTOR');
    assert.equal(h.steps.length,0);
  }
});
