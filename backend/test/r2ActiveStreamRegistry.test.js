'use strict';
const test=require('node:test');
const assert=require('node:assert/strict');
const {EventEmitter}=require('node:events');
const {createR2ActiveStreamRegistry}=require('../src/r2ActiveStreamRegistry');
const sender='AAAABBBBCCCCDDDD',recipient='1111222233334444';
function stream(messageId='message-1',recipientIdentity=recipient){
  const response=new EventEmitter();
  response.destroyed=false;
  response.writableEnded=false;
  response.destroy=function(){this.destroyed=true;this.emit('close');};
  const abortController=new AbortController();
  return {reference:{senderIdentity:sender,recipientIdentity,messageId},
    abortController,response};
}
test('register then revoke exact message aborts upstream and downstream',()=>{
  const registry=createR2ActiveStreamRegistry(),s=stream();
  const handle=registry.register(s);
  assert.equal(handle.active,true);
  assert.equal(registry.activeCount,1);
  assert.equal(registry.revokeMessage('message-1'),1);
  assert.equal(s.abortController.signal.aborted,true);
  assert.equal(s.response.destroyed,true);
  assert.equal(handle.active,false);
  assert.equal(registry.activeCount,0);
  assert.equal(registry.revokeMessage('message-1'),0);
});
test('revocation by bilateral pair closes both directions and leaves unrelated streams',()=>{
  const r=createR2ActiveStreamRegistry({maxActive:4,maxPerRecipient:4});
  const one=stream('a');
  const two=stream('b','2222333344445555');
  const other={...stream('c','BBBBCCCCDDDDEEEE')};
  other.reference={senderIdentity:'1234123412341234',
    recipientIdentity:'BBBBCCCCDDDDEEEE',messageId:'c'};
  r.register(one);r.register(two);r.register(other);
  assert.equal(r.revokePair(sender,recipient),1);
  assert.equal(one.response.destroyed,true);
  assert.equal(two.response.destroyed,false);
  assert.equal(other.response.destroyed,false);
  assert.equal(r.activeCount,2);
});
test('end-of-response and close remove active session and signal listener',()=>{
  const r=createR2ActiveStreamRegistry(),s=stream();
  const h=r.register(s);
  s.response.emit('finish');
  assert.equal(h.active,false);
  assert.equal(r.activeCount,0);
  assert.equal(r.revokeMessage('message-1'),0);
});
test('manual cancel idempotently aborts and cleans up',()=>{
  const r=createR2ActiveStreamRegistry(),s=stream();
  const h=r.register(s);
  assert.equal(h.cancel(),true);
  assert.equal(h.cancel(),false);
  assert.equal(h.close(),false);
  assert.equal(s.abortController.signal.aborted,true);
});
test('independent controller abort also closes downstream socket',()=>{
  const r=createR2ActiveStreamRegistry(),s=stream();
  r.register(s);
  s.abortController.abort('CLIENT_DISCONNECTED');
  assert.equal(s.response.destroyed,true);
  assert.equal(r.activeCount,0);
});
test('capacity and per-recipient concurrency fail closed',()=>{
  const r=createR2ActiveStreamRegistry({maxActive:2,maxPerRecipient:1});
  r.register(stream('a'));
  assert.throws(()=>r.register(stream('b')),/R2_RECIPIENT_STREAM_CAPACITY/);
  r.register(stream('c','5555666677778888'));
  assert.throws(()=>r.register(stream('d','9999AAAABBBBCCCC')),/R2_STREAM_CAPACITY_EXCEEDED/);
});
test('invalid actor, keys, and request handlers are rejected',()=>{
  const r=createR2ActiveStreamRegistry();
  assert.throws(()=>r.register({...stream(),reference:{
    senderIdentity:'INVALID',recipientIdentity:recipient,messageId:'a'
  }}),/Invalid trusted/);
  assert.throws(()=>r.register({...stream(),response:{destroy(){}}}),/HTTP response/);
  assert.throws(()=>r.revokePair('BAD',recipient),/Invalid R2 pair/);
  assert.throws(()=>r.revokeMessage(''),/Invalid R2 message/);
});
test('no S3 client, network or SQL is used by registry',()=>{
  const r=createR2ActiveStreamRegistry(),s=stream();
  r.register(s);assert.equal(r.activeCount,1);
  assert.equal(r.revokePair(sender,recipient),1);
  assert.equal(r.activeCount,0);
});

test('premature HTTP close aborts upstream immediately instead of waiting for drain',()=>{
  const r=createR2ActiveStreamRegistry(),s=stream();
  const h=r.register(s);
  s.response.destroyed=true;
  s.response.emit('close');
  assert.equal(s.abortController.signal.aborted,true);
  assert.equal(s.abortController.signal.reason,'CLIENT_DISCONNECT');
  assert.equal(h.active,false);
  assert.equal(r.activeCount,0);
  assert.equal(r.revokeMessage('message-1'),0);
});
