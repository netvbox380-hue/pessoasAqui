'use strict';
const test=require('node:test');
const assert=require('node:assert/strict');
const crypto=require('node:crypto');
const {canonicalR2Action}=require('../src/r2ActionAuth');
const {admitAuthenticatedR2Reserve}=
  require('../src/r2StagedReserveAdmission');

const kp=crypto.generateKeyPairSync('ec',{namedCurve:'prime256v1'});
const publicKey=kp.publicKey.export({type:'spki',format:'der'}).toString('base64');
const actor=crypto.createHash('sha256')
  .update(Buffer.from(publicKey,'base64')).digest('hex').slice(0,16).toUpperCase();
const peer='FEDCBA9876543210';
const objectKey='pending/12/34/12345678-1234-4234-8234-123456789abc';
const messageId='msg-staged-reservation-1';
const now=1794823200000;
function proof(fields={messageId,objectKey},options={}){
  const timestamp=options.timestamp??now;
  const nonce=options.nonce??crypto.randomUUID();
  const bytes=canonicalR2Action('R2_RESERVE',actor,peer,
    fields,timestamp,nonce);
  const signer=crypto.createSign('SHA256');signer.update(bytes,'utf8');signer.end();
  return {v:1,timestamp,nonce,publicKey,
    signature:signer.sign(kp.privateKey).toString('base64')};
}
function database({key=publicKey,bilateral=true,replay=false,
  collision=false,expired=false,dbError=false}={}){
  const queries=[];
  const tx=async(sql,args=[])=>{
    queries.push({sql,args});
    if(dbError)throw Error('POSTGRES_DOWN');
    if(sql.includes('FROM public.identity_key_bindings'))
      return {rowCount:key?1:0,rows:key?[{public_key_spki:key}]:[]};
    if(sql.includes('FROM public.connections')){
      const rows=[
        {from_identity:actor,to_identity:peer,is_mutual:true},
        ...(bilateral?[{from_identity:peer,to_identity:actor,is_mutual:true}]:[])
      ];
      return {rowCount:rows.length,rows};
    }
    if(sql.includes('INSERT INTO public.r2_action_nonces'))
      return {rowCount:replay?0:1,rows:replay?[]:[{nonce:args[1]}]};
    if(sql.includes('INSERT INTO public.message_r2_objects')){
      if(collision)return {rowCount:0,rows:[]};
      return {rowCount:1,rows:[{
        object_key:objectKey,upload_generation:1,
        upload_lease_expires_at:'2026-11-23T00:00:00Z'
      }]};
    }
    if(sql.includes('FROM public.message_r2_objects'))
      return {rowCount:1,rows:[{
        object_key:objectKey,sender_identity:actor,recipient_identity:peer,
        state:'RESERVED',upload_generation:1,lease_active:!expired,
        upload_lease_expires_at:'2026-11-23T00:00:00Z'
      }]};
    throw Error('UNEXPECTED_SQL');
  };
  return {queries,withTransaction:async fn=>fn(tx)};
}
function reserve(db,overrides={}){
  return admitAuthenticatedR2Reserve({
    withTransaction:db.withTransaction,
    identity:actor,peerIdentity:peer,messageId,objectKey,
    proof:proof(),now,...overrides
  });
}
test('trusted proof, mutual connections and nonce precede immutable reservation',async()=>{
  const db=database();
  const outcome=await reserve(db);
  assert.equal(outcome.authorized,true);
  assert.equal(outcome.reserved,true);
  assert.equal(outcome.generation,1);
  assert.equal(outcome.objectKey,objectKey);
  assert.equal(Object.isFrozen(outcome),true);
  assert.deepEqual(db.queries.map(x=>x.sql).map(s=>
    s.includes('identity_key_bindings')?'KEY':
    s.includes('FROM public.connections')?'PAIR':
    s.includes('r2_action_nonces')?'NONCE':
    s.includes('INSERT INTO public.message_r2_objects')?'RESERVE':'OTHER'
  ),['KEY','PAIR','NONCE','RESERVE']);
  assert.equal(db.queries[3].args[2],actor);
  assert.equal(db.queries[3].args[3],peer);
});
test('wrong signing key is refused without reservation write',async()=>{
  const other=crypto.generateKeyPairSync('ec',{namedCurve:'prime256v1'});
  const otherKey=other.publicKey.export({type:'spki',format:'der'}).toString('base64');
  const db=database({key:otherKey});
  const x=await reserve(db);
  assert.equal(x.authorized,false);
  assert.equal(x.code,'INVALID_R2_IDENTITY_PROOF');
  assert.equal(db.queries.length,1);
});
test('missing bilateral mutual consent refuses before nonce insertion',async()=>{
  const db=database({bilateral:false});
  const x=await reserve(db);
  assert.equal(x.authorized,false);
  assert.equal(x.code,'NO_MUTUAL_R2_CONNECTION');
  assert.equal(db.queries.length,2);
});
test('nonce replay rejects reservation before any media changes',async()=>{
  const db=database({replay:true});
  const x=await reserve(db);
  assert.equal(x.authorized,false);
  assert.equal(x.code,'R2_PROOF_REPLAY');
  assert.equal(db.queries.length,3);
});
test('forged object key invalidates signature, no media row inserted',async()=>{
  const db=database();
  const wrong='pending/aa/bb/aabbccdd-aabb-4abb-8abb-aabbccddaabb';
  const x=await reserve(db,{objectKey:wrong,proof:proof()});
  assert.equal(x.authorized,false);
  assert.equal(x.code,'INVALID_R2_IDENTITY_PROOF');
  assert.equal(db.queries.length,1);
});
test('idempotent same-object replay cannot extend the original lease',async()=>{
  const db=database({collision:true});
  const x=await reserve(db);
  assert.equal(x.reserved,true);
  assert.equal(x.duplicate,true);
  assert.equal(db.queries.length,5);
  assert.match(db.queries[4].sql,/SELECT object_key/);
  assert.equal(db.queries[4].args[0],messageId);
});
test('expired lease cannot be refreshed by repeated signed reservation',async()=>{
  const db=database({collision:true,expired:true});
  const x=await reserve(db);
  assert.equal(x.reserved,false);
  assert.equal(x.code,'LEASE_EXPIRED');
});
test('invalid or ambiguous identities and keys are refused before SQL',async()=>{
  for(const changes of [
    {identity:'not-a-peer'},
    {peerIdentity:actor},
    {messageId:''},
    {objectKey:'../secret'}
  ]){
    const db=database();
    const x=await reserve(db,changes);
    assert.equal(x.authorized,false);
    assert.equal(db.queries.length,0);
  }
});
test('database outages throw, never fall back to local RAM',async()=>{
  const db=database({dbError:true});
  await assert.rejects(reserve(db),/POSTGRES_DOWN/);
});
