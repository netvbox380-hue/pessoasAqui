'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const {Readable} = require('node:stream');
const {verifyStoredCiphertext, R2IntegrityError} = require('../src/r2ObjectIntegrity');

const key='pending/12/34/12345678-1234-4234-8234-123456789abc';
const bytes=Buffer.from('ciphertext-only-e2ee-body');
const sha=crypto.createHash('sha256').update(bytes).digest('hex');
class GetObjectCommand {constructor(input){this.input=input;}}

function harness(chunks=[bytes], contentLength=bytes.length) {
  let stream;
  const client={send: async(command)=>{
    assert.equal(command.input.Key,key);
    assert.equal(command.input.Bucket,'pessoasaqui');
    stream=Readable.from(chunks);
    return {Body:stream,ContentLength:contentLength};
  }};
  return {client,getStream:()=>stream};
}
function verify(client,overrides={}) {
  return verifyStoredCiphertext({
    client,GetObjectCommand,bucket:'pessoasaqui',objectKey:key,
    expectedSha256:sha,expectedBytes:bytes.length,
    ...overrides
  });
}
test('checks bytes from real body stream, not object ETag or metadata hash',async()=>{
  const h=harness([bytes.subarray(0,7),bytes.subarray(7)]);
  const result=await verify(h.client);
  assert.deepEqual(result,{verified:true,bytes:bytes.length,sha256:sha,objectKey:key});
});
test('identical size but altered bytes fail SHA-256',async()=>{
  const h=harness([Buffer.from('ciphertext-only-e2ee-bodx')]);
  await assert.rejects(verify(h.client),e=>e.code==='R2_HASH_MISMATCH');
});
test('announced ContentLength mismatch aborts before consuming body',async()=>{
  const h=harness([bytes],bytes.length+1);
  await assert.rejects(verify(h.client),e=>e.code==='R2_SIZE_MISMATCH');
  assert.equal(h.getStream().destroyed,true);
});
test('actual stream exceeding declared expected length aborts',async()=>{
  const h=harness([bytes,Buffer.from('extra')]);
  await assert.rejects(verify(h.client),e=>e.code==='R2_SIZE_MISMATCH');
  assert.equal(h.getStream().destroyed,true);
});
test('truncated stream fails even if ContentLength header is correct',async()=>{
  const h=harness([bytes.subarray(0,-1)]);
  await assert.rejects(verify(h.client),e=>e.code==='R2_SIZE_MISMATCH');
});
test('invalid hashes, sizes and object keys are rejected',async()=>{
  const h=harness();
  for(const overrides of [
    {expectedSha256:'bad'},{expectedBytes:0},{expectedBytes:30*1024*1024},
    {objectKey:'../secret'},{maxBytes:30*1024*1024},{timeoutMs:1}
  ]) await assert.rejects(verify(h.client,overrides));
});
test('missing R2 object fails closed',async()=>{
  const client={send:async()=>{throw Object.assign(Error('missing'),{code:'NoSuchKey'});}};
  await assert.rejects(verify(client),e=>e.code==='NoSuchKey');
});
test('missing body cannot authorize a confirmation',async()=>{
  const client={send:async()=>({Body:null,ContentLength:bytes.length})};
  await assert.rejects(verify(client),e=>e.code==='R2_STREAM_UNSUPPORTED');
});
test('a stalled body times out and is destroyed',async()=>{
  const stream=new Readable({read(){}});
  const client={send:async()=>({Body:stream,ContentLength:bytes.length})};
  await assert.rejects(verify(client,{timeoutMs:100}),e=>e.code==='R2_READ_TIMEOUT');
  assert.equal(stream.destroyed,true);
});
test('invalid chunk type fails closed and destroys stream',async()=>{
  const h=harness(['wrong-type']);
  await assert.rejects(verify(h.client),e=>e.code==='R2_INVALID_STREAM_CHUNK');
});
