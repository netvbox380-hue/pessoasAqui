'use strict';
const test=require('node:test');
const assert=require('node:assert/strict');
const {Readable}=require('node:stream');
const {createStagedR2GetTransport}=
  require('../src/r2StagedGetTransport');

const objectKey='pending/12/34/12345678-1234-4234-8234-123456789abc';
const bucket='pessoasaqui-test-bucket';
class FakeGetObjectCommand{constructor(input){this.input=input;}}
function fixture(send){
  let calls=0;
  const open=createStagedR2GetTransport({
    client:{async send(...args){calls++;return send(...args);}},
    GetObjectCommand:FakeGetObjectCommand,bucket
  });
  return {open,get calls(){return calls;}};
}
function stream(bytes='abc'){
  return Readable.from([Buffer.from(bytes)]);
}
test('construction is inert, no GET before explicit request',()=>{
  const f=fixture(async()=>({Body:stream(),ContentLength:3}));
  assert.equal(f.calls,0);
  assert.equal(typeof f.open,'function');
});
test('GET uses exact trusted bucket/key and forwards original abort signal',async()=>{
  let sent;
  const f=fixture(async(command,options)=>{
    sent={command,options};
    return {Body:stream(),ContentLength:3};
  });
  const signal=new AbortController().signal;
  const result=await f.open({objectKey,expectedBytes:3,abortSignal:signal});
  assert.ok(sent.command instanceof FakeGetObjectCommand);
  assert.deepEqual(sent.command.input,{Bucket:bucket,Key:objectKey});
  assert.equal(sent.options.abortSignal,signal);
  assert.equal(result.contentLength,3);
  assert.equal(Object.isFrozen(result),true);
  const chunks=[];
  for await(const c of result.body)chunks.push(c);
  assert.equal(Buffer.concat(chunks).toString(),'abc');
});
test('key traversal, invalid sizes and missing abort denied before storage',async()=>{
  const f=fixture(async()=>({Body:stream(),ContentLength:3}));
  const ctrl=new AbortController();
  for(const args of [
    {objectKey:'../path',expectedBytes:3,abortSignal:ctrl.signal},
    {objectKey,expectedBytes:0,abortSignal:ctrl.signal},
    {objectKey,expectedBytes:26*1024*1024,abortSignal:ctrl.signal},
    {objectKey,expectedBytes:3}
  ])await assert.rejects(f.open(args));
  assert.equal(f.calls,0);
});
test('pre-aborted signal causes zero GET calls',async()=>{
  const f=fixture(async()=>({Body:stream(),ContentLength:3}));
  const ctrl=new AbortController();ctrl.abort();
  await assert.rejects(f.open({
    objectKey,expectedBytes:3,abortSignal:ctrl.signal
  }),/R2_GET_ABORTED/);
  assert.equal(f.calls,0);
});
test('SDK returns length less than expected: destroy stream',async()=>{
  const body=stream('ab');
  const f=fixture(async()=>({Body:body,ContentLength:2}));
  await assert.rejects(f.open({
    objectKey,expectedBytes:3,abortSignal:new AbortController().signal
  }),/descriptor length mismatch/);
  assert.equal(body.destroyed,true);
});
test('SDK returns larger than expected: stop before streaming',async()=>{
  const body=stream('abcd');
  const f=fixture(async()=>({Body:body,ContentLength:4}));
  await assert.rejects(f.open({
    objectKey,expectedBytes:3,abortSignal:new AbortController().signal
  }),/exceeds configured bound/);
  assert.equal(body.destroyed,true);
});
test('missing/invalid SDK body fails closed',async()=>{
  const f=fixture(async()=>({Body:{},ContentLength:3}));
  await assert.rejects(f.open({
    objectKey,expectedBytes:3,abortSignal:new AbortController().signal
  }),/cancellable Node.js stream/);
});
test('abort after SDK returns cancels active stream and detaches listener',async()=>{
  const ctrl=new AbortController();
  const body=new Readable({read(){}});
  const f=fixture(async()=>({Body:body,ContentLength:3}));
  const opened=await f.open({
    objectKey,expectedBytes:3,abortSignal:ctrl.signal
  });
  assert.equal(opened.body,body);
  assert.equal(ctrl.signal.listenerCount?.('abort'),undefined);
  ctrl.abort();
  assert.equal(body.destroyed,true);
  assert.equal(ctrl.signal.aborted,true);
});
test('abort while SDK is responding prevents late body from streaming',async()=>{
  const ctrl=new AbortController();
  const body=stream();
  const f=fixture(async()=>{ctrl.abort();return {Body:body,ContentLength:3};});
  await assert.rejects(f.open({
    objectKey,expectedBytes:3,abortSignal:ctrl.signal
  }),/R2 GET aborted/);
  assert.equal(body.destroyed,true);
});
test('SDK transport error propagates, never returns a fake success',async()=>{
  const f=fixture(async()=>{throw Error('ECONNRESET');});
  await assert.rejects(f.open({
    objectKey,expectedBytes:3,abortSignal:new AbortController().signal
  }),/ECONNRESET/);
});
