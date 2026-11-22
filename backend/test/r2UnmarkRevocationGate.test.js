'use strict';
const test=require('node:test');
const assert=require('node:assert/strict');
const {
  r2RevocationBridgeEnabled,assertR2RevocationSchemaInTransaction,
  recordGuardedUnmarkRevocation
}=require('../src/r2UnmarkRevocationGate');
const identityA='AABBCCDDEEFF0011',identityB='1122334455667788';

function fakeTx({present=true,inserted=true,notify=true}={}){
  const statements=[];
  const txQuery=async(sql,args=[])=>{
    statements.push({sql,args});
    if(sql.includes('to_regclass('))
      return {rowCount:1,rows:[{present}]};
    if(sql.includes('INSERT INTO public.r2_stream_revocation_events'))
      return {rowCount:inserted?1:0,rows:[]};
    if(sql.includes('SELECT pg_notify(')){
      if(!notify)throw Error('PG_NOTIFY_FAILURE');
      return {rowCount:1,rows:[]};
    }
    throw Error('Unexpected SQL in R2 unmark gate: '+sql);
  };
  return {txQuery,statements};
}
test('both rollout flags mandatory, enabled only on exact true',()=>{
  const f=r2RevocationBridgeEnabled;
  assert.equal(f({}),false);
  assert.equal(f({PESSOASAQUI_R2_REVOCATION_TX_ENABLED:'true'}),false);
  assert.equal(f({PESSOASAQUI_R2_STAGE_ROLLOUT_APPROVED:'true'}),false);
  assert.equal(f({PESSOASAQUI_R2_REVOCATION_TX_ENABLED:'TRUE',
    PESSOASAQUI_R2_STAGE_ROLLOUT_APPROVED:'true'}),false);
  assert.equal(f({PESSOASAQUI_R2_REVOCATION_TX_ENABLED:'true',
    PESSOASAQUI_R2_STAGE_ROLLOUT_APPROVED:'true'}),true);
});
test('feature disabled never probes missing production migration 012',async()=>{
  const tx=fakeTx({present:false});
  const out=await recordGuardedUnmarkRevocation({
    txQuery:tx.txQuery,identityA,identityB,
    deletedConnectionRows:2,cancelledMessageRows:1,enabled:false
  });
  assert.equal(out.recorded,false);
  assert.equal(out.reason,'DISABLED');
  assert.equal(tx.statements.length,0);
});
test('missing migration 012 fails before writing any event',async()=>{
  const tx=fakeTx({present:false});
  await assert.rejects(recordGuardedUnmarkRevocation({
    txQuery:tx.txQuery,identityA,identityB,
    deletedConnectionRows:1,cancelledMessageRows:0,enabled:true
  }),/R2_REVOCATION_SCHEMA_NOT_READY/);
  assert.equal(tx.statements.length,1);
  assert.match(tx.statements[0].sql,/to_regclass/);
});
test('schema ready: events inserted only after trusted mutation count',async()=>{
  const tx=fakeTx();
  assert.equal(await assertR2RevocationSchemaInTransaction({txQuery:tx.txQuery}),true);
  const out=await recordGuardedUnmarkRevocation({
    txQuery:tx.txQuery,identityA,identityB,
    deletedConnectionRows:2,cancelledMessageRows:3,enabled:true
  });
  assert.equal(out.recorded,true);
  assert.equal(tx.statements.length,4);
  assert.match(tx.statements[2].sql,/INSERT INTO public.r2_stream_revocation_events/);
  assert.match(tx.statements[3].sql,/pg_notify/);
});
test('no actual mutation inserts no event even with feature enabled',async()=>{
  const tx=fakeTx();
  const out=await recordGuardedUnmarkRevocation({
    txQuery:tx.txQuery,identityA,identityB,
    deletedConnectionRows:0,cancelledMessageRows:0,enabled:true
  });
  assert.equal(out.recorded,false);
  assert.equal(tx.statements.length,1);
});
test('DB write failure must reject to let route rollback',async()=>{
  const tx=fakeTx({inserted:false});
  await assert.rejects(recordGuardedUnmarkRevocation({
    txQuery:tx.txQuery,identityA,identityB,
    deletedConnectionRows:1,cancelledMessageRows:0,enabled:true
  }),/R2_REVOCATION_EVENT_WRITE_FAILED/);
});
test('pg_notify failure must reject to let route rollback',async()=>{
  const tx=fakeTx({notify:false});
  await assert.rejects(recordGuardedUnmarkRevocation({
    txQuery:tx.txQuery,identityA,identityB,
    deletedConnectionRows:1,cancelledMessageRows:0,enabled:true
  }),/PG_NOTIFY_FAILURE/);
});
test('bad mutation counts fail closed without inserting event',async()=>{
  const tx=fakeTx();
  await assert.rejects(recordGuardedUnmarkRevocation({
    txQuery:tx.txQuery,identityA,identityB,
    deletedConnectionRows:undefined,cancelledMessageRows:0,enabled:true
  }),/Authoritative PostgreSQL mutation counts/);
  assert.equal(tx.statements.length,1);
});
