'use strict';
const test=require('node:test');
const assert=require('node:assert/strict');
const {claimQueuedR2Delete,recordDeleteClaimCompleted,LEASE_SECONDS,MAX_ATTEMPTS}=
 require('../src/r2DeleteClaimLedger');
const key='pending/12/34/12345678-1234-4234-8234-123456789abc';
const message='message-1';
const token='12345678-1234-4234-8234-123456789abc';
function database(options={}){
 const sql=[];
 const tx=async(q,args)=>{
   sql.push({q,args});
   if(options.down)throw Error('PG_DOWN');
   if(q.includes('FROM public.message_r2_objects'))
     return {rowCount:options.noMedia?0:1,
       rows:options.noMedia?[]:[{state:options.attached?'ATTACHED':'DELETE_QUEUED'}]};
   if(q.includes('FROM public.message_r2_delete_outbox'))
     return {rowCount:options.noOutbox?0:1,rows:options.noOutbox?[]:[{object_key:key}]};
   if(q.includes('FROM public.message_delivery_receipts'))
     return {rowCount:options.noReceipt?0:1,
       rows:options.noReceipt?[]:[{state:options.receipt||'DELIVERED'}]};
   if(q.includes('FROM public.r2_put_capability_windows'))
     return {rowCount:options.putBlocked?0:1,
       rows:options.putBlocked?[]:[{object_key:key}]};
   if(q.includes('INSERT INTO public.r2_delete_claims'))
     return {rowCount:options.claimBusy?0:1,rows:options.claimBusy?[]:[{
       generation:options.generation||1,lease_until:'2026-11-21T12:05:00Z',
       attempts:options.attempts||1
     }]};
   if(q.includes('UPDATE public.r2_delete_claims'))
     return {rowCount:options.stale?0:1,rows:options.stale?[]:[{object_key:key}]};
   throw Error('Unexpected SQL: '+q);
 };
 return {sql,withTransaction:async fn=>fn(tx)};
}
const claim=(d,extra={})=>claimQueuedR2Delete({
 withTransaction:d.withTransaction,objectKey:key,messageId:message,claimToken:token,...extra
});
test('eligible terminal message atomically claims a bounded GC lease',async()=>{
 const db=database(),out=await claim(db);
 assert.equal(out.claimed,true);
 assert.equal(out.generation,1);
 assert.equal(out.attempts,1);
 assert.equal(db.sql.length,5);
 assert.match(db.sql[0].q,/FOR UPDATE/);
 assert.match(db.sql[1].q,/FOR UPDATE/);
 assert.match(db.sql[3].q,/FOR UPDATE/);
 assert.match(db.sql[4].q,/ON CONFLICT \(object_key\) DO UPDATE/);
 assert.deepEqual(db.sql[4].args,[key,message,token,LEASE_SECONDS,MAX_ATTEMPTS]);
});
test('missing media and nonterminal ATTACHED abort before GC claim',async()=>{
 for(const config of [{noMedia:true},{attached:true}]){
   const db=database(config),out=await claim(db);
   assert.equal(out.claimed,false);assert.equal(db.sql.length,1);
 }
});
test('outbox must be present and bound to exact message',async()=>{
 const db=database({noOutbox:true}),out=await claim(db);
 assert.equal(out.code,'R2_GC_OUTBOX_MISSING');assert.equal(db.sql.length,2);
});
test('CANCELLED and absent terminal receipts are rejected',async()=>{
 for(const config of [{receipt:'CANCELLED'},{noReceipt:true}]){
   const db=database(config),out=await claim(db);
   assert.equal(out.code,'R2_GC_RECEIPT_INVALID');assert.equal(db.sql.length,3);
 }
});
test('unsealed or live PUT capability denies lease and never emits claim SQL',async()=>{
 const db=database({putBlocked:true}),out=await claim(db);
 assert.equal(out.code,'R2_PUT_DELETE_BLOCKED');assert.equal(db.sql.length,4);
});
test('active leases and exhausted retry limits fail closed',async()=>{
 const db=database({claimBusy:true}),out=await claim(db);
 assert.equal(out.code,'R2_GC_ALREADY_CLAIMED_OR_RETRY_EXHAUSTED');
 assert.match(db.sql[4].q,/lease_until < clock_timestamp/);
 assert.match(db.sql[4].q,/attempts < /);
});
test('expired lease is fenced by incrementing generation',async()=>{
 const db=database({generation:2,attempts:2}),out=await claim(db);
 assert.equal(out.claimed,true);assert.equal(out.generation,2);
 assert.equal(out.attempts,2);
 assert.match(db.sql[4].q,/generation=public.r2_delete_claims.generation\+1/);
});
test('completion records only exact live generation and trusted result',async()=>{
 const db=database(),result=await recordDeleteClaimCompleted({
   withTransaction:db.withTransaction,objectKey:key,messageId:message,
   claimToken:token,generation:1,trustedStorageDeleteCompleted:true
 });
 assert.equal(result.recorded,true);
 assert.match(db.sql[0].q,/claim_token=\$3::uuid AND generation=\$4::bigint/);
 const stale=await recordDeleteClaimCompleted({
   withTransaction:database({stale:true}).withTransaction,
   objectKey:key,messageId:message,claimToken:token,
   generation:1,trustedStorageDeleteCompleted:true
 });
 assert.equal(stale.recorded,false);
});
test('untrusted client cannot record deletion success or fake token',async()=>{
 await assert.rejects(recordDeleteClaimCompleted({
   withTransaction:database().withTransaction,objectKey:key,messageId:message,
   claimToken:token,generation:1,trustedStorageDeleteCompleted:false
 }),/Trusted successful/);
 await assert.rejects(claim(database(),{claimToken:'not-uuid'}),/Invalid trusted/);
});
test('DB errors bubble up without issuing physical storage operation',async()=>{
 await assert.rejects(claim(database({down:true})),/PG_DOWN/);
});
