'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { confirmR2Delivery } = require('../src/r2DeliveryFinalizer');

const id='m1';
const receiver='1111222233334444';
const sender='AAAABBBBCCCCDDDD';
const key='pending/12/34/12345678-1234-4234-8234-123456789abc';
const obj={
  object_key:key, message_id:id, sender_identity:sender,
  recipient_identity:receiver, state:'ATTACHED',
  content_sha256:'a'.repeat(64),content_bytes:200
};
const msg={
  id, sender_identity:sender, recipient_identity:receiver,
  ciphertext_payload:'ENC_ECDH:iv:encrypted',
  iv_nonce:'iv', expires_at:'2026-11-16T12:00:00.000Z',
  delivery_state:'PENDING',is_cancelled:false,is_delivered:false
};
function fakeDb({object=obj,message=msg,receipt=null,now='2026-11-15T12:00:00Z',fail='',deleteRows=1,missingWindow=false}={}) {
  const steps=[];
  const withTransaction=async work=>work(async (sql,params)=>{
    steps.push({sql,params});
    if(fail==='receipt' && sql.includes('INSERT INTO public.message_delivery_receipts'))throw Error('DB_RECEIPT_DOWN');
    if(fail==='outbox' && sql.includes('INSERT INTO public.message_r2_delete_outbox'))throw Error('DB_OUTBOX_DOWN');
    if(sql.includes('FROM public.message_r2_objects'))return {rows:object?[object]:[],rowCount:object?1:0};
    if(sql.includes('FROM public.message_delivery_receipts'))return {rows:receipt?[receipt]:[],rowCount:receipt?1:0};
    if(sql.includes('DELETE FROM public.e2ee_messages'))return {rowCount:deleteRows};
    if(sql.includes('SELECT') && sql.includes('FROM public.e2ee_messages'))return {rows:message?[message]:[],rowCount:message?1:0};
    if(sql.includes('clock_timestamp() AS now'))return {rows:[{now}]};
    if(sql.includes('INSERT INTO public.message_delivery_receipts'))return {rowCount:1};
    if(sql.includes('UPDATE public.message_r2_objects'))return {rowCount:1};
    if(sql.includes('UPDATE public.r2_put_capability_windows')){
      if(fail==='seal')throw Error('DB_PUT_SEAL_DOWN');
      return {rowCount:missingWindow?0:1};
    }
    if(sql.includes('INSERT INTO public.message_r2_delete_outbox'))return {rowCount:1};
    throw Error('UNEXPECTED_SQL');
  });
  return {steps,withTransaction};
}
const call=(db,authenticatedRecipientIdentity=receiver)=>confirmR2Delivery({
  withTransaction:db.withTransaction,messageId:id,authenticatedRecipientIdentity
});
test('ACK only queues deletion after receipt and media terminalization',async()=>{
  const db=fakeDb();
  const r=await call(db);
  assert.equal(r.success,true);
  assert.equal(r.acknowledged,true);
  const s=db.steps.map(x=>x.sql);
  const receipt=s.findIndex(x=>x.includes('INSERT INTO public.message_delivery_receipts'));
  const media=s.findIndex(x=>x.includes('UPDATE public.message_r2_objects'));
  const seal=s.findIndex(x=>x.includes('UPDATE public.r2_put_capability_windows'));
  const outbox=s.findIndex(x=>x.includes('INSERT INTO public.message_r2_delete_outbox'));
  const deletion=s.findIndex(x=>x.includes('DELETE FROM public.e2ee_messages'));
  assert.ok(receipt>=0 && media>receipt && seal>media && outbox>seal && deletion>outbox);
  assert.match(s[0],/FOR UPDATE/);
  assert.match(s[2],/FOR UPDATE/);
  assert.match(s[deletion],/expires_at > clock_timestamp\(\)/);
});
test('rejects unauthorized recipient before receipt lookup',async()=>{
  const db=fakeDb();
  const r=await call(db,'OTHER1234567');
  assert.equal(r.statusCode,403);
  assert.equal(db.steps.length,1);
});
test('repeat ACK uses immutable terminal receipt after payload removal',async()=>{
  const db=fakeDb({object:{...obj,state:'DELETE_QUEUED'},message:null,receipt:{
    recipient_identity:receiver,state:'DELIVERED'
  }});
  const r=await call(db);
  assert.deepEqual(r,{
    success:true,acknowledged:true,duplicate:true,state:'DELIVERED',statusCode:200
  });
  assert.equal(db.steps.some(x=>x.sql.includes('INSERT INTO public.message_r2_delete_outbox')),false);
});
test('expired or cancelled receipts cannot be reacknowledged',async()=>{
  for(const state of ['EXPIRED','CANCELLED']){
    const db=fakeDb({receipt:{recipient_identity:receiver,state}});
    const r=await call(db);
    assert.equal(r.statusCode,410);
    assert.equal(r.code,state);
  }
});
test('not attached or missing media cannot confirm message delivery',async()=>{
  for(const options of [{object:null},{object:{...obj,state:'UPLOADED'}}]){
    const db=fakeDb(options);const r=await call(db);
    assert.equal(r.success,false);
    assert.equal(db.steps.some(x=>x.sql.includes('INSERT INTO public.message_r2_delete_outbox')),false);
  }
});
test('missing or cancelled message does not queue remote deletion',async()=>{
  for(const message of [null,{...msg,is_cancelled:true},{...msg,delivery_state:'EXPIRED'}]){
    const db=fakeDb({message});const r=await call(db);
    assert.equal(r.success,false);
    assert.equal(db.steps.some(x=>x.sql.includes('INSERT INTO public.message_r2_delete_outbox')),false);
  }
});
test('expiry equality is terminal: late ACK cannot delete as delivered',async()=>{
  const db=fakeDb({now:msg.expires_at});
  const r=await call(db);
  assert.equal(r.code,'EXPIRED');
  assert.equal(db.steps.some(x=>x.sql.includes('INSERT INTO public.message_delivery_receipts')),false);
});
test('DB failure before terminalization propagates for transaction rollback',async()=>{
  for(const fail of ['receipt','seal','outbox']){
    const db=fakeDb({fail});
    await assert.rejects(call(db),/DB_/);
  }
});
test('final deletion deadline conflict throws and rolls back all side effects',async()=>{
  const db=fakeDb({deleteRows:0});
  await assert.rejects(call(db),/R2_DELIVERY_DEADLINE_CONFLICT/);
});
test('invalid R2 object key blocks deletion even when stored in registry',async()=>{
  const db=fakeDb({object:{...obj,object_key:'../private'}});
  await assert.rejects(call(db),/Invalid R2 object key/);
  assert.equal(db.steps.some(x=>x.sql.includes('INSERT INTO public.message_delivery_receipts')),false);
});

test('unknown legacy PUT window denies ACK before delete outbox insertion',async()=>{
  const db=fakeDb({missingWindow:true});
  await assert.rejects(call(db),/R2_PUT_WINDOW_UNKNOWN/);
  assert.equal(db.steps.some(x=>x.sql.includes('INSERT INTO public.message_r2_delete_outbox')),false);
});
