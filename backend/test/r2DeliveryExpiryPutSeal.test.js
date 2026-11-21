'use strict';
const test=require('node:test');
const assert=require('node:assert/strict');
const {expireR2Message}=require('../src/r2DeliveryFinalizer');
const key='pending/12/34/12345678-1234-4234-8234-123456789abc';
const msg='m-expired',sender='AAAABBBBCCCCDDDD',recipient='1111222233334444';
const noticeId='12345678-1234-4234-8234-123456789abc';
function database({unknownWindow=false,sealFails=false,notExpired=false}={}){
  const steps=[];
  const withTransaction=async fn=>fn(async(sql,args)=>{
    steps.push({sql,args});
    if(sql.includes('FROM public.message_r2_objects'))
      return {rowCount:1,rows:[{object_key:key,message_id:msg,
        sender_identity:sender,recipient_identity:recipient,
        state:'ATTACHED',content_sha256:'f'.repeat(64),content_bytes:8}]};
    if(sql.includes('FROM public.message_delivery_receipts'))
      return {rowCount:0,rows:[]};
    if(sql.includes('FROM public.e2ee_messages'))
      return {rowCount:1,rows:[{id:msg,sender_identity:sender,
        recipient_identity:recipient,ciphertext_payload:'ENC_ECDH:test',
        iv_nonce:'iv',expires_at:notExpired?'2026-11-23T00:00:00Z':'2026-11-19T00:00:00Z',
        delivery_state:'PENDING',is_cancelled:false,is_delivered:false}]};
    if(sql.includes('clock_timestamp() AS now'))
      return {rowCount:1,rows:[{now:'2026-11-21T00:00:00Z'}]};
    if(sql.includes('UPDATE public.r2_put_capability_windows')){
      if(sealFails)throw Error('PUT_SEAL_DB_DOWN');
      return {rowCount:unknownWindow?0:1,rows:unknownWindow?[]:[{
        sealed_at:'2026-11-21T00:00:00Z',expires_at:'2026-11-21T00:00:00Z'
      }]};
    }
    if(sql.includes('UPDATE public.message_r2_objects'))
      return {rowCount:1};
    if(sql.includes('DELETE FROM public.e2ee_messages'))
      return {rowCount:1};
    if(sql.includes('INSERT INTO public.message_delivery_receipts')||
       sql.includes('INSERT INTO public.message_expiration_notices')||
       sql.includes('INSERT INTO public.message_r2_delete_outbox'))
      return {rowCount:1};
    throw Error('UNEXPECTED_SQL');
  });
  return {steps,withTransaction};
}
const run=d=>expireR2Message({withTransaction:d.withTransaction,messageId:msg,noticeId});
test('expiration seals PUT window after terminal media transition but before outbox',async()=>{
  const d=database(),out=await run(d);
  assert.equal(out.success,true);
  const sql=d.steps.map(x=>x.sql);
  const media=sql.findIndex(x=>x.includes('UPDATE public.message_r2_objects'));
  const seal=sql.findIndex(x=>x.includes('UPDATE public.r2_put_capability_windows'));
  const outbox=sql.findIndex(x=>x.includes('INSERT INTO public.message_r2_delete_outbox'));
  assert.ok(media>=0&&seal>media&&outbox>seal);
});
test('unknown legacy window fails before expiration delete outbox',async()=>{
  const d=database({unknownWindow:true});
  await assert.rejects(run(d),/R2_PUT_WINDOW_UNKNOWN/);
  assert.equal(d.steps.some(s=>s.sql.includes('INSERT INTO public.message_r2_delete_outbox')),false);
});
test('database seal failure aborts expiration and blocks outbox insertion',async()=>{
  const d=database({sealFails:true});
  await assert.rejects(run(d),/PUT_SEAL_DB_DOWN/);
  assert.equal(d.steps.some(s=>s.sql.includes('INSERT INTO public.message_r2_delete_outbox')),false);
});
test('message not yet expired never seals object or inserts outbox',async()=>{
  const d=database({notExpired:true}),out=await run(d);
  assert.equal(out.code,'NOT_EXPIRED');
  assert.equal(d.steps.some(s=>s.sql.includes('UPDATE public.r2_put_capability_windows')),false);
});
