'use strict';
const test=require('node:test');
const assert=require('node:assert/strict');
const crypto=require('node:crypto');
const {canonicalR2Action}=require('../src/r2ActionAuth');
const {admitAuthenticatedR2Confirm}=require('../src/r2StagedConfirmAdmission');
const kp=crypto.generateKeyPairSync('ec',{namedCurve:'prime256v1'});
const publicKey=kp.publicKey.export({type:'spki',format:'der'}).toString('base64');
const identity=crypto.createHash('sha256').update(Buffer.from(publicKey,'base64'))
  .digest('hex').slice(0,16).toUpperCase();
const peerIdentity='FEDCBA9876543210';
const messageId='msg-30v-confirm';
const objectKey='pending/12/34/12345678-1234-4234-8234-123456789abc';
const contentSha256='a'.repeat(64),contentBytes=1234,generation=1;
const now=Date.parse('2026-11-22T20:00:00Z');
function proof(operation='R2_CONFIRM',override={}){
  const fields=['R2_RESERVE','R2_ISSUE_PUT'].includes(operation)
    ? {messageId,objectKey}
    : {messageId,objectKey,generation,contentSha256,contentBytes,...override};
  const timestamp=now,nonce=crypto.randomUUID();
  const signed=canonicalR2Action(operation,identity,peerIdentity,fields,timestamp,nonce);
  const signer=crypto.createSign('SHA256');
  signer.update(signed,'utf8');signer.end();
  return {v:1,publicKey,nonce,timestamp,
    signature:signer.sign(kp.privateKey).toString('base64')};
}
function harness(opts={}){
  const queries=[];
  const tx=async(sql,args=[])=>{
    queries.push({sql,args});
    if(opts.dbDown)throw Error('POSTGRES_DOWN');
    if(sql.includes('FROM public.identity_key_bindings'))
      return {rowCount:1,rows:[{public_key_spki:opts.otherKey||publicKey}]};
    if(sql.includes('FROM public.connections')){
      const rows=[{from_identity:identity,to_identity:peerIdentity,is_mutual:true},
        ...(opts.unilateral?[]:[{from_identity:peerIdentity,to_identity:identity,is_mutual:true}])];
      return {rowCount:rows.length,rows};
    }
    if(sql.includes('INSERT INTO public.r2_action_nonces'))
      return {rowCount:opts.replay?0:1,rows:opts.replay?[]:[{nonce:args[1]}]};
    if(sql.includes('FROM public.message_r2_objects')){
      const rows=opts.missing?[]:[{
        object_key:objectKey,message_id:messageId,
        sender_identity:identity,recipient_identity:peerIdentity,
        state:opts.state||'RESERVED',upload_generation:1,
        lease_active:opts.expired!==true
      }];
      return {rowCount:rows.length,rows};
    }
    if(sql.includes('INSERT INTO public.r2_verification_daily_budget'))
      return {rowCount:1,rows:[]};
    if(sql.includes('SELECT attempts,reserved_bytes'))
      return {rowCount:1,rows:[{attempts:opts.quotaFull?12:0,reserved_bytes:0}]};
    if(sql.includes('INSERT INTO public.r2_verification_claims'))
      return {rowCount:opts.busy?0:1,rows:opts.busy?[]:[{
        claim_id:'123e4567-e89b-42d3-a456-426614174000',
        lease_expires_at:'2026-11-22T20:03:00Z'
      }]};
    if(sql.includes('UPDATE public.r2_verification_daily_budget'))
      return {rowCount:1,rows:[{attempts:1,reserved_bytes:contentBytes}]};
    throw Error('UNEXPECTED_SQL: '+sql);
  };
  return {queries,withTransaction:async callback=>callback(tx)};
}
const args={identity,peerIdentity,messageId,objectKey,generation,
  contentSha256,contentBytes,now};
function run(db,changes={}){
  return admitAuthenticatedR2Confirm({
    ...args,withTransaction:db.withTransaction,proof:proof(),...changes
  });
}
test('P-256 confirm atomically records nonce, claim and verification quota after bilateral check',async()=>{
  const db=harness();const out=await run(db);
  assert.equal(out.authorized,true);
  assert.equal(out.granted,true);
  assert.match(out.claimId,/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i);
  assert.equal(out.expectedBytes,contentBytes);
  assert.equal(Object.isFrozen(out),true);
  assert.equal(db.queries.length,8);
  const sql=db.queries.map(x=>x.sql);
  assert.match(sql[0],/identity_key_bindings/);
  assert.match(sql[1],/FROM public.connections/);
  assert.match(sql[2],/r2_action_nonces/);
  assert.match(sql[3],/FOR UPDATE/);
  assert.match(sql[6],/r2_verification_claims/);
  assert.match(sql[7],/UPDATE public.r2_verification_daily_budget/);
});
test('R2_RESERVE proof never authorizes R2_CONFIRM, even for same object/message',async()=>{
  const db=harness();
  const otherProof=proof('R2_RESERVE');
  const out=await run(db,{proof:otherProof});
  assert.equal(out.authorized,false);
  assert.equal(out.code,'INVALID_R2_IDENTITY_PROOF');
  assert.equal(db.queries.length,1);
});
test('R2_ISSUE_PUT proof never authorizes R2_CONFIRM',async()=>{
  const db=harness();
  const out=await run(db,{proof:proof('R2_ISSUE_PUT')});
  assert.equal(out.authorized,false);
  assert.equal(out.code,'INVALID_R2_IDENTITY_PROOF');
  assert.equal(db.queries.length,1);
  assert.equal(db.queries.some(q=>q.sql.includes('r2_action_nonces')),false);
});

test('unilateral connection cannot claim or charge verification',async()=>{
  const db=harness({unilateral:true});const out=await run(db);
  assert.equal(out.code,'NO_MUTUAL_R2_CONNECTION');
  assert.equal(db.queries.length,2);
});
test('nonce replay denies before any verification claim or quota',async()=>{
  const db=harness({replay:true});const out=await run(db);
  assert.equal(out.code,'R2_PROOF_REPLAY');
  assert.equal(db.queries.length,3);
});
test('expired or terminal reservation cannot begin verification',async()=>{
  for(const opts of [{expired:true},{state:'UPLOADED'},{state:'ATTACHED'},{missing:true}]){
    const db=harness(opts);const out=await run(db);
    assert.equal(out.granted,false);
    assert.equal(db.queries.length,4);
  }
});
test('verification quota full denies before claim or R2 GET',async()=>{
  const db=harness({quotaFull:true});
  const out=await run(db);
  assert.equal(out.granted,false);
  assert.equal(out.statusCode,429);
  assert.equal(db.queries.some(x=>x.sql.includes('INSERT INTO public.r2_verification_claims')),false);
});
test('object single-flight refuses competing claim without additional quota charge',async()=>{
  const db=harness({busy:true});
  const out=await run(db);
  assert.equal(out.code,'R2_VERIFICATION_IN_PROGRESS');
  assert.equal(db.queries.some(x=>x.sql.includes('UPDATE public.r2_verification_daily_budget')),false);
});
test('malformed digest, bytes, generation or key never queries PostgreSQL',async()=>{
  for(const changes of [
    {contentSha256:'invalid'},{contentBytes:0},{generation:2},
    {objectKey:'../bad'},{messageId:''},{identity:'not-a-peer'}
  ]){
    const db=harness();const out=await run(db,changes);
    assert.equal(out.code,'INVALID_R2_CONFIRM_REQUEST');
    assert.equal(db.queries.length,0);
  }
});
test('db outage propagates; no RAM fallback and no false confirmation',async()=>{
  const db=harness({dbDown:true});
  await assert.rejects(run(db),/POSTGRES_DOWN/);
});
