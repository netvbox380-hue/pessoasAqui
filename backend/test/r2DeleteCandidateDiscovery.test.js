'use strict';
const test=require('node:test');
const assert=require('node:assert/strict');
const {discoverQueuedR2DeleteCandidates,MAX_BATCH}=
  require('../src/r2DeleteCandidateDiscovery');

const key='pending/12/34/12345678-1234-4234-8234-123456789abc';
function db(rows=[],opts={}){
  const statements=[];
  const withTransaction=async cb=>cb(async(sql,params)=>{
    statements.push({sql,params});
    if(opts.down)throw Error('PG_UNAVAILABLE');
    return {rowCount:opts.badCount?rows.length+1:rows.length,rows};
  });
  return {statements,withTransaction};
}
test('returns immutable candidate descriptors without deleting or issuing R2 I/O',async()=>{
  const d=db([{object_key:key,message_id:'msg1'}]);
  const result=await discoverQueuedR2DeleteCandidates({
    withTransaction:d.withTransaction
  });
  assert.deepEqual(result,[{objectKey:key,messageId:'msg1'}]);
  assert.equal(Object.isFrozen(result),true);
  assert.equal(Object.isFrozen(result[0]),true);
  assert.match(d.statements[0].sql,/FOR UPDATE OF o SKIP LOCKED/);
  assert.match(d.statements[0].sql,/DELETE_QUEUED/);
  assert.match(d.statements[0].sql,/EXISTS/);
  assert.deepEqual(d.statements[0].params,[16]);
});
test('empty queue causes single bounded SELECT and zero candidates',async()=>{
  const d=db([]);
  const out=await discoverQueuedR2DeleteCandidates({
    withTransaction:d.withTransaction,limit:8
  });
  assert.deepEqual(out,[]);
  assert.equal(d.statements.length,1);
});
test('rejects batches larger than limit or contract mismatch',async()=>{
  const rows=[{object_key:key,message_id:'a'},
    {object_key:key,message_id:'b'}];
  await assert.rejects(discoverQueuedR2DeleteCandidates({
    withTransaction:db(rows).withTransaction,limit:1
  }),/INVALID_CANDIDATE_RESULT/);
  await assert.rejects(discoverQueuedR2DeleteCandidates({
    withTransaction:db([], {badCount:true}).withTransaction
  }),/INVALID_CANDIDATE_RESULT/);
});
test('rejects invalid and duplicate object keys or message IDs',async()=>{
  for(const rows of [
    [{object_key:'unsafe',message_id:'m'}],
    [{object_key:key,message_id:''}],
    [{object_key:key,message_id:'a'},{object_key:key,message_id:'b'}]
  ]){
    await assert.rejects(discoverQueuedR2DeleteCandidates({
      withTransaction:db(rows).withTransaction
    }));
  }
});
test('validates strict batch limit before PostgreSQL usage',async()=>{
  for(const limit of [0,MAX_BATCH+1,1.5,-1,NaN]){
    await assert.rejects(discoverQueuedR2DeleteCandidates({
      withTransaction:db().withTransaction,limit
    }),/bounded batch size/);
  }
  await assert.rejects(discoverQueuedR2DeleteCandidates({
    withTransaction:null,limit:1
  }),/Trusted DB/);
});
test('database failure propagates without fallback',async()=>{
  await assert.rejects(discoverQueuedR2DeleteCandidates({
    withTransaction:db([],{down:true}).withTransaction
  }),/PG_UNAVAILABLE/);
});
