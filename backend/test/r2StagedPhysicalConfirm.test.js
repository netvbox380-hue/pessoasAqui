'use strict';
const test=require('node:test');
const assert=require('node:assert/strict');
const {
  preflightRetiredR2Put,finalizeVerifiedR2Upload,
  verifyAndFinalizeStagedR2Upload
}=require('../src/r2StagedPhysicalConfirm');

const identity='AABBCCDDEEFF0011';
const peerIdentity='1122334455667788';
const messageId='msg-30w';
const objectKey='pending/12/34/12345678-1234-4234-8234-123456789abc';
const activePublicKey='ACTIVE_P256_KEY';
const SHA='a'.repeat(64);
const claimId='123e4567-e89b-42d3-a456-426614174000';
const ticket=Object.freeze({
  authorized:true,granted:true,identity,peerIdentity,messageId,objectKey,
  generation:1,expectedSha256:SHA,expectedBytes:3,claimId
});
const verified=Object.freeze({verified:true,objectKey,sha256:SHA,bytes:3});
function database(options={}){
  let state={uploaded:false,settled:false,sealed:false};
  const sql=[];
  let inTx=false,commits=0,rollbacks=0;
  const withTransaction=async callback=>{
    if(inTx)throw Error('NESTED_TX_OR_NETWORK_HELD_LOCK');
    inTx=true;
    const before={...state};
    const tx=async(statement,args=[])=>{
      sql.push({statement,args});
      if(options.dbDown)throw Error('DATABASE_DOWN');
      if(statement.includes('FROM public.identity_key_bindings'))
        return {rowCount:1,rows:[{
          public_key_spki:options.rotatedKey?'ROTATED':activePublicKey
        }]};
      if(statement.includes('FROM public.connections')){
        const rows=[{from_identity:identity,to_identity:peerIdentity,is_mutual:true}];
        if(!options.revoked)rows.push({
          from_identity:peerIdentity,to_identity:identity,is_mutual:true
        });
        return {rowCount:rows.length,rows};
      }
      if(statement.includes('FROM public.message_r2_objects'))
        return {rowCount:options.missingMedia?0:1,
          rows:options.missingMedia?[]:[{
            object_key:objectKey,message_id:messageId,
            sender_identity:identity,recipient_identity:peerIdentity,
            state:options.inactive?'ORPHAN_QUEUED':'RESERVED',
            upload_generation:1,lease_active:!options.expired
          }]};
      if(statement.includes('FROM public.r2_verification_claims'))
        return {rowCount:options.staleClaim?0:1,rows:options.staleClaim?[]:[{
          claim_id:claimId,object_key:objectKey,message_id:messageId,
          identity_id:identity,generation:1,state:'RUNNING',claim_active:true
        }]};
      if(statement.includes('UPDATE public.r2_put_capability_windows')){
        state.sealed=true;
        return {rowCount:1,rows:[{
          sealed_at:'2026-11-22T20:00:00Z',
          expires_at:'2026-11-22T20:15:00Z'
        }]};
      }
      if(statement.includes('FROM public.r2_put_capability_windows'))
        return {rowCount:options.windowActive?0:1,
          rows:options.windowActive?[]:[{object_key:objectKey}]};
      if(statement.includes('UPDATE public.message_r2_objects')){
        if(options.uploadFails)return {rowCount:0,rows:[]};
        state.uploaded=true;
        return {rowCount:1,rows:[{object_key:objectKey}]};
      }
      if(statement.includes('UPDATE public.r2_verification_claims')){
        if(options.settleFails)return {rowCount:0,rows:[]};
        state.settled=true;
        return {rowCount:1,rows:[{claim_id:claimId}]};
      }
      throw Error('UNEXPECTED_SQL '+statement);
    };
    try{
      const result=await callback(tx);
      commits++;
      return result;
    }catch(e){
      state=before;
      rollbacks++;
      throw e;
    }finally{inTx=false;}
  };
  return {withTransaction,sql,get state(){return {...state};},
    get inTx(){return inTx;},get commits(){return commits;},
    get rollbacks(){return rollbacks;}};
}
function run(db,params={}){
  return verifyAndFinalizeStagedR2Upload({
    withTransaction:db.withTransaction,
    identity,peerIdentity,messageId,objectKey,generation:1,
    contentSha256:SHA,contentBytes:3,proof:{publicKey:activePublicKey},
    client:{send(){}},GetObjectCommand:class FakeGetObject{},bucket:'pa-test-bucket',
    admitConfirm:async()=>ticket,
    verifyCiphertext:async()=>verified,...params
  });
}
test('staged verification uses two short PostgreSQL transactions and GET outside both',async()=>{
  const db=database();
  let getCount=0;
  const x=await run(db,{verifyCiphertext:async input=>{
    getCount++;
    assert.equal(db.inTx,false);
    assert.equal(input.objectKey,objectKey);
    assert.equal(input.expectedSha256,SHA);
    assert.equal(input.expectedBytes,3);
    return verified;
  }});
  assert.equal(x.confirmed,true);
  assert.equal(getCount,1);
  assert.deepEqual(db.state,{uploaded:true,settled:true,sealed:true});
  assert.equal(db.commits,2);
  assert.equal(db.rollbacks,0);
  const media=db.sql.findIndex(x=>x.statement.includes('UPDATE public.message_r2_objects'));
  const settle=db.sql.findIndex(x=>x.statement.includes('UPDATE public.r2_verification_claims'));
  assert.ok(media>=0&&settle>media);
});
test('active presigned PUT window is sealed but prevents premature physical GET',async()=>{
  const db=database({windowActive:true});
  let gets=0;
  const x=await run(db,{verifyCiphertext:async()=>{gets++;return verified;}});
  assert.equal(x.confirmed,false);
  assert.equal(x.code,'R2_CONFIRM_PUT_WINDOW_ACTIVE');
  assert.equal(gets,0);
  assert.equal(db.state.sealed,true);
  assert.equal(db.state.uploaded,false);
  assert.equal(db.commits,1);
});
test('wrong digest/bytes or object never results in UPLOADED state',async()=>{
  for(const bad of [
    {...verified,sha256:'b'.repeat(64)},
    {...verified,bytes:2},
    {...verified,objectKey:'different'}
  ]){
    const db=database();
    const x=await run(db,{verifyCiphertext:async()=>bad});
    assert.equal(x.confirmed,false);
    assert.equal(x.code,'R2_CONFIRM_PHYSICAL_VERIFICATION_FAILED');
    assert.equal(db.state.uploaded,false);
    assert.equal(db.state.settled,false);
  }
});
test('GET failure does not finalize a claim or mark media uploaded',async()=>{
  const db=database();
  const x=await run(db,{verifyCiphertext:async()=>{throw Error('S3_DOWN_SECRET');}});
  assert.equal(x.confirmed,false);
  assert.equal(x.code,'R2_CONFIRM_STORAGE_VERIFICATION_FAILED');
  assert.equal(db.state.uploaded,false);
  assert.equal(db.state.settled,false);
  assert.equal(db.commits,1);
});
test('settlement failure rolls back the preceding UPLOADED state',async()=>{
  const db=database({settleFails:true});
  await assert.rejects(run(db),/R2_CONFIRM_SETTLEMENT_FAILED/);
  assert.equal(db.state.uploaded,false);
  assert.equal(db.state.settled,false);
  assert.equal(db.rollbacks,1);
});
test('upload transition failure rolls back and never completes claim',async()=>{
  const db=database({uploadFails:true});
  await assert.rejects(run(db),/R2_CONFIRM_UPLOAD_TRANSITION_FAILED/);
  assert.equal(db.state.uploaded,false);
  assert.equal(db.state.settled,false);
});
test('revoked pair or rotated P-256 identity blocks before storage IO',async()=>{
  for(const opts of [{revoked:true},{rotatedKey:true},{expired:true},
    {inactive:true},{missingMedia:true},{staleClaim:true}]){
    const db=database(opts);
    let gets=0;
    const x=await run(db,{verifyCiphertext:async()=>{gets++;return verified;}});
    assert.equal(x.confirmed,false);
    assert.equal(gets,0);
    assert.equal(db.state.uploaded,false);
  }
});
test('successful GET followed by pair revocation blocks finalization',async()=>{
  let revoke=false;
  // Change pair state between two short transactions, not during GET.
  const db=database({get revoked(){return revoke;}});
  const x=await run(db,{verifyCiphertext:async()=>{revoke=true;return verified;}});
  assert.equal(x.confirmed,false);
  assert.equal(x.code,'R2_CONFIRM_PAIR_REVOKED');
  assert.equal(db.state.uploaded,false);
});
test('claim ticket is internal and malformed tickets cannot be finalized',async()=>{
  const db=database();
  await assert.rejects(finalizeVerifiedR2Upload({
    withTransaction:db.withTransaction,ticket:{...ticket,claimId:'bad'},
    activePublicKey,verification:verified
  }),/Trusted physical/);
  assert.equal(db.sql.length,0);
});
test('preflight cannot skip the seal or use already invalid claim',async()=>{
  const db=database({staleClaim:true});
  const x=await preflightRetiredR2Put({
    withTransaction:db.withTransaction,ticket,activePublicKey
  });
  assert.equal(x.code,'R2_CONFIRM_CLAIM_INACTIVE');
  assert.equal(db.state.sealed,false);
});
