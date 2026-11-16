'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const {claimR2Verification, settleR2Verification, MAX_DAILY_VERIFIED_BYTES,
  MAX_DAILY_VERIFICATIONS} = require('../src/r2VerificationAdmission');

const key='pending/12/34/12345678-1234-4234-8234-123456789abc';
const actor='AAAABBBBCCCCDDDD', peer='1111222233334444';
const request={authenticatedIdentity:actor,recipientIdentity:peer,
  objectKey:key,messageId:'message-1',expectedBytes:25*1024*1024};

function mock(options={}){
  const calls=[];
  const media={object_key:key,message_id:'message-1',
    sender_identity:actor,recipient_identity:peer,
    state:'RESERVED',upload_generation:1,lease_active:true,...options.media};
  const query=async(sql,args)=>{
    calls.push({sql,args});
    if(options.failDb)throw Error('POSTGRES_DOWN');
    if(sql.includes('FROM public.message_r2_objects'))return {
      rowCount:options.missingMedia?0:1,rows:options.missingMedia?[]:[media]
    };
    if(sql.includes('INSERT INTO public.r2_verification_daily_budget'))return {rowCount:1,rows:[]};
    if(sql.includes('SELECT attempts,reserved_bytes'))return {
      rowCount:1,rows:[{attempts:options.attempts??0,
        reserved_bytes:options.reservedBytes??0}]
    };
    if(sql.includes('INSERT INTO public.r2_verification_claims'))return {
      rowCount:options.busy?0:1,
      rows:options.busy?[]:[{claim_id:'123e4567-e89b-42d3-a456-426614174000',
        lease_expires_at:'2026-11-16T18:05:00Z'}]
    };
    if(sql.includes('UPDATE public.r2_verification_daily_budget'))return {
      rowCount:1,rows:[{attempts:1,reserved_bytes:request.expectedBytes}]
    };
    if(sql.includes('UPDATE public.r2_verification_claims'))return {
      rowCount:options.settleMissing?0:1,rows:[{claim_id:args[0]}]
    };
    throw Error('UNEXPECTED_SQL '+sql);
  };
  return {calls,query,withTransaction:async fn=>fn(query)};
}
const claim=(h,overrides={})=>claimR2Verification({
  ...request,...overrides,withTransaction:h.withTransaction
});
test('authenticated reserved media is admitted and charged once inside transaction',async()=>{
  const h=mock();
  const res=await claim(h);
  assert.equal(res.granted,true);
  assert.equal(res.authenticatedIdentity,actor);
  assert.equal(res.expectedBytes,request.expectedBytes);
  assert.equal(h.calls.length,5);
  assert.match(h.calls[0].sql,/FOR UPDATE/);
  assert.match(h.calls[2].sql,/FOR UPDATE/);
  assert.match(h.calls[3].sql,/ON CONFLICT\(object_key\) DO UPDATE/);
  assert.match(h.calls[4].sql,/reserved_bytes=reserved_bytes\+\$2/);
});
test('already UPLOADED, ATTACHED or expired lease never incur R2 reads',async()=>{
  for(const media of [{state:'UPLOADED'},{state:'ATTACHED'},
    {lease_active:false},{upload_generation:0},{state:'ORPHAN_QUEUED'}]){
    const h=mock({media});
    const res=await claim(h);
    assert.equal(res.granted,false);
    assert.equal(h.calls.length,1);
  }
});
test('forged actor or peer cannot claim a message',async()=>{
  for(const overrides of [{authenticatedIdentity:'1234567890ABCDEF'},
    {recipientIdentity:'FEDCBA9876543210'}]){
    const h=mock();
    const res=await claim(h,overrides);
    assert.equal(res.code,'R2_OBJECT_NOT_AUTHORIZED');
    assert.equal(h.calls.length,1);
  }
});
test('daily attempt and daily byte ceilings reject before unique claim',async()=>{
  for(const options of [
    {attempts:MAX_DAILY_VERIFICATIONS},
    {reservedBytes:MAX_DAILY_VERIFIED_BYTES-request.expectedBytes+1}
  ]) {
    const h=mock(options);
    const res=await claim(h);
    assert.equal(res.code,'R2_DAILY_VERIFY_QUOTA_EXCEEDED');
    assert.equal(h.calls.length,3);
  }
});
test('object-level single-flight rejects duplicate without charging budget',async()=>{
  const h=mock({busy:true});
  const res=await claim(h);
  assert.equal(res.code,'R2_VERIFICATION_IN_PROGRESS');
  assert.equal(h.calls.length,4);
});
test('unsupported sizes and invalid object identifiers fail closed',async()=>{
  for(const overrides of [
    {expectedBytes:0},{expectedBytes:25*1024*1024+1},
    {authenticatedIdentity:'user'},{messageId:''},
    {recipientIdentity:actor}
  ]){
    const h=mock();
    const res=await claim(h,overrides);
    assert.equal(res.granted,false);
    assert.equal(h.calls.length,0);
  }
  await assert.rejects(claim(mock(),{objectKey:'../secret'}),/Invalid R2 object key/);
});
test('PostgreSQL outages propagate and never grant a claim',async()=>{
  const h=mock({failDb:true});
  await assert.rejects(claim(h),/POSTGRES_DOWN/);
});
test('settle requires claim token and updates only running matching claim',async()=>{
  const h=mock();
  const res=await settleR2Verification({
    query:h.query,claimId:'123e4567-e89b-42d3-a456-426614174000',
    objectKey:key,authenticatedIdentity:actor,successful:true
  });
  assert.equal(res.settled,true);
  assert.match(h.calls[0].sql,/state='RUNNING' AND lease_expires_at>clock_timestamp/);
  assert.deepEqual(h.calls[0].args,[
    '123e4567-e89b-42d3-a456-426614174000',key,actor,'COMPLETED'
  ]);
  const missing=mock({settleMissing:true});
  assert.equal((await settleR2Verification({
    query:missing.query,claimId:'123e4567-e89b-42d3-a456-426614174000',
    objectKey:key,authenticatedIdentity:actor,successful:false
  })).settled,false);
});
