'use strict';
const test=require('node:test');
const assert=require('node:assert/strict');
const {createStagedR2ReserveHandler,validReserveBody}=
  require('../src/r2StagedReserveHttpHandler');
const objectKey='pending/12/34/12345678-1234-4234-8234-123456789abc';
const body={
  identity:'AABBCCDDEEFF0011',peerIdentity:'1122334455667788',
  messageId:'msg-1',objectKey,
  proof:{
    v:1,timestamp:1794823200000,
    nonce:'12345678-1234-4234-8234-123456789abc',
    publicKey:'active-key',signature:'signed'
  }
};
function fixture(outcome={
  authorized:true,reserved:true,duplicate:false,messageId:body.messageId,
  objectKey,generation:1,leaseExpiresAt:'2026-11-23T12:00:00Z'
}){
  let calls=0;
  const handler=createStagedR2ReserveHandler({
    withTransaction:async cb=>cb(async()=>({rows:[],rowCount:0})),
    reserve:async args=>{
      calls++;
      if(typeof outcome==='function')return outcome(args);
      return outcome;
    }
  });
  const res={
    code:200,payload:null,headers:{},
    status(n){this.code=n;return this;},
    json(value){this.payload=value;return this;},
    setHeader(name,value){this.headers[name]=value;}
  };
  return {handler,res,get calls(){return calls;},
    invoke:(b=body,method='POST')=>handler({method,body:b},res)};
}
test('valid reserve HTTP request returns only lease descriptor, never a presigned URL',async()=>{
  const f=fixture();
  await f.invoke();
  assert.equal(f.calls,1);
  assert.equal(f.res.code,200);
  assert.equal(f.res.payload.reserved,true);
  assert.equal(f.res.payload.generation,1);
  assert.equal(f.res.payload.url,undefined);
  assert.equal(f.res.headers['Cache-Control'],'no-store, private');
});
test('wrong method rejected before any database work',async()=>{
  const f=fixture();
  await f.invoke(body,'GET');
  assert.equal(f.res.code,405);
  assert.equal(f.calls,0);
});
test('unknown JSON field including admission and capability is rejected',async()=>{
  const f=fixture();
  await f.invoke({...body,admission:{authorized:true}});
  assert.equal(f.res.code,400);
  assert.equal(f.calls,0);
});
test('invalid identity/message/key cannot reach authenticated action',async()=>{
  for(const b of [
    {...body,identity:'forged'},
    {...body,peerIdentity:body.identity},
    {...body,messageId:'x\n'},
    {...body,objectKey:'../../x'},
    {...body,proof:{...body.proof,nonce:'bad'}},
    {...body,proof:{...body.proof,v:2}}
  ]){
    const f=fixture();await f.invoke(b);
    assert.equal(f.res.code,400);
    assert.equal(f.calls,0);
  }
});
test('valid body requires exactly signed P-256 proof envelope fields',()=>{
  assert.equal(validReserveBody(body),true);
  assert.equal(validReserveBody({...body,proof:{...body.proof,other:1}}),false);
  assert.equal(validReserveBody({...body,authenticatedIdentity:body.identity}),false);
});
test('signature and bilateral failures are relayed as sanitized denials',async()=>{
  for(const outcome of [
    {authorized:false,statusCode:401,code:'INVALID_R2_IDENTITY_PROOF'},
    {authorized:false,statusCode:403,code:'NO_MUTUAL_R2_CONNECTION'},
    {authorized:false,statusCode:409,code:'R2_PROOF_REPLAY'}
  ]){
    const f=fixture(outcome);
    await f.invoke();
    assert.equal(f.res.code,outcome.statusCode);
    assert.equal(f.res.payload.success,false);
  }
});
test('database errors do not leak internal exception or secret',async()=>{
  const f=fixture(async()=>{throw Error('database credentials secret');});
  await f.invoke();
  assert.equal(f.res.code,503);
  assert.equal(f.res.payload.code,'R2_RESERVATION_UNAVAILABLE');
});
test('trusted result mismatch is fail-closed rather than acknowledging',async()=>{
  const f=fixture({
    authorized:true,reserved:true,objectKey:'other',
    messageId:body.messageId,generation:1
  });
  await f.invoke();
  assert.equal(f.res.code,503);
  assert.equal(f.res.payload.code,'R2_RESERVATION_MISMATCH');
});

test('reservation daily quota denial propagates HTTP 429 without storage capability',async()=>{
  const f=fixture({
    authorized:false,reserved:false,statusCode:429,
    code:'R2_RESERVE_DAILY_QUOTA_EXCEEDED'
  });
  await f.invoke();
  assert.equal(f.res.code,429);
  assert.equal(f.res.payload.success,false);
  assert.equal(f.res.payload.code,'R2_RESERVE_DAILY_QUOTA_EXCEEDED');
  assert.equal(f.res.payload.url,undefined);
});
