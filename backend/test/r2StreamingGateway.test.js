'use strict';
const test=require('node:test');
const assert=require('node:assert/strict');
const crypto=require('node:crypto');
const {Readable,Writable}=require('node:stream');
const {createR2ActiveStreamRegistry}=require('../src/r2ActiveStreamRegistry');
const {streamAuthenticatedR2Ciphertext}=require('../src/r2StreamingGateway');

const sender='AAAABBBBCCCCDDDD',recipient='1111222233334444';
const bytes=Buffer.from('opaque-e2ee-ciphertext-for-test');
const sha=crypto.createHash('sha256').update(bytes).digest('hex');
const admission={authorized:true,admitted:true,value:{
  allowed:true,authenticatedIdentity:recipient,peerIdentity:sender,
  messageId:'message-1',objectKey:'pending/12/34/12345678-1234-4234-8234-123456789abc',
  expectedSha256:sha,expectedBytes:bytes.byteLength
}};
function response(){
  const chunks=[];
  const res=new Writable({write(chunk,_encoding,done){
    chunks.push(Buffer.from(chunk));done();
  }});
  res.received=()=>Buffer.concat(chunks);
  return res;
}
function context(options={}){
  const registry=createR2ActiveStreamRegistry();
  const listener={ready:options.ready!==false};
  let opened=0,queries=0;
  const withTransaction=async fn=>fn(async sql=>{
    queries++;
    if(options.down)throw Error('DB_DOWN');
    if(sql.includes('FROM public.connections')){
      const rows=[
        {from_identity:sender,to_identity:recipient,is_mutual:true},
        {from_identity:recipient,to_identity:sender,is_mutual:!options.revoked}
      ];
      return {rowCount:2,rows};
    }
    if(sql.includes('FROM public.message_r2_objects'))
      return {rowCount:1,rows:[{
        state:options.cancelled?'UPLOADED':'ATTACHED',content_sha256:sha,
        content_bytes:bytes.length,delivery_state:'PENDING',
        is_cancelled:false,is_active:true
      }]};
    if(sql.includes('FROM public.message_delivery_receipts'))
      return {rowCount:0,rows:[]};
    throw Error('UNEXPECTED_SQL');
  });
  const openCiphertext=async({abortSignal})=>{
    opened++;
    if(options.onOpen)options.onOpen({registry,listener,abortSignal});
    return {contentLength:options.badLength?bytes.length+1:bytes.length,
      body:Readable.from([options.badHash?Buffer.alloc(bytes.length):bytes])};
  };
  return {registry,listener,withTransaction,openCiphertext,res:response(),
    get opened(){return opened},get queries(){return queries}};
}
async function run(c,overrides={}){
  return streamAuthenticatedR2Ciphertext({
    admission,registry:c.registry,listener:c.listener,
    withTransaction:c.withTransaction,openCiphertext:c.openCiphertext,
    response:c.res,...overrides
  });
}
test('successful server-side stream delivers only opaque ciphertext with exact digest',async()=>{
  const c=context(),out=await run(c);
  assert.deepEqual(out,{sent:true,bytes:bytes.length});
  assert.deepEqual(c.res.received(),bytes);
  assert.equal(c.registry.activeCount,0);
  assert.equal(c.opened,1);assert.equal(c.queries,3);
});
test('requires signed previous admission and a live listener without DB or GET',async()=>{
  const c=context();
  assert.equal((await run(c,{admission:null})).statusCode,401);
  c.listener.ready=false;
  assert.equal((await run(c)).code,'R2_REVOCATION_LISTENER_NOT_READY');
  assert.equal(c.opened,0);assert.equal(c.queries,0);
});
test('revocation before preflight disallows opening R2 object',async()=>{
  const c=context({revoked:true});
  assert.equal((await run(c)).code,'R2_CONNECTION_REVOKED');
  assert.equal(c.opened,0);
  assert.equal(c.registry.activeCount,0);
});
test('cancelled or unlinked message rejects without R2 GET',async()=>{
  const c=context({cancelled:true});
  assert.equal((await run(c)).code,'R2_MESSAGE_NOT_DOWNLOADABLE');
  assert.equal(c.opened,0);
});
test('revocation after SQL but during R2 open prevents first ciphertext byte',async()=>{
  const c=context({onOpen:({registry})=>registry.revokeMessage('message-1')});
  const out=await run(c);
  assert.equal(out.sent,false);
  assert.equal(c.res.received().length,0);
  assert.equal(c.registry.activeCount,0);
});
test('invalid R2 content length is rejected without sending content',async()=>{
  const c=context({badLength:true}),out=await run(c);
  assert.equal(out.code,'R2_CIPHERTEXT_DESCRIPTOR_MISMATCH');
  assert.equal(c.res.received().length,0);
});
test('ciphertext hash mismatch destroys downstream after streaming; Android must discard',async()=>{
  const c=context({badHash:true}),out=await run(c);
  assert.equal(out.code,'R2_STREAM_ABORTED_OR_INTEGRITY_FAILED');
  assert.equal(c.res.destroyed,true);
  assert.equal(c.res.received().length,bytes.length);
});
test('database outage fails closed without opening R2 bytes',async()=>{
  const c=context({down:true}),out=await run(c);
  assert.equal(out.sent,false);
  assert.equal(c.opened,0);
  assert.equal(c.registry.activeCount,0);
  assert.equal(c.res.destroyed,true);
});
