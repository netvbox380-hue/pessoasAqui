'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const {revalidateR2ReadAtStreamStart} = require('../src/r2StreamStartGate');

const sender='AAAABBBBCCCCDDDD', recipient='1111222233334444';
const messageId='msg-stream-start-1';
const objectKey='pending/12/34/12345678-1234-4234-8234-123456789abc';
const digest='a'.repeat(64);
const admission={authorized:true,admitted:true,value:{
  allowed:true,authenticatedIdentity:recipient,peerIdentity:sender,
  messageId,objectKey,expectedSha256:digest,expectedBytes:1234
}};
function fakeDb(options={}){
  const calls=[];
  const query=async(sql,args)=>{
    calls.push({sql,args});
    if(options.down)throw Error('POSTGRES_DOWN');
    if(sql.includes('FROM public.connections')){
      const rows=[
        {from_identity:sender,to_identity:recipient,is_mutual:true},
        {from_identity:recipient,to_identity:sender,is_mutual:true}
      ];
      if(options.revoked)rows[1].is_mutual=false;
      return {rowCount:options.unilateral?1:2,rows:options.unilateral?rows.slice(0,1):rows};
    }
    if(sql.includes('FROM public.message_r2_objects')){
      if(options.wrongObject)return {rowCount:0,rows:[]};
      return {rowCount:1,rows:[{
        state:options.state||'ATTACHED',content_sha256:options.digest||digest,
        content_bytes:options.bytes??1234,is_cancelled:options.cancelled||false,
        delivery_state:'PENDING',is_active:!options.expired
      }]};
    }
    if(sql.includes('FROM public.message_delivery_receipts')){
      return {rowCount:options.terminal?1:0,rows:options.terminal?[{state:'DELIVERED'}]:[]};
    }
    throw Error('UNEXPECTED_SQL');
  };
  return {calls,withTransaction:async cb=>cb(query)};
}
const run=(db,prior=admission)=>revalidateR2ReadAtStreamStart({
  withTransaction:db.withTransaction,admission:prior
});

test('valid prior admission and current bilateral object authorization permit preflight',async()=>{
  const db=fakeDb(),result=await run(db);
  assert.equal(result.allowed,true);
  assert.equal(result.expectedBytes,1234);
  assert.equal('url' in result,false);
  assert.equal(db.calls.length,3);
  assert.match(db.calls[0].sql,/FOR SHARE/);
  assert.match(db.calls[1].sql,/FOR SHARE OF o,m/);
  assert.deepEqual(db.calls[0].args,[sender,recipient]);
});
test('no signed prior admission means no SQL and no network',async()=>{
  for(const prior of [null,{}, {...admission,authorized:false},
    {...admission,admitted:false}]){
    const db=fakeDb(),res=await run(db,prior);
    assert.equal(res.allowed,false);
    assert.equal(res.code,'R2_PRIOR_ADMISSION_REQUIRED');
    assert.equal(db.calls.length,0);
  }
});
test('revocation after initial admission rejects before inspecting the object',async()=>{
  for(const opts of [{revoked:true},{unilateral:true}]){
    const db=fakeDb(opts),res=await run(db);
    assert.equal(res.code,'R2_CONNECTION_REVOKED');
    assert.equal(db.calls.length,1);
  }
});
test('message cancelled, expired, delivered or object missing rejects',async()=>{
  for(const opts of [{cancelled:true},{expired:true},{state:'UPLOADED'},
    {terminal:true},{wrongObject:true}]){
    const db=fakeDb(opts),res=await run(db);
    assert.equal(res.allowed,false);
    assert.equal(db.calls.some(x=>x.sql.includes('FROM public.message_r2_objects')),true);
  }
});
test('object descriptor changed between admission and preflight fails closed',async()=>{
  for(const opts of [{digest:'b'.repeat(64)},{bytes:1235}]){
    const db=fakeDb(opts),res=await run(db);
    assert.equal(res.code,'R2_READ_DESCRIPTOR_CHANGED');
    assert.equal(db.calls.length,3);
  }
});
test('invalid prior metadata fails before any SQL',async()=>{
  for(const value of [
    {...admission.value,expectedSha256:'nope'},
    {...admission.value,expectedBytes:0},
    {...admission.value,peerIdentity:recipient}
  ]){
    const db=fakeDb(),res=await run(db,{...admission,value});
    assert.equal(res.code,'R2_INVALID_PRIOR_ADMISSION');
    assert.equal(db.calls.length,0);
  }
});
test('PostgreSQL unavailable fails closed and never grants a stream',async()=>{
  await assert.rejects(run(fakeDb({down:true})),/POSTGRES_DOWN/);
});
test('preflight requires transaction driver and performs no R2 I/O',async()=>{
  await assert.rejects(revalidateR2ReadAtStreamStart({
    withTransaction:null,admission
  }),/PostgreSQL transaction required/);
});
