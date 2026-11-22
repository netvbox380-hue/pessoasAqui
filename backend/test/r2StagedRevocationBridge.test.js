'use strict';
const test=require('node:test');
const assert=require('node:assert/strict');
const {
  recordPairRevocationInExistingTransaction,
  recordMessageRevocationInExistingTransaction
}=require('../src/r2StagedRevocationBridge');
const identityA='AABBCCDDEEFF0011',identityB='1122334455667788';
function tx(){
  const executed=[];
  const txQuery=async(sql,args=[])=>{
    executed.push({sql,args});
    if(sql.includes('INSERT INTO public.r2_stream_revocation_events'))
      return {rowCount:1,rows:[{event_id:args[0]}]};
    if(sql.includes('SELECT pg_notify('))
      return {rowCount:1,rows:[{}]};
    throw Error('Unexpected SQL');
  };
  return {txQuery,executed};
}
test('PAIR event inserted and notified only after evidence of DB mutation',async()=>{
  const a=tx();
  const r=await recordPairRevocationInExistingTransaction({
    txQuery:a.txQuery,identityA,identityB,
    deletedConnectionRows:2,cancelledMessageRows:0
  });
  assert.equal(r.recorded,true);
  assert.equal(a.executed.length,2);
  assert.match(a.executed[0].sql,/INSERT INTO public.r2_stream_revocation_events/);
  assert.equal(a.executed[0].args[1],'PAIR');
  assert.equal(a.executed[0].args[3],identityA);
  assert.equal(a.executed[0].args[4],identityB);
  assert.match(a.executed[1].sql,/pg_notify/);
});
test('no actual pair mutation creates no event or notification',async()=>{
  const a=tx();
  const out=await recordPairRevocationInExistingTransaction({
    txQuery:a.txQuery,identityA,identityB,
    deletedConnectionRows:0,cancelledMessageRows:0
  });
  assert.equal(out.recorded,false);
  assert.equal(a.executed.length,0);
});
test('cancelled messages independently authorize pair hint',async()=>{
  const a=tx();
  const out=await recordPairRevocationInExistingTransaction({
    txQuery:a.txQuery,identityA,identityB,
    deletedConnectionRows:0,cancelledMessageRows:1
  });
  assert.equal(out.recorded,true);
});
test('invalid or untrusted pair identity/count fail closed',async()=>{
  const a=tx();
  for(const invalid of [
    {identityA:'aabbccddeeff0011'},
    {identityB:identityA},
    {deletedConnectionRows:-1},
    {cancelledMessageRows:1.5},
    {deletedConnectionRows:undefined}
  ]){
    await assert.rejects(recordPairRevocationInExistingTransaction({
      txQuery:a.txQuery,identityA,identityB,
      deletedConnectionRows:1,cancelledMessageRows:0,...invalid
    }),/Trusted|mutation/);
  }
  assert.equal(a.executed.length,0);
});
test('MESSAGE event inserted only for actual message cancellation',async()=>{
  const a=tx();
  const out=await recordMessageRevocationInExistingTransaction({
    txQuery:a.txQuery,messageId:'msg-123',updatedRows:1
  });
  assert.equal(out.recorded,true);
  assert.equal(a.executed[0].args[1],'MESSAGE');
  assert.equal(a.executed[0].args[2],'msg-123');
  assert.equal(a.executed[0].args[3],null);
  assert.equal(a.executed.length,2);
});
test('message without changed DB row never creates revocation',async()=>{
  const a=tx();
  const out=await recordMessageRevocationInExistingTransaction({
    txQuery:a.txQuery,messageId:'msg-123',updatedRows:0
  });
  assert.equal(out.recorded,false);
  assert.equal(a.executed.length,0);
});
test('invalid message payload or missing mutation count is refused',async()=>{
  const a=tx();
  for(const invalid of [{messageId:''},{messageId:'x\n'},{updatedRows:undefined},{updatedRows:-1}]){
    await assert.rejects(recordMessageRevocationInExistingTransaction({
      txQuery:a.txQuery,messageId:'msg-123',updatedRows:1,...invalid
    }),/Authoritative/);
  }
  assert.equal(a.executed.length,0);
});
test('failed durable event insert rejects, requiring rollback from caller',async()=>{
  const q=async(sql)=>{
    if(sql.includes('INSERT INTO'))return {rowCount:0,rows:[]};
    throw Error('SHOULD_NOT_NOTIFY');
  };
  await assert.rejects(recordPairRevocationInExistingTransaction({
    txQuery:q,identityA,identityB,deletedConnectionRows:1,
    cancelledMessageRows:0
  }),/R2_REVOCATION_EVENT_WRITE_FAILED/);
});
test('notify failure propagates to authoritative transaction for rollback',async()=>{
  const q=async(sql,args)=>{
    if(sql.includes('INSERT INTO'))return {rowCount:1,rows:[{event_id:args[0]}]};
    throw Error('NOTIFY_DB_FAILURE');
  };
  await assert.rejects(recordMessageRevocationInExistingTransaction({
    txQuery:q,messageId:'msg-123',updatedRows:1
  }),/NOTIFY_DB_FAILURE/);
});
