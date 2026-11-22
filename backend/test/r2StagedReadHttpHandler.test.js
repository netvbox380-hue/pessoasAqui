'use strict';
const test=require('node:test');
const assert=require('node:assert/strict');
const {createStagedR2ReadHandler}=require('../src/r2StagedReadHttpHandler');
const key='pending/12/34/12345678-1234-4234-8234-123456789abc';
const body={
  identity:'AABBCCDDEEFF0011',peerIdentity:'1122334455667788',
  messageId:'msg-1',objectKey:key,
  proof:{v:1,timestamp:Date.now(),
    nonce:'12345678-1234-4234-8234-123456789abc',
    publicKey:'pub-key',signature:'signature'}
};
const success=Object.freeze({
  authorized:true,admitted:true,
  value:{allowed:true,authenticatedIdentity:body.identity,
    peerIdentity:body.peerIdentity,messageId:body.messageId,objectKey:key,
    expectedSha256:'a'.repeat(64),expectedBytes:3}
});
function fixture({ready=true,admission=success,
  stream=async()=>({sent:true})}={}){
  let admits=0,streams=0;
  const runtime={
    get ready(){return ready;},
    stream:async args=>{streams++;return stream(args);}
  };
  const handler=createStagedR2ReadHandler({
    withTransaction:async callback=>callback(async()=>({rowCount:0,rows:[]})),
    runtime,openCiphertext:async()=>{},
    admitRead:async params=>{admits++;return typeof admission==='function'?
      admission(params):admission;}
  });
  const response={
    headers:{},statusCode:200,headersSent:false,body:null,destroyed:false,
    status(code){this.statusCode=code;return this;},
    json(payload){this.body=payload;return this;},
    setHeader(name,value){this.headers[name]=value;},
    destroy(){this.destroyed=true;}
  };
  const call=(requestBody=body,method='POST')=>
    handler({body:requestBody,method},response);
  return {call,response,get admits(){return admits;},
    get streams(){return streams;}};
}
test('handler factory is inert and does not create HTTP listener',()=>{
  const f=fixture();
  assert.equal(f.admits,0);assert.equal(f.streams,0);
});
test('valid POST produces no-store ciphertext stream only after authenticated admission',async()=>{
  const f=fixture({stream:async({admission,openCiphertext,response})=>{
    assert.equal(admission,success);
    assert.equal(typeof openCiphertext,'function');
    assert.equal(response,f.response);
    assert.equal(f.admits,1);
    return {sent:true};
  }});
  await f.call();
  assert.equal(f.response.statusCode,200);
  assert.equal(f.response.headers['Content-Type'],'application/octet-stream');
  assert.equal(f.response.headers['Cache-Control'],'no-store, private');
  assert.equal(f.response.headers['X-Content-Type-Options'],'nosniff');
  assert.equal(f.admits,1);assert.equal(f.streams,1);
});
test('rejects method other than POST before admission',async()=>{
  const f=fixture();
  await f.call(body,'GET');
  assert.equal(f.response.statusCode,405);
  assert.equal(f.admits,0);assert.equal(f.streams,0);
});
test('unknown JSON fields or client-supplied admission are rejected before DB',async()=>{
  for(const bad of [
    {...body,admission:success},
    {...body,identity:body.peerIdentity,peerIdentity:body.peerIdentity},
    {...body,objectKey:'../secret'},
    {...body,messageId:'bad\nvalue'},
    {...body,proof:{...body.proof,v:2}}
  ]){
    const f=fixture();await f.call(bad);
    assert.equal(f.response.statusCode,400);
    assert.equal(f.admits,0);
  }
});
test('listener not ready blocks nonce charge and any stream',async()=>{
  const f=fixture({ready:false});
  await f.call();
  assert.equal(f.response.statusCode,503);
  assert.equal(f.response.body.code,'R2_REVOCATION_LISTENER_NOT_READY');
  assert.equal(f.admits,0);assert.equal(f.streams,0);
});
test('unauthorized cryptographic admission returns sanitized status',async()=>{
  const f=fixture({admission:{
    authorized:false,statusCode:401,code:'INVALID_R2_IDENTITY_PROOF'
  }});
  await f.call();
  assert.equal(f.response.statusCode,401);
  assert.equal(f.response.body.code,'R2_READ_NOT_AUTHORIZED');
  assert.equal(f.streams,0);
});
test('admission exception is fail-closed, no storage fetch attempted',async()=>{
  const f=fixture({admission:async()=>{throw Error('db credential secret');}});
  await f.call();
  assert.equal(f.response.statusCode,503);
  assert.equal(f.response.body.code,'R2_READ_ADMISSION_UNAVAILABLE');
  assert.equal(f.streams,0);
});
test('trusted admission mismatch is denied before streaming',async()=>{
  const f=fixture({admission:{...success,value:{...success.value,objectKey:'other'}}});
  await f.call();
  assert.equal(f.response.statusCode,503);
  assert.equal(f.streams,0);
});
test('preflight rejection does not claim successful transfer',async()=>{
  const f=fixture({stream:async()=>({
    sent:false,statusCode:403,code:'R2_CONNECTION_REVOKED'
  })});
  await f.call();
  assert.equal(f.response.statusCode,403);
  assert.equal(f.response.body.code,'R2_STREAM_DENIED');
  assert.equal(f.streams,1);
});
test('late revocation after response headers leads to HTTP stream abort',async()=>{
  const f=fixture({stream:async({response})=>{
    response.headersSent=true;
    return {sent:false,statusCode:409,code:'R2_STREAM_REVOKED'};
  }});
  await f.call();
  assert.equal(f.response.destroyed,true);
  assert.equal(f.response.body,null);
});
test('dependencies fail closed with bad or missing objects',()=>{
  assert.throws(()=>createStagedR2ReadHandler({
    withTransaction:null,runtime:{stream(){}},openCiphertext:async()=>{}
  }),/Trusted R2/);
  assert.throws(()=>createStagedR2ReadHandler({
    withTransaction:async()=>{},runtime:null,openCiphertext:async()=>{}
  }),/Trusted R2/);
});
