'use strict';
const test=require('node:test');
const assert=require('node:assert/strict');
const {finalizeTrustedR2Delete,scheduleFailedR2Delete}=
  require('../src/r2DeleteOutcomeCoordinator');
const key='pending/12/34/12345678-1234-4234-8234-123456789abc';
const messageId='msg-1',claimToken='12345678-1234-4234-8234-123456789abc';
function db(opts={}){
  const statements=[];
  const tx=async(sql,args=[])=>{
    statements.push({sql,args});
    if(opts.down)throw Error('PG_DOWN');
    if(sql.includes('FROM public.message_r2_objects'))
      return {rowCount:1,rows:[{state:opts.attached?'ATTACHED':'DELETE_QUEUED'}]};
    if(sql.includes('FROM public.message_r2_delete_outbox'))
      return {rowCount:1,rows:[{state:opts.jobState||'PENDING'}]};
    if(sql.includes('FROM public.message_delivery_receipts'))
      return {rowCount:opts.noReceipt?0:1,
        rows:opts.noReceipt?[]:[{state:opts.receipt||'DELIVERED'}]};
    if(sql.includes('FROM public.r2_put_capability_windows'))
      return {rowCount:opts.livePut?0:1,
        rows:opts.livePut?[]:[{object_key:key}]};
    if(sql.includes('FROM public.r2_delete_claims')&&
       sql.includes('FOR UPDATE'))
      return {rowCount:1,rows:[{claim_token:opts.stale?cryptoRandom:claimToken,
        generation:opts.generation||1,attempts:opts.attempts||1,
        completed_at:opts.completed?'2026-11-21T11:00:00Z':null,
        lease_until:opts.leaseExpired?'2026-11-21T11:00:00Z':'2026-11-21T13:00:00Z'}]};
    if(sql.includes('SELECT clock_timestamp() AS now'))
      return {rowCount:1,rows:[{now:'2026-11-21T12:00:00Z'}]};
    if(sql.includes('UPDATE public.message_r2_delete_outbox'))
      return {rowCount:opts.conflict==='outbox'?0:1,
        rows:[{object_key:key,next_attempt_at:'2026-11-21T13:03:00Z'}]};
    if(sql.includes('UPDATE public.message_r2_objects'))
      return {rowCount:opts.conflict==='media'?0:1,rows:[{object_key:key}]};
    if(sql.includes('UPDATE public.r2_delete_claims'))
      return {rowCount:opts.conflict==='claim'?0:1,rows:[{object_key:key}]};
    throw Error('Unexpected SQL: '+sql);
  };
  return {statements,withTransaction:async fn=>fn(tx)};
}
const cryptoRandom='99999999-9999-4999-8999-999999999999';
const opts={objectKey:key,messageId,claimToken,generation:1};
test('success atomically marks outbox DONE then media DELETED then claim completed',async()=>{
  const d=db();
  const r=await finalizeTrustedR2Delete({...opts,
    withTransaction:d.withTransaction,trustedStorageDeleteCompleted:true});
  assert.deepEqual(r,{committed:true,objectKey:key,messageId,state:'DELETED'});
  const statements=d.statements.map(s=>s.sql);
  const outbox=statements.findIndex(s=>s.includes('UPDATE public.message_r2_delete_outbox'));
  const media=statements.findIndex(s=>s.includes('UPDATE public.message_r2_objects'));
  const claim=statements.findIndex(s=>s.includes('UPDATE public.r2_delete_claims'));
  assert.ok(outbox>0&&media>outbox&&claim>media);
  assert.match(statements[media],/state='DELETED'/);
  assert.match(statements[claim],/claim_token=\$3::uuid AND generation=\$4::bigint/);
});
test('missing trustworthy R2 success rejects before SQL',async()=>{
  const d=db();
  await assert.rejects(finalizeTrustedR2Delete({...opts,
    withTransaction:d.withTransaction,trustedStorageDeleteCompleted:false}),
    /Trusted storage/);
  assert.equal(d.statements.length,0);
});
test('old tokens, generation, completed or expired lease deny finalization',async()=>{
  for(const opt of [{stale:true},{generation:2},{completed:true},{leaseExpired:true}]){
    const d=db(opt);
    const out=await finalizeTrustedR2Delete({...opts,
      withTransaction:d.withTransaction,trustedStorageDeleteCompleted:true});
    assert.equal(out.committed,false);
    assert.equal(d.statements.some(s=>s.sql.includes('UPDATE public.message_r2_delete_outbox')),false);
  }
});
test('nonterminal objects, missing receipts and live PUT windows deny finalization',async()=>{
  for(const opt of [{attached:true},{noReceipt:true},{receipt:'CANCELLED'},{livePut:true}]){
    const d=db(opt);
    const out=await finalizeTrustedR2Delete({...opts,
      withTransaction:d.withTransaction,trustedStorageDeleteCompleted:true});
    assert.equal(out.committed,false);
    assert.equal(d.statements.some(s=>s.sql.includes('UPDATE public.message_r2_delete_outbox')),false);
  }
});
test('any DB conflict throws so real transaction wrapper must roll back',async()=>{
  for(const conflict of ['outbox','media','claim']){
    const d=db({conflict});
    await assert.rejects(finalizeTrustedR2Delete({...opts,
      withTransaction:d.withTransaction,trustedStorageDeleteCompleted:true}),/CONFLICT/);
  }
});
test('trusted failed DELETE schedules durable retry after the lease expiry',async()=>{
  const d=db({attempts:3});
  const out=await scheduleFailedR2Delete({...opts,withTransaction:d.withTransaction,
    errorCode:'R2_DELETE_TIMEOUT',jitterSeconds:12});
  assert.equal(out.committed,true);
  assert.equal(out.delaySeconds,132);
  const sql=d.statements.find(s=>s.sql.includes('UPDATE public.message_r2_delete_outbox'));
  assert.match(sql.sql,/GREATEST/);
  assert.match(sql.sql,/next_attempt_at/);
  assert.deepEqual(sql.args,[key,messageId,132,'R2_DELETE_TIMEOUT']);
});
test('retry denies stale claim and malformed failure metadata',async()=>{
  const d=db({stale:true});
  const out=await scheduleFailedR2Delete({...opts,withTransaction:d.withTransaction});
  assert.equal(out.committed,false);
  assert.equal(d.statements.some(s=>s.sql.includes('UPDATE public.message_r2_delete_outbox')),false);
  await assert.rejects(scheduleFailedR2Delete({...opts,
    withTransaction:db().withTransaction,errorCode:'secret:123'}),/Invalid trusted/);
});
test('PostgreSQL failure propagates and does not call any R2 SDK',async()=>{
  await assert.rejects(finalizeTrustedR2Delete({...opts,
    withTransaction:db({down:true}).withTransaction,trustedStorageDeleteCompleted:true
  }),/PG_DOWN/);
});
