'use strict';
const test=require('node:test');
const assert=require('node:assert/strict');
const {
  createStagedR2S3DeleteClient,CONNECTION_TIMEOUT_MS,
  REQUEST_TIMEOUT_MS,MAX_SOCKETS
}=require('../src/r2StagedS3ClientFactory');

const key='pending/12/34/12345678-1234-4234-8234-123456789abc';
let configs,handlers,sends,destroyed;
class S3Client{
  constructor(opts){configs.push(opts);}
  async send(command,options){
    sends.push({command,options});
    return {$metadata:{httpStatusCode:204}};
  }
  destroy(){destroyed++;}
}
class DeleteObjectCommand{
  constructor(input){this.input=input;}
}
class NodeHttpHandler{
  constructor(options){handlers.push(options);}
}
const input={
  S3Client,DeleteObjectCommand,NodeHttpHandler,
  accountId:'0123456789abcdef0123456789abcdef',
  bucket:'pessoasaqui-test-bucket',
  accessKeyId:'test-key-12345678',
  secretAccessKey:'test-secret-12345678'
};
function reset(){configs=[];handlers=[];sends=[];destroyed=0;}
test('import and composition are inert: no remote storage actions',()=>{
  reset();
  assert.equal(sends.length,0);
  const session=createStagedR2S3DeleteClient(input);
  assert.equal(sends.length,0);
  assert.equal(typeof session.deleteObject,'function');
  assert.equal(Object.isFrozen(session),true);
  session.close();
  assert.equal(destroyed,1);
});
test('client must use exact account HTTPS endpoint and disable SDK retry',()=>{
  reset();
  const session=createStagedR2S3DeleteClient(input);
  assert.equal(configs.length,1);
  const cfg=configs[0];
  assert.equal(cfg.endpoint,'https://0123456789abcdef0123456789abcdef.r2.cloudflarestorage.com');
  assert.equal(cfg.region,'auto');
  assert.equal(cfg.forcePathStyle,true);
  assert.equal(cfg.maxAttempts,1);
  assert.deepEqual(cfg.credentials,{
    accessKeyId:'test-key-12345678',secretAccessKey:'test-secret-12345678'
  });
  assert.equal(cfg.requestHandler instanceof NodeHttpHandler,true);
  session.close();
});
test('https transport enforces TLS and bounded 5s / 10s timeouts',()=>{
  reset();
  const session=createStagedR2S3DeleteClient(input);
  assert.equal(handlers.length,1);
  assert.equal(handlers[0].connectionTimeout,CONNECTION_TIMEOUT_MS);
  assert.equal(handlers[0].requestTimeout,REQUEST_TIMEOUT_MS);
  assert.equal(handlers[0].httpsAgent.options.rejectUnauthorized,true);
  assert.equal(handlers[0].httpsAgent.maxSockets,MAX_SOCKETS);
  session.close();
});
test('trusted delete callback passes exact key and abort signal to S3Client',async()=>{
  reset();
  const session=createStagedR2S3DeleteClient(input);
  const signal=new AbortController().signal;
  assert.deepEqual(await session.deleteObject({objectKey:key,signal}),
    {deleted:true,objectKey:key});
  assert.equal(sends.length,1);
  assert.equal(sends[0].command instanceof DeleteObjectCommand,true);
  assert.deepEqual(sends[0].command.input,{Bucket:input.bucket,Key:key});
  assert.equal(sends[0].options.abortSignal,signal);
  session.close();
});
test('missing or invalid account and credentials fail closed before constructor',()=>{
  for(const bad of [
    {accountId:'not-a-real-account'},
    {accountId:'ABCDEF0123456789abcdef0123456789'},
    {bucket:'bad.bucket'},
    {bucket:'../escape'},
    {accessKeyId:'a'},
    {secretAccessKey:''},
    {secretAccessKey:'password\nattack'}
  ]){
    reset();
    assert.throws(()=>createStagedR2S3DeleteClient({...input,...bad}),
      /Invalid trusted Cloudflare R2 configuration/);
    assert.equal(configs.length,0);
  }
});
test('missing SDK classes fail closed and prevent client creation',()=>{
  reset();
  assert.throws(()=>createStagedR2S3DeleteClient({...input,S3Client:null}),
    /Trusted AWS SDK/);
  assert.throws(()=>createStagedR2S3DeleteClient({...input,NodeHttpHandler:null}),
    /Trusted AWS SDK/);
  assert.equal(configs.length,0);
});
test('client constructor failures do not leak secrets in error message',()=>{
  reset();
  class FailingClient{constructor(){throw Error('secret='+input.secretAccessKey);}}
  assert.throws(()=>createStagedR2S3DeleteClient({...input,S3Client:FailingClient}),
    error=>{
      assert.equal(error.message,'R2_STAGED_SDK_CONFIGURATION_FAILED');
      assert.equal(error.message.includes(input.secretAccessKey),false);
      return true;
    });
});
test('abort blocks the storage operation before send()',async()=>{
  reset();
  const session=createStagedR2S3DeleteClient(input);
  const abort=new AbortController();abort.abort();
  await assert.rejects(
    session.deleteObject({objectKey:key,signal:abort.signal}),/R2_DELETE_ABORTED/);
  assert.equal(sends.length,0);
  session.close();
});
