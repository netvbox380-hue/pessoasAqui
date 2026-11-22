'use strict';
const test=require('node:test');
const assert=require('node:assert/strict');
const crypto=require('node:crypto');
const {canonicalR2Action}=require('../src/r2ActionAuth');
const {issueAuthenticatedR2Put}=require('../src/r2StagedPutIssuance');

const pair=crypto.generateKeyPairSync('ec',{namedCurve:'prime256v1'});
const publicKey=pair.publicKey.export({type:'spki',format:'der'}).toString('base64');
const identity=crypto.createHash('sha256').update(Buffer.from(publicKey,'base64'))
  .digest('hex').slice(0,16).toUpperCase();
const peerIdentity='FEDCBA9876543210';
const objectKey='pending/12/34/12345678-1234-4234-8234-123456789abc';
const messageId='msg-stage-30t';
const bucket='pessoasaqui-media';
const now=Date.parse('2026-11-22T20:00:00Z');
function makeProof(opts={}){
  const timestamp=opts.timestamp??now,nonce=opts.nonce??crypto.randomUUID();
  const operation=opts.operation??'R2_ISSUE_PUT';
  const fields={messageId,objectKey};
  const signer=crypto.createSign('SHA256');
  signer.update(canonicalR2Action(operation,identity,peerIdentity,
    fields,timestamp,nonce),'utf8');signer.end();
  return {v:1,timestamp,nonce,publicKey,
    signature:signer.sign(pair.privateKey).toString('base64')};
}
function signedURL(){
  const u=new URL('https://'+'a'.repeat(32)+'.r2.cloudflarestorage.com/'+bucket+'/'+objectKey);
  for(const [k,v] of Object.entries({
    'X-Amz-Algorithm':'AWS4-HMAC-SHA256',
    'X-Amz-Expires':'900',
    'X-Amz-SignedHeaders':'content-type;host;if-none-match',
    'X-Amz-Signature':'f'.repeat(64),
    'X-Amz-Credential':'test',
    'X-Amz-Date':'20261122T200000Z'
  }))u.searchParams.set(k,v);
  return u.toString();
}
function harness({mutual=true,active=true,sealed=false,missing=false,
  bogusKey=false,replay=false,signingFails=false}={}){
  const calls=[];
  let committed=false,rolledBack=false,signs=0;
  const tx=async(sql,args=[])=>{
    calls.push({sql,args});
    if(sql.includes('FROM public.identity_key_bindings'))
      return {rowCount:1,rows:[{public_key_spki:bogusKey?'bogus':publicKey}]};
    if(sql.includes('FROM public.connections')){
      const rows=[{from_identity:identity,to_identity:peerIdentity,is_mutual:true},
        ...(mutual?[{from_identity:peerIdentity,to_identity:identity,is_mutual:true}]:[])];
      return {rowCount:rows.length,rows};
    }
    if(sql.includes('INSERT INTO public.r2_action_nonces'))
      return {rowCount:replay?0:1,rows:replay?[]:[{nonce:args[1]}]};
    if(sql.includes('FROM public.message_r2_objects'))
      return {rowCount:missing?0:1,rows:missing?[]:[{
        object_key:objectKey,message_id:messageId,sender_identity:identity,
        recipient_identity:peerIdentity,state:'RESERVED',
        upload_generation:1,lease_active:active
      }]};
    if(sql.includes('SELECT sealed_at FROM public.r2_put_capability_windows'))
      return {rowCount:sealed?1:0,rows:sealed?[{sealed_at:new Date(now)}]:[]};
    if(sql.includes('SELECT clock_timestamp() AS now'))
      return {rowCount:1,rows:[{now:new Date(now)}]};
    if(sql.includes('INSERT INTO public.r2_put_capability_windows'))
      return {rowCount:1,rows:[{object_key:objectKey}]};
    throw Error('Unexpected SQL');
  };
  const withTransaction=async fn=>{
    try{const val=await fn(tx);committed=true;return val;}
    catch(e){rolledBack=true;throw e;}
  };
  const signPutUrl=async args=>{
    signs++;
    if(signingFails)throw Error('SIGNER_UNAVAILABLE');
    assert.equal(args.method,'PUT');
    assert.equal(args.headers['If-None-Match'],'*');
    return signedURL();
  };
  return {calls,withTransaction,signPutUrl,
    get committed(){return committed;},get rolledBack(){return rolledBack;},
    get signs(){return signs;}};
}
function issue(h,changes={}){
  return issueAuthenticatedR2Put({
    withTransaction:h.withTransaction,signPutUrl:h.signPutUrl,
    identity,peerIdentity,messageId,objectKey,proof:makeProof(),bucket,now,
    ...changes
  });
}
test('signed P-256 proof and active bilateral connection precede PUT signer',async()=>{
  const h=harness();
  const result=await issue(h);
  assert.equal(result.authorized,true);assert.equal(result.issued,true);
  assert.equal(result.method,'PUT');
  assert.equal(result.capability.requiredHeaders['If-None-Match'],'*');
  assert.equal(result.capability.expiresIn,900);
  assert.equal(h.committed,true);
  const sql=h.calls.map(x=>x.sql);
  assert.ok(sql.findIndex(x=>x.includes('FROM public.connections'))<
    sql.findIndex(x=>x.includes('FROM public.message_r2_objects')));
  assert.ok(sql.findIndex(x=>x.includes('FROM public.message_r2_objects'))<
    sql.findIndex(x=>x.includes('INSERT INTO public.r2_put_capability_windows')));
  assert.equal(h.signs,1);
});
test('unilateral connection refuses before nonce or signing',async()=>{
  const h=harness({mutual:false});const x=await issue(h);
  assert.equal(x.authorized,false);assert.equal(h.signs,0);
  assert.equal(h.calls.some(x=>x.sql.includes('r2_action_nonces')),false);
});
test('wrong P-256 binding refuses before any storage capability',async()=>{
  const h=harness({bogusKey:true});const x=await issue(h);
  assert.equal(x.authorized,false);assert.equal(h.signs,0);
});
test('replayed nonce refuses without signing',async()=>{
  const h=harness({replay:true});const x=await issue(h);
  assert.equal(x.code,'R2_PROOF_REPLAY');assert.equal(h.signs,0);
});
test('expired lease never issues PUT URL',async()=>{
  const h=harness({active:false});const x=await issue(h);
  assert.equal(x.issued,false);assert.equal(x.code,'R2_PUT_LEASE_OR_STATE_INVALID');
  assert.equal(h.signs,0);
});
test('missing reservation never issues PUT URL',async()=>{
  const h=harness({missing:true});const x=await issue(h);
  assert.equal(x.issued,false);assert.equal(x.code,'R2_PUT_RESERVATION_NOT_FOUND');
  assert.equal(h.signs,0);
});
test('sealed capability key fails and requires transaction rollback',async()=>{
  const h=harness({sealed:true});
  await assert.rejects(issue(h),/R2_PUT_KEY_RETIRED/);
  assert.equal(h.rolledBack,true);assert.equal(h.signs,0);
});
test('signer failure rolls back the nonce and leaves no capability',async()=>{
  const h=harness({signingFails:true});
  await assert.rejects(issue(h),/SIGNER_UNAVAILABLE/);
  assert.equal(h.rolledBack,true);
  assert.equal(h.calls.some(x=>x.sql.includes('INSERT INTO public.r2_put_capability_windows')),false);
});
test('invalid identifiers and bucket fail without starting SQL',async()=>{
  for(const changes of [
    {identity:'forged'},{objectKey:'../x'},{messageId:''},{bucket:'../bad'},
    {peerIdentity:identity}
  ]){
    const h=harness();
    const x=changes.bucket?issue(h,changes):await issue(h,changes);
    if(changes.bucket)await assert.rejects(x,/Trusted R2 PUT/);
    else assert.equal(x.authorized,false);
    assert.equal(h.calls.length,0);
  }
});

test('R2_RESERVE signature cannot authorize R2_ISSUE_PUT even with matching fields',async()=>{
  const h=harness();
  const original=makeProof({operation:'R2_RESERVE'});
  const out=await issue(h,{proof:original});
  assert.equal(out.authorized,false);
  assert.equal(out.code,'INVALID_R2_IDENTITY_PROOF');
  assert.equal(h.signs,0);
  assert.equal(h.calls.some(x=>x.sql.includes('r2_action_nonces')),false);
  assert.equal(h.calls.some(x=>x.sql.includes('r2_put_capability_windows')),false);
});
test('R2_ISSUE_PUT proof cannot be repurposed as a signed reservation',()=>{
  const issueProof=makeProof();
  const {verifyR2ActionProof}=require('../src/r2ActionAuth');
  const fields={messageId,objectKey};
  assert.equal(verifyR2ActionProof({
    operation:'R2_RESERVE',identity,peerIdentity,fields,
    proof:issueProof,activePublicKey:publicKey,now
  }),false);
  assert.equal(verifyR2ActionProof({
    operation:'R2_ISSUE_PUT',identity,peerIdentity,fields,
    proof:issueProof,activePublicKey:publicKey,now
  }),true);
});
test('domain-separated canonical payload differs for same key, actor, nonce and timestamp',()=>{
  const nonce=crypto.randomUUID();
  const fields={messageId,objectKey};
  const reserve=canonicalR2Action('R2_RESERVE',identity,peerIdentity,fields,now,nonce);
  const issue=canonicalR2Action('R2_ISSUE_PUT',identity,peerIdentity,fields,now,nonce);
  assert.notEqual(reserve,issue);
  assert.match(issue,/R2_ISSUE_PUT/);
});
