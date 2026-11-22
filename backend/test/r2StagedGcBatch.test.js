'use strict';
const test=require('node:test');
const assert=require('node:assert/strict');
const {processStagedR2GcBatch}=require('../src/r2StagedGcBatch');
const objectKey='pending/12/34/12345678-1234-4234-8234-123456789abc';
const messageId='msg-1';
const token='12345678-1234-4234-8234-123456789abc';
function database(opts={}){
  const statements=[];
  const tx=async(sql,args=[])=>{
    statements.push({sql,args});
    if(sql.includes('FROM public.message_r2_objects')){
      if(sql.includes('FOR UPDATE OF o SKIP LOCKED')){
        const rows=opts.empty?[]:[{object_key:objectKey,message_id:messageId}];
        return {rowCount:rows.length,rows};
      }
      return {rowCount:1,rows:[{state:'DELETE_QUEUED'}]};
    }
    if(sql.includes('FROM public.message_r2_delete_outbox'))
      return {rowCount:1,rows:[{state:'PENDING'}]};
    if(sql.includes('FROM public.message_delivery_receipts'))
      return {rowCount:1,rows:[{state:'DELIVERED'}]};
    if(sql.includes('FROM public.r2_put_capability_windows'))
      return {rowCount:1,rows:[{object_key:objectKey}]};
    if(sql.includes('INSERT INTO public.r2_delete_claims'))
      return {rowCount:opts.claimBusy?0:1,rows:opts.claimBusy?[]:[{
        generation:1,lease_until:'2099-11-21T12:00:00Z',attempts:1}]};
    if(sql.includes('FROM public.r2_delete_claims'))
      return {rowCount:1,rows:[{
        claim_token:token,generation:1,attempts:1,
        lease_until:'2099-11-21T12:00:00Z',completed_at:null}]};
    if(sql.includes('SELECT clock_timestamp() AS now'))
      return {rowCount:1,rows:[{now:'2026-11-21T12:00:00Z'}]};
    if(sql.includes('UPDATE public.message_r2_delete_outbox')){
      if(opts.finalizeDown&&sql.includes("state='DONE'"))throw Error('PG_FINALIZE_FAILED');
      return {rowCount:1,rows:[{next_attempt_at:'2099-11-21T12:00:00Z'}]};
    }
    if(sql.includes('UPDATE public.message_r2_objects')||
       sql.includes('UPDATE public.r2_delete_claims'))
      return {rowCount:1,rows:[{object_key:objectKey}]};
    throw Error('Unexpected SQL '+sql);
  };
  return {statements,withTransaction:async fn=>fn(tx)};
}
function call(db,deleteObject,extra={}){
  return processStagedR2GcBatch({
    withTransaction:db.withTransaction,deleteObject,limit:1,
    deleteTimeoutMs:100,makeClaimToken:()=>token,
    makeJitterSeconds:()=>0,...extra
  });
}
test('staged batch is inert until explicit invocation and empty batch does nothing',async()=>{
  const db=database({empty:true});
  let calls=0;
  const out=await call(db,async()=>{calls++;return {deleted:true,objectKey};});
  assert.deepEqual(out,[]);
  assert.equal(calls,0);
});
test('successful trusted deletion only happens after committed claim and finalizes DB',async()=>{
  const db=database();
  let called=0;
  const out=await call(db,async({objectKey:key,signal})=>{
    called++;
    assert.equal(key,objectKey);
    assert.equal(signal.aborted,false);
    assert.ok(db.statements.some(s=>s.sql.includes('INSERT INTO public.r2_delete_claims')));
    return {deleted:true,objectKey:key};
  });
  assert.equal(called,1);
  assert.deepEqual(out,[{objectKey,state:'FINALIZED'}]);
  assert.ok(db.statements.some(s=>s.sql.includes("state='DELETED'")));
  assert.ok(db.statements.some(s=>s.sql.includes("state='DONE'")));
});
test('busy claim never reaches storage delete',async()=>{
  const db=database({claimBusy:true});let count=0;
  const out=await call(db,async()=>{count++;return {deleted:true,objectKey};});
  assert.equal(count,0);assert.equal(out[0].state,'NOT_CLAIMED');
});
test('storage error schedules persisted retry, without finalizing success',async()=>{
  const db=database();
  const out=await call(db,async()=>{throw Error('NETWORK_DOWN');});
  assert.equal(out[0].state,'RETRY_SCHEDULED');
  assert.ok(db.statements.some(s=>s.sql.includes("state='RETRY'")));
  assert.equal(db.statements.some(s=>s.sql.includes("state='DONE'")),false);
});
test('unverified storage response schedules retry, never marks DELETED',async()=>{
  const db=database(),out=await call(db,async()=>({deleted:true,objectKey:'other'}));
  assert.equal(out[0].state,'RETRY_SCHEDULED');
  assert.equal(db.statements.some(s=>s.sql.includes("state='DELETED'")),false);
});
test('storage success followed by PostgreSQL failure bubbles up for idempotent recovery',async()=>{
  const db=database({finalizeDown:true});
  await assert.rejects(call(db,async()=>({deleted:true,objectKey})),/PG_FINALIZE_FAILED/);
  assert.equal(db.statements.some(s=>s.sql.includes("state='RETRY'")),false);
});
test('timeout aborts injected storage operation and schedules retry',async()=>{
  const db=database();let aborted=false;
  const out=await call(db,({signal})=>new Promise((_,reject)=>{
    signal.addEventListener('abort',()=>{
      aborted=true;reject(Error('ABORTED'));
    },{once:true});
  }));
  assert.equal(aborted,true);
  assert.equal(out[0].state,'RETRY_SCHEDULED');
  assert.equal(out[0].code,'R2_DELETE_TIMEOUT');
});
test('limit, timeout and trusted callbacks are bounded before database use',async()=>{
  const db=database();
  await assert.rejects(call(db,async()=>({deleted:true,objectKey}),{limit:9}),/bounded GC/);
  await assert.rejects(call(db,async()=>({deleted:true,objectKey}),{deleteTimeoutMs:31000}),/bounded GC/);
  await assert.rejects(call(db,null),/bounded GC/);
  assert.equal(db.statements.length,0);
});
