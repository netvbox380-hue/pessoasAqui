'use strict';
const test=require('node:test');
const assert=require('node:assert/strict');
const {admitQueuedR2Deletion}=require('../src/r2DeletionAdmission');

const objectKey='pending/12/34/12345678-1234-4234-8234-123456789abc';
const messageId='message-1';
function db(options={}){
  const calls=[];
  const tx=async(sql,args)=>{
    calls.push({sql,args});
    if(options.down)throw Error('DB_UNAVAILABLE');
    if(sql.includes('FROM public.message_r2_objects'))
      return options.missingMedia?{rowCount:0,rows:[]}:
        {rowCount:1,rows:[{object_key:objectKey,message_id:messageId,
          state:options.mediaState||'DELETE_QUEUED'}]};
    if(sql.includes('FROM public.message_r2_delete_outbox'))
      return options.missingJob?{rowCount:0,rows:[]}:
        {rowCount:1,rows:[{object_key:objectKey,message_id:messageId}]};
    if(sql.includes('FROM public.message_delivery_receipts'))
      return options.missingReceipt?{rowCount:0,rows:[]}:
        {rowCount:1,rows:[{state:options.receipt||'DELIVERED'}]};
    if(sql.includes('FROM public.r2_put_capability_windows'))
      return options.putEligible?{rowCount:1,rows:[{object_key:objectKey}]}:
        {rowCount:0,rows:[]};
    throw Error('Unexpected SQL: '+sql);
  };
  return {calls,withTransaction:async cb=>cb(tx)};
}
async function run(d){
  return admitQueuedR2Deletion({withTransaction:d.withTransaction,objectKey,messageId});
}
test('DELIVERED terminal, queued job and expired sealed PUT window allow staged admission',async()=>{
  const d=db({putEligible:true});
  const result=await run(d);
  assert.deepEqual(result,{allowed:true,code:'R2_QUEUED_DELETE_ELIGIBLE',objectKey,messageId});
  assert.equal(d.calls.length,4);
  assert.match(d.calls[0].sql,/FOR UPDATE/);
  assert.match(d.calls[1].sql,/FOR UPDATE/);
  assert.match(d.calls[3].sql,/sealed_at IS NOT NULL/);
  assert.match(d.calls[3].sql,/FOR UPDATE/);
});
test('EXPIRED receipt is eligible once PUT window expires',async()=>{
  const result=await run(db({putEligible:true,receipt:'EXPIRED'}));
  assert.equal(result.allowed,true);
});
test('ATTACHED or missing media never reaches outbox or PUT gate',async()=>{
  for(const cfg of [{mediaState:'ATTACHED'},{missingMedia:true}]){
    const d=db(cfg),result=await run(d);
    assert.equal(result.code,'R2_MEDIA_NOT_DELETE_QUEUED');
    assert.equal(d.calls.length,1);
  }
});
test('missing queued outbox row denies deletion',async()=>{
  const d=db({missingJob:true}),result=await run(d);
  assert.equal(result.code,'R2_DELETE_OUTBOX_MISSING');
  assert.equal(d.calls.length,2);
});
test('missing, unrecognized or nonterminal receipt denies deletion',async()=>{
  for(const cfg of [{missingReceipt:true},{receipt:'PENDING'},
    {receipt:'CANCELLED'},{receipt:'UNKNOWN'}]){
    const d=db({...cfg,putEligible:true}),result=await run(d);
    assert.equal(result.code,'R2_TERMINAL_RECEIPT_MISSING');
    assert.equal(d.calls.length,3);
  }
});
test('unknown, unsealed or not-yet-expired PUT window blocks deletion',async()=>{
  const d=db(),result=await run(d);
  assert.equal(result.allowed,false);
  assert.equal(result.code,'R2_PUT_DELETE_BLOCKED');
  assert.equal(d.calls.length,4);
});
test('database outage bubbles up with no fallback',async()=>{
  await assert.rejects(run(db({down:true})),/DB_UNAVAILABLE/);
});
test('invalid untrusted references and transaction are rejected',async()=>{
  await assert.rejects(admitQueuedR2Deletion({
    withTransaction:null,objectKey,messageId
  }),/PostgreSQL transaction/);
  await assert.rejects(admitQueuedR2Deletion({
    withTransaction:db().withTransaction,objectKey:'../invalid',messageId
  }),/Invalid R2 object key/);
  await assert.rejects(admitQueuedR2Deletion({
    withTransaction:db().withTransaction,objectKey,messageId:''
  }),/Valid R2 message/);
});
