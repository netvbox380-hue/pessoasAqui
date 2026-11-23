'use strict';
const test=require('node:test');
const assert=require('node:assert/strict');
const crypto=require('node:crypto');
const {canonicalR2Action}=require('../src/r2ActionAuth');
const {issueAuthenticatedAttemptPut}=require('../src/r2StagedAttemptPutIssuance');

const kp=crypto.generateKeyPairSync('ec',{namedCurve:'prime256v1'});
const pub=kp.publicKey.export({type:'spki',format:'der'}).toString('base64');
const identity=crypto.createHash('sha256').update(Buffer.from(pub,'base64'))
  .digest('hex').slice(0,16).toUpperCase();
const peerIdentity='1122334455667788';
const messageId='msg-30y-a';
const attemptObjectKey='pending/12/34/12345678-1234-4234-8234-123456789abc';
const legacyKey='pending/aa/bb/aabbccdd-aabb-4abb-8abb-aabbccddaabb';
const bucket='pessoasaqui-media';
const now=Date.parse('2026-11-23T12:00:00Z');
function signedProof(operation='R2_ISSUE_PUT'){
  const nonce=crypto.randomUUID();
  const fields={messageId,objectKey:attemptObjectKey};
  const sign=crypto.createSign('SHA256');
  sign.update(canonicalR2Action(operation,identity,peerIdentity,
    fields,now,nonce),'utf8');sign.end();
  return {v:1,timestamp:now,nonce,publicKey:pub,
    signature:sign.sign(kp.privateKey).toString('base64')};
}
function signedUrl(){
  const u=new URL('https://'+'a'.repeat(32)+'.r2.cloudflarestorage.com/'
    +bucket+'/'+attemptObjectKey);
  for(const [k,v] of Object.entries({
    'X-Amz-Algorithm':'AWS4-HMAC-SHA256',
    'X-Amz-Expires':'900',
    'X-Amz-SignedHeaders':'content-type;host;if-none-match',
    'X-Amz-Signature':'f'.repeat(64),'X-Amz-Credential':'mock',
    'X-Amz-Date':'20261123T120000Z'
  }))u.searchParams.set(k,v);
  return u.toString();
}
function db(opts={}){
  const calls=[];
  let commits=0,rollbacks=0,signs=0;
  const tx=async(sql,args=[])=>{
    calls.push({sql,args});
    if(opts.dbDown)throw Error('PG_DOWN');
    if(sql.includes('FROM public.identity_key_bindings'))
      return {rowCount:1,rows:[{public_key_spki:opts.badBinding?'invalid':pub}]};
    if(sql.includes('FROM public.connections')){
      const rows=[{from_identity:identity,to_identity:peerIdentity,is_mutual:true}];
      if(!opts.unilateral)rows.push({
        from_identity:peerIdentity,to_identity:identity,is_mutual:true
      });
      return {rowCount:rows.length,rows};
    }
    if(sql.includes('INSERT INTO public.r2_action_nonces'))
      return {rowCount:opts.replay?0:1,rows:opts.replay?[]:[{nonce:args[1]}]};
    if(sql.includes('FROM public.message_r2_objects'))
      return {rowCount:opts.noParent?0:1,rows:opts.noParent?[]:[{
        object_key:legacyKey,message_id:messageId,
        sender_identity:opts.wrongParent?'FEDCBA9876543210':identity,
        recipient_identity:peerIdentity,
        state:opts.terminal?'UPLOADED':'RESERVED',
        upload_generation:1,lease_active:!opts.expired
      }]};
    if(sql.includes('FROM public.message_r2_upload_attempts'))
      return {rowCount:opts.noAttempt?0:1,rows:opts.noAttempt?[]:[{
        object_key:attemptObjectKey,message_id:messageId,
        generation:2,sender_identity:opts.wrongAttempt?'FEDCBA9876543210':identity,
        recipient_identity:peerIdentity,
        state:opts.attemptState||'ALLOCATED'
      }]};
    if(sql.includes('SELECT sealed_at FROM public.r2_put_capability_windows'))
      return {rowCount:opts.sealed?1:0,
        rows:opts.sealed?[{sealed_at:'2026-11-23T12:00:00Z'}]:[]};
    if(sql.includes('SELECT clock_timestamp() AS now'))
      return {rowCount:1,rows:[{now:new Date(now)}]};
    if(sql.includes('INSERT INTO public.r2_put_capability_windows'))
      return {rowCount:1,rows:[{object_key:attemptObjectKey}]};
    if(sql.includes('UPDATE public.message_r2_upload_attempts'))
      return {rowCount:1,rows:[{object_key:attemptObjectKey}]};
    throw Error('UNEXPECTED SQL '+sql);
  };
  const withTransaction=async fn=>{
    try{const out=await fn(tx);commits++;return out;}
    catch(e){rollbacks++;throw e;}
  };
  const signPutUrl=async ({method,objectKey,headers})=>{
    signs++;
    if(opts.signFails)throw Error('SIGNING_DOWN');
    assert.equal(method,'PUT');
    assert.equal(objectKey,attemptObjectKey);
    assert.equal(headers['If-None-Match'],'*');
    return signedUrl();
  };
  return {calls,withTransaction,signPutUrl,
    get commits(){return commits;},get rollbacks(){return rollbacks;},
    get signs(){return signs;}};
}
function issue(h,changes={}){
  return issueAuthenticatedAttemptPut({
    withTransaction:h.withTransaction,
    signPutUrl:h.signPutUrl,identity,peerIdentity,messageId,
    attemptObjectKey,proof:signedProof(),bucket,now,...changes
  });
}
test('physical key PUT requires parent and attempt locks and seals expiry before commit',async()=>{
  const h=db();
  const x=await issue(h);
  assert.equal(x.authorized,true);
  assert.equal(x.issued,true);
  assert.equal(x.attemptGeneration,2);
  assert.equal(x.attemptObjectKey,attemptObjectKey);
  assert.equal(x.capability.requiredHeaders['If-None-Match'],'*');
  assert.equal(h.commits,1);
  const s=h.calls.map(x=>x.sql);
  const parent=s.findIndex(x=>x.includes('FROM public.message_r2_objects'));
  const attempt=s.findIndex(x=>x.includes('FROM public.message_r2_upload_attempts'));
  const ledger=s.findIndex(x=>x.includes('INSERT INTO public.r2_put_capability_windows'));
  const state=s.findIndex(x=>x.includes('UPDATE public.message_r2_upload_attempts'));
  assert.ok(parent>0&&attempt>parent&&ledger>attempt&&state>ledger);
  assert.match(s[parent],/FOR UPDATE/);
  assert.match(s[attempt],/FOR UPDATE/);
  assert.equal(h.signs,1);
});
test('signature of R2_RESERVE cannot grant physical PUT capability',async()=>{
  const h=db();
  const x=await issue(h,{proof:signedProof('R2_RESERVE')});
  assert.equal(x.authorized,false);
  assert.equal(x.code,'INVALID_R2_IDENTITY_PROOF');
  assert.equal(h.signs,0);
  assert.equal(h.calls.length,1);
});
test('unilateral pair, rotated key and nonce replay never sign',async()=>{
  for(const opts of [{unilateral:true},{badBinding:true},{replay:true}]){
    const h=db(opts);const x=await issue(h);
    assert.equal(x.authorized,false);
    assert.equal(h.signs,0);
    assert.equal(h.calls.some(x=>x.sql.includes('r2_put_capability_windows')),false);
  }
});
test('inactive logical message or foreign ownership does not sign',async()=>{
  for(const opts of [{noParent:true},{wrongParent:true},{expired:true},
    {terminal:true}]){
    const h=db(opts);const x=await issue(h);
    assert.equal(x.issued,false);
    assert.equal(h.signs,0);
  }
});
test('missing/foreign/terminal physical attempt cannot issue PUT',async()=>{
  for(const opts of [{noAttempt:true},{wrongAttempt:true},
    {attemptState:'VERIFYING'},{attemptState:'VERIFIED'},
    {attemptState:'ABANDONED'}]){
    const h=db(opts);const x=await issue(h);
    assert.equal(x.issued,false);
    assert.equal(h.signs,0);
  }
});
test('idempotent new-signature issuance does not regress PUT_ISSUED state',async()=>{
  const h=db({attemptState:'PUT_ISSUED'});
  const x=await issue(h);
  assert.equal(x.issued,true);
  assert.equal(h.calls.some(x=>x.sql.includes('UPDATE public.message_r2_upload_attempts')),false);
});
test('retired attempt key and signer outages roll back and never publish URL',async()=>{
  for(const opts of [{sealed:true},{signFails:true}]){
    const h=db(opts);
    await assert.rejects(issue(h));
    assert.equal(h.rollbacks,1);
  }
});
test('malformed key, actor and message are denied before SQL',async()=>{
  for(const changes of [{identity:'fake'},{peerIdentity:identity},
    {messageId:''},{attemptObjectKey:'../../oops'}]){
    const h=db();
    const x=await issue(h,changes);
    assert.equal(x.issued,false);
    assert.equal(h.calls.length,0);
  }
});
test('a backend database outage is fail-closed',async()=>{
  const h=db({dbDown:true});
  await assert.rejects(issue(h),/PG_DOWN/);
  assert.equal(h.rollbacks,1);
});
