'use strict';
const test=require('node:test');
const assert=require('node:assert/strict');
const {createStagedR2DeleteAdapter}=require('../src/r2StagedDeleteAdapter');
const objectKey='pending/12/34/12345678-1234-4234-8234-123456789abc';
const bucket='pessoasaqui-test-bucket';
class FakeDeleteObjectCommand{constructor(input){this.input=input;}}
function factory(send=async()=>({$metadata:{httpStatusCode:204}})){
  return createStagedR2DeleteAdapter({
    client:{send},DeleteObjectCommand:FakeDeleteObjectCommand,bucket
  });
}
test('factory is inert and does not send a deletion until invoked',async()=>{
  let sends=0;
  const cb=factory(async()=>{sends++;return {$metadata:{httpStatusCode:204}};});
  assert.equal(sends,0);
  const signal=new AbortController().signal;
  const result=await cb({objectKey,signal});
  assert.deepEqual(result,{deleted:true,objectKey});
  assert.equal(sends,1);
  assert.equal(Object.isFrozen(result),true);
});
test('SDK command binds exact opaque key and trusted bucket, passes AbortSignal',async()=>{
  const controller=new AbortController();let observed;
  const cb=factory(async(command,opts)=>{
    observed={command,opts};
    return {$metadata:{httpStatusCode:204}};
  });
  await cb({objectKey,signal:controller.signal});
  assert.ok(observed.command instanceof FakeDeleteObjectCommand);
  assert.deepEqual(observed.command.input,{Bucket:bucket,Key:objectKey});
  assert.equal(observed.opts.abortSignal,controller.signal);
});
test('already aborted signal rejects without any SDK call',async()=>{
  let sends=0;
  const cb=factory(async()=>{sends++;return {$metadata:{httpStatusCode:204}};});
  const controller=new AbortController();controller.abort();
  await assert.rejects(cb({objectKey,signal:controller.signal}),/R2_DELETE_ABORTED/);
  assert.equal(sends,0);
});
test('SDK ignoring abort cannot cause false confirmation after late 204',async()=>{
  const controller=new AbortController();
  const cb=factory(async()=>{
    controller.abort();
    return {$metadata:{httpStatusCode:204}};
  });
  await assert.rejects(cb({objectKey,signal:controller.signal}),/R2_DELETE_ABORTED/);
});
test('missing metadata or non-204 response is not accepted as success',async()=>{
  for(const reply of [undefined,{},{$metadata:{httpStatusCode:200}},
    {$metadata:{httpStatusCode:500}}]){
    const cb=factory(async()=>reply);
    await assert.rejects(cb({objectKey,signal:new AbortController().signal}),
      /R2_DELETE_UNVERIFIED_RESPONSE/);
  }
});
test('SDK failure propagates to trusted orchestrator for retry',async()=>{
  const cb=factory(async()=>{throw Error('STORAGE_ERROR');});
  await assert.rejects(cb({objectKey,signal:new AbortController().signal}),
    /STORAGE_ERROR/);
});
test('invalid key or absent signal blocks request',async()=>{
  let sends=0;
  const cb=factory(async()=>{sends++;return {$metadata:{httpStatusCode:204}};});
  await assert.rejects(cb({objectKey:'../secret',signal:new AbortController().signal}),
    /Invalid R2 object key/);
  await assert.rejects(cb({objectKey}),/AbortSignal is required/);
  assert.equal(sends,0);
});
test('untrusted or invalid SDK dependencies fail at factory construction',()=>{
  assert.throws(()=>createStagedR2DeleteAdapter({
    client:null,DeleteObjectCommand:FakeDeleteObjectCommand,bucket
  }),/Trusted R2 S3Client/);
  assert.throws(()=>createStagedR2DeleteAdapter({
    client:{send(){}},DeleteObjectCommand:FakeDeleteObjectCommand,bucket:'../invalid'
  }),/Invalid trusted R2 bucket/);
});
