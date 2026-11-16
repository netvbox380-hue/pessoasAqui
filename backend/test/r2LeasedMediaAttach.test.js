'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { attachLeasedMediaObject } = require('../src/r2MediaRegistry');

const input = {
  messageId: 'leased-message-1',
  objectKey: 'pending/12/34/12345678-1234-4234-8234-123456789abc',
  senderIdentity: 'AAAABBBBCCCCDDDD',
  authenticatedSenderIdentity: 'AAAABBBBCCCCDDDD',
  recipientIdentity: '1111222233334444',
  senderAlias: 'Alice', ciphertextPayload: 'ENC_ECDH:iv:ciphertext',
  ivNonce: 'iv', contentSha256: 'a'.repeat(64), contentBytes: 300,
  uploadGeneration: 1
};
const oldMsg = {
  sender_identity: input.senderIdentity, recipient_identity: input.recipientIdentity,
  sender_alias: input.senderAlias, ciphertext_payload: input.ciphertextPayload,
  iv_nonce: input.ivNonce, delivery_state: 'PENDING',
  is_cancelled: false, is_delivered: false
};
function fakeDb({ patch = {}, existing = null, receipt = false } = {}) {
  const calls = [];
  const media = {
    object_key: input.objectKey, message_id: input.messageId,
    sender_identity: input.senderIdentity, recipient_identity: input.recipientIdentity,
    content_sha256: input.contentSha256, content_bytes: input.contentBytes,
    state: 'UPLOADED', upload_generation: 1, upload_lease_expires_at: '2026-11-15T15:00:00Z',
    uploaded_at: '2026-11-15T14:30:00Z', fresh_upload: true, ...patch
  };
  const tx = async (sql,params) => {
    calls.push({sql,params});
    if(sql.includes('FROM public.connections')) {
      return {rowCount: 2,rows:[
        {from_identity:input.senderIdentity,to_identity:input.recipientIdentity,is_mutual:true},
        {from_identity:input.recipientIdentity,to_identity:input.senderIdentity,is_mutual:true}
      ]};
    }
    if(sql.includes('FROM public.message_r2_objects'))return {rowCount:1,rows:[media]};
    if(sql.includes('FROM public.message_delivery_receipts'))return {
      rowCount:receipt?1:0,rows:receipt?[{state:'DELIVERED'}]:[]
    };
    if(sql.includes('FROM public.e2ee_messages'))return {
      rowCount:existing?1:0,rows:existing?[existing]:[]
    };
    if(sql.includes('INSERT INTO public.e2ee_messages') ||
       sql.includes('UPDATE public.message_r2_objects'))return {rowCount:1,rows:[]};
    throw Error('UNEXPECTED_SQL');
  };
  return {calls,withTransaction:async fn=>fn(tx)};
}
const send=(db, overrides={})=>attachLeasedMediaObject({
  ...input,...overrides,withTransaction:db.withTransaction
});
test('fresh leased attachment succeeds in one transaction and uses DB clock for 2h',async()=>{
  const db=fakeDb();
  const result=await send(db);
  assert.equal(result.success,true);
  assert.equal(result.duplicate,false);
  assert.equal(db.calls.length,6);
  assert.match(db.calls[0].sql,/FOR SHARE/);
  assert.match(db.calls[1].sql,/FOR UPDATE/);
  assert.match(db.calls[1].sql,/upload_generation/);
  assert.match(db.calls[1].sql,/uploaded_at >= clock_timestamp\(\) - interval '2 hours'/);
});
test('older uploaded media is rejected before creating any message',async()=>{
  const db=fakeDb({patch:{fresh_upload:false}});
  const result=await send(db);
  assert.equal(result.code,'UPLOAD_WINDOW_EXPIRED');
  assert.equal(db.calls.some(x=>x.sql.includes('INSERT INTO public.e2ee_messages')),false);
});
test('missing DB lease and mismatched generation are fail closed',async()=>{
  for(const patch of [{upload_lease_expires_at:null},{upload_generation:2},
                       {uploaded_at:null}]) {
    const db=fakeDb({patch});
    const result=await send(db);
    assert.equal(result.code,'UNLEASED_OR_WRONG_GENERATION');
    assert.equal(db.calls.length,2);
  }
});
test('generation is mandatory for future leased API',async()=>{
  for(const uploadGeneration of [undefined,0,2,'1']) {
    const db=fakeDb();
    const result=await send(db,{uploadGeneration});
    assert.equal(result.code,'INVALID_UPLOAD_GENERATION');
    assert.equal(db.calls.length,0);
  }
});
test('exact retry after attachment remains idempotent beyond upload freshness window',async()=>{
  const db=fakeDb({patch:{state:'ATTACHED',fresh_upload:false},existing:oldMsg});
  const result=await send(db);
  assert.equal(result.success,true);
  assert.equal(result.duplicate,true);
  assert.equal(db.calls.some(x=>x.sql.includes('INSERT INTO public.e2ee_messages')),false);
});
test('a terminalized message cannot be resurrected by leased attachment',async()=>{
  const db=fakeDb({receipt:true});
  const result=await send(db);
  assert.equal(result.code,'MESSAGE_ALREADY_TERMINAL');
  assert.equal(db.calls.some(x=>x.sql.includes('INSERT INTO public.e2ee_messages')),false);
});

test('caller cannot bypass lease by passing legacy requireLeasedUpload=false',async()=>{
  const db=fakeDb({patch:{upload_lease_expires_at:null}});
  const result=await send(db,{requireLeasedUpload:false});
  assert.equal(result.code,'UNLEASED_OR_WRONG_GENERATION');
  assert.equal(db.calls.some(x=>x.sql.includes('INSERT INTO public.e2ee_messages')),false);
});
test('direct attachMediaObject also enforces mandatory generation and lease',async()=>{
  const { attachMediaObject } = require('../src/r2MediaRegistry');
  const withoutGeneration=fakeDb();
  const missing=await attachMediaObject({
    ...input,uploadGeneration:undefined,requireLeasedUpload:false,
    withTransaction:withoutGeneration.withTransaction
  });
  assert.equal(missing.code,'INVALID_UPLOAD_GENERATION');
  assert.equal(withoutGeneration.calls.length,0);
  const noLease=fakeDb({patch:{upload_lease_expires_at:null}});
  const rejected=await attachMediaObject({
    ...input,requireLeasedUpload:false,withTransaction:noLease.withTransaction
  });
  assert.equal(rejected.code,'UNLEASED_OR_WRONG_GENERATION');
});
