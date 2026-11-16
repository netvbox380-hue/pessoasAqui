'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { authorizeRecipientR2Read } = require('../src/r2RecipientReadAuth');

const actor='1111222233334444', peer='AAAABBBBCCCCDDDD';
const objectKey='pending/12/34/12345678-1234-4234-8234-123456789abc';
const messageId='msg-read-test-1';
const base={authenticatedIdentity:actor,peerIdentity:peer,messageId,objectKey};
function db(overrides={}){
  const calls=[];
  const row={
    object_key:objectKey,message_id:messageId,sender_identity:peer,
    recipient_identity:actor,content_sha256:'a'.repeat(64),
    content_bytes:'1234',state:'ATTACHED',message_sender:peer,
    message_recipient:actor,delivery_state:'PENDING',is_cancelled:false,
    expires_at:'2026-11-17T12:00:00Z',is_active:true,...overrides.row
  };
  const tx=async(sql,params)=>{
    calls.push({sql,params});
    if (sql.includes('FROM public.message_r2_objects')) {
      return {rowCount:overrides.missing?0:1,rows:overrides.missing?[]:[row]};
    }
    if (sql.includes('FROM public.message_delivery_receipts')) {
      return {rowCount:overrides.terminal?1:0,rows:overrides.terminal?[{state:'DELIVERED'}]:[]};
    }
    throw Error('unexpected SQL');
  };
  return {tx,calls};
}
test('recipient receives bounded metadata only after exact object+message locks',async()=>{
  const h=db();
  const result=await authorizeRecipientR2Read({tx:h.tx,...base});
  assert.equal(result.allowed,true);
  assert.equal(result.authenticatedIdentity,actor);
  assert.equal(result.peerIdentity,peer);
  assert.equal(result.expectedBytes,1234);
  assert.equal(result.expectedSha256,'a'.repeat(64));
  assert.equal('url' in result,false);
  assert.equal('ciphertext' in result,false);
  assert.equal(h.calls.length,2);
  assert.match(h.calls[0].sql,/FOR SHARE OF o,m/);
  assert.match(h.calls[0].sql,/UPPER\(m.recipient_identity\)=\$4/);
  assert.deepEqual(h.calls[0].params,[objectKey,messageId,peer,actor]);
});
test('unauthorized object and nonmatching message fail closed',async()=>{
  const h=db({missing:true});
  const result=await authorizeRecipientR2Read({tx:h.tx,...base});
  assert.equal(result.allowed,false);
  assert.equal(result.code,'R2_OBJECT_NOT_AUTHORIZED');
  assert.equal(h.calls.length,1);
});
test('sender cannot impersonate the recipient',async()=>{
  const h=db({missing:true});
  const result=await authorizeRecipientR2Read({
    tx:h.tx,...base,authenticatedIdentity:peer,peerIdentity:actor
  });
  assert.equal(result.allowed,false);
  assert.deepEqual(h.calls[0].params,[objectKey,messageId,actor,peer]);
});
test('expired, revoked, delivered, or unattached message is not readable',async()=>{
  for (const row of [
    {is_active:false},{is_cancelled:true},{delivery_state:'DELIVERED'},
    {state:'UPLOADED'},{state:'ORPHAN_QUEUED'}
  ]) {
    const h=db({row});
    const result=await authorizeRecipientR2Read({tx:h.tx,...base});
    assert.equal(result.code,'R2_MESSAGE_NOT_DOWNLOADABLE');
    assert.equal(h.calls.length,1);
  }
});
test('terminal receipt overrides pending state',async()=>{
  const h=db({terminal:true});
  const result=await authorizeRecipientR2Read({tx:h.tx,...base});
  assert.equal(result.code,'R2_MESSAGE_TERMINAL');
  assert.equal(h.calls.length,2);
});
test('malformed object descriptor refuses read',async()=>{
  for (const row of [
    {content_sha256:'bad'},{content_bytes:0},
    {content_bytes:25*1024*1024+1}
  ]) {
    const h=db({row});
    const result=await authorizeRecipientR2Read({tx:h.tx,...base});
    assert.equal(result.code,'R2_INVALID_STORED_DESCRIPTOR');
  }
});
test('malformed identity, message or object key fail before SQL',async()=>{
  for (const payload of [
    {authenticatedIdentity:'BOB'}, {peerIdentity:actor},
    {messageId:''}
  ]) {
    const h=db();
    const result=await authorizeRecipientR2Read({tx:h.tx,...base,...payload});
    assert.equal(result.allowed,false);
    assert.equal(h.calls.length,0);
  }
  await assert.rejects(authorizeRecipientR2Read({
    tx:db().tx,...base,objectKey:'../other'
  }),/Invalid R2 object key/);
});
test('PostgreSQL failure propagates instead of authorizing from RAM',async()=>{
  await assert.rejects(authorizeRecipientR2Read({
    tx:async()=>{throw Error('POSTGRES_DOWN');},...base
  }),/POSTGRES_DOWN/);
});
