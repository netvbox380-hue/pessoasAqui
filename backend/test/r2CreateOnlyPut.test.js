'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const {
  validatePresignedCreateOnlyPut, issueCreateOnlyPutCapability,
  MAX_PRESIGNED_PUT_SECONDS
}=require('../src/r2CreateOnlyPut');
const objectKey='pending/12/34/12345678-1234-4234-8234-123456789abc';
const bucket='pessoasaqui';
const account='1234567890abcdef1234567890abcdef';
function url({key=objectKey,host=account+'.r2.cloudflarestorage.com',
  expires=900,headers='content-type;host;if-none-match',
  signature='a'.repeat(64),port='',scheme='https:'}={}) {
  const u=new URL(scheme+'//'+host+(port?':'+port:'')+
    '/'+bucket+'/'+key);
  for(const [name,value] of [
    ['X-Amz-Algorithm','AWS4-HMAC-SHA256'],
    ['X-Amz-Expires',String(expires)],
    ['X-Amz-SignedHeaders',headers],
    ['X-Amz-Signature',signature],
    ['X-Amz-Credential','example'],
    ['X-Amz-Date','20261116T120000Z']
  ])u.searchParams.set(name,value);
  return u.toString();
}
test('accepts signed create-only precondition and fixed headers',()=>{
  const r=validatePresignedCreateOnlyPut({url:url(),bucket,objectKey});
  assert.equal(r.method,'PUT');
  assert.equal(r.expiresIn,900);
  assert.deepEqual(r.requiredHeaders,{'If-None-Match':'*',
    'Content-Type':'application/octet-stream'});
});
test('refuses unsigned If-None-Match so Android cannot strip conditional write',()=>{
  assert.throws(()=>validatePresignedCreateOnlyPut({
    url:url({headers:'content-type;host'}),bucket,objectKey
  }),/must be signed/);
});
test('refuses unsigned Content-Type to preserve octet-stream contract',()=>{
  assert.throws(()=>validatePresignedCreateOnlyPut({
    url:url({headers:'host;if-none-match'}),bucket,objectKey
  }),/must be signed/);
});
test('refuses duplicate signed headers, excessively long TTL and empty signature',()=>{
  for(const input of [
    url({headers:'content-type;host;if-none-match;if-none-match'}),
    url({expires:901}),url({expires:3600}),url({expires:0}),
    url({signature:'bad'})
  ])assert.throws(()=>validatePresignedCreateOnlyPut({
    url:input,bucket,objectKey
  }));
});
test('refuses URL to unrelated host, bucket, object key or plaintext HTTP',()=>{
  for(const input of [
    url({host:'evil.example'}),url({key:'pending/12/34/aaaaaaaa-1234-4234-8234-123456789abc'}),
    url({scheme:'http:'}),url({port:'8443'})
  ])assert.throws(()=>validatePresignedCreateOnlyPut({
    url:input,bucket,objectKey
  }));
});
test('refuses missing or duplicate SigV4 query fields',()=>{
  const missing=new URL(url());
  missing.searchParams.delete('X-Amz-Signature');
  assert.throws(()=>validatePresignedCreateOnlyPut({
    url:missing.toString(),bucket,objectKey
  }));
  const duplicate=new URL(url());
  duplicate.searchParams.append('X-Amz-Expires','900');
  assert.throws(()=>validatePresignedCreateOnlyPut({
    url:duplicate.toString(),bucket,objectKey
  }));
});
test('capability requires server-authenticated DB reservation and fixed signer contract',async()=>{
  let request;
  const signPutUrl=async opts=>{request=opts;return url();};
  const r=await issueCreateOnlyPutCapability({signPutUrl,bucket,objectKey,
    authorizedReservation:{reserved:true,generation:1,bucket,objectKey}});
  assert.equal(r.expiresIn,MAX_PRESIGNED_PUT_SECONDS);
  assert.equal(request.headers['If-None-Match'],'*');
  assert.equal(request.headers['Content-Type'],'application/octet-stream');
});
test('missing server-side reservation or mismatched key always rejects before signer',async()=>{
  let called=false;
  const signer=async()=>{called=true;return url();};
  for(const authorizedReservation of [null,
    {reserved:true,generation:1,bucket,objectKey:'pending/other'},
    {reserved:true,generation:0,bucket,objectKey},
    {reserved:false,generation:1,bucket,objectKey}]) {
    await assert.rejects(issueCreateOnlyPutCapability({
      signPutUrl:signer,bucket,objectKey,authorizedReservation
    }));
  }
  assert.equal(called,false);
});
test('rejects signed PUT URL returned without create-only guarantee',async()=>{
  await assert.rejects(issueCreateOnlyPutCapability({
    signPutUrl:async()=>url({headers:'content-type;host'}),
    bucket,objectKey,
    authorizedReservation:{reserved:true,generation:1,bucket,objectKey}
  }),/must be signed/);
});
