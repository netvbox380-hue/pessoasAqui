'use strict';
const test=require('node:test');
const assert=require('node:assert/strict');
const {allocateImmutableR2Attempt,MAX_ATTEMPTS}=
  require('../src/r2ImmutableAttemptAllocator');

const senderIdentity='AABBCCDDEEFF0011';
const recipientIdentity='1122334455667788';
const messageId='msg-30x-attempt';
const oldKey='pending/aa/bb/aabbccdd-aabb-4abb-8abb-aabbccddaabb';
const uuids=[
  '12345678-1234-4234-8234-123456789abc',
  '87654321-4321-4321-8321-cba987654321'
];
function db({exists=true,owner=true,lease=true,reserved=true,
  next=1,fail=false,wrongInsert=false,legacyKey=false}={}){
  const sql=[];
  let commits=0,rollbacks=0;
  const tx=async(statement,args=[])=>{
    sql.push({statement,args});
    if(fail)throw Error('POSTGRES_DOWN');
    if(statement.includes('FROM public.message_r2_objects'))
      return {rowCount:exists?1:0,rows:exists?[{
        object_key:legacyKey
          ?'pending/12/34/'+uuids[0]:oldKey,
        message_id:messageId,sender_identity:owner?senderIdentity:'1111111111111111',
        recipient_identity:recipientIdentity,state:reserved?'RESERVED':'UPLOADED',
        upload_generation:1,lease_active:lease
      }]:[]};
    if(statement.includes('MAX(generation)'))
      return {rowCount:1,rows:[{next_generation:String(next)}]};
    if(statement.includes('INSERT INTO public.message_r2_upload_attempts'))
      return {rowCount:1,rows:[{
        object_key:wrongInsert?'wrong':args[0],message_id:messageId,
        generation:args[2],created_at:'2026-11-23T00:00:00Z'
      }]};
    throw Error('UNEXPECTED_SQL '+statement);
  };
  const withTransaction=async fn=>{
    try{const out=await fn(tx);commits++;return out;}
    catch(e){rollbacks++;throw e;}
  };
  return {sql,withTransaction,get commits(){return commits;},
    get rollbacks(){return rollbacks;}};
}
const alloc=(h,uuid=uuids[0],overrides={})=>
  allocateImmutableR2Attempt({
    withTransaction:h.withTransaction,messageId,senderIdentity,
    recipientIdentity,randomUUID:()=>uuid,...overrides
  });

test('first physical attempt gets a fresh server-generated key in one transaction',async()=>{
  const h=db();
  const out=await alloc(h);
  assert.equal(out.allocated,true);
  assert.equal(out.generation,1);
  assert.equal(out.objectKey,'pending/12/34/'+uuids[0]);
  assert.notEqual(out.objectKey,oldKey);
  assert.equal(h.commits,1);
  assert.equal(h.sql.length,3);
  assert.match(h.sql[0].statement,/FOR UPDATE/);
  assert.match(h.sql[1].statement,/MAX\(generation\)/);
  assert.match(h.sql[2].statement,/INSERT INTO public.message_r2_upload_attempts/);
  assert.deepEqual(h.sql[2].args,[out.objectKey,messageId,1,senderIdentity,recipientIdentity]);
  assert.equal(Object.isFrozen(out),true);
});
test('next attempt must be a distinct physical key and monotonic generation',async()=>{
  const h=db({next:2});
  const out=await alloc(h,uuids[1]);
  assert.equal(out.generation,2);
  assert.equal(out.objectKey,'pending/87/65/'+uuids[1]);
  assert.equal(h.sql[2].args[2],2);
});
test('invalid identity/message refused before any database query',async()=>{
  for(const changes of [
    {senderIdentity:'lowercase'},
    {recipientIdentity:senderIdentity},
    {messageId:''},
    {messageId:'bad\nmessage'}
  ]){
    const h=db();
    const out=await alloc(h,uuids[0],changes);
    assert.equal(out.allocated,false);
    assert.equal(out.statusCode,400);
    assert.equal(h.sql.length,0);
  }
});
test('missing, inactive, expired or foreign logical media cannot allocate a key',async()=>{
  for(const options of [{exists:false},{owner:false},
    {lease:false},{reserved:false}]){
    const h=db(options);
    const out=await alloc(h);
    assert.equal(out.allocated,false);
    assert.equal(h.sql.length,1);
  }
});
test('never reuses the legacy physical key in the message registry',async()=>{
  const h=db({legacyKey:true});
  const out=await alloc(h);
  assert.equal(out.allocated,false);
  assert.equal(out.code,'R2_ATTEMPT_KEY_REUSE');
  assert.equal(h.sql.length,1);
});
test('excess generation cannot create an extra attempt',async()=>{
  const h=db({next:MAX_ATTEMPTS+1});
  const out=await alloc(h);
  assert.equal(out.allocated,false);
  assert.equal(out.statusCode,429);
  assert.equal(h.sql.length,2);
});
test('database outage cannot allocate in RAM or claim success',async()=>{
  const h=db({fail:true});
  await assert.rejects(alloc(h),/POSTGRES_DOWN/);
  assert.equal(h.rollbacks,1);
});
test('unexpected inserted binding causes rollback',async()=>{
  const h=db({wrongInsert:true});
  await assert.rejects(alloc(h),/R2_ATTEMPT_ALLOCATION_MISMATCH/);
  assert.equal(h.rollbacks,1);
});
test('server controls the UUID; malformed UUID never starts SQL',async()=>{
  const h=db();
  await assert.rejects(alloc(h,'../../../unsafe'),/Invalid UUID v4/);
  assert.equal(h.sql.length,0);
});
