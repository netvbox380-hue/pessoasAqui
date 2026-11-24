'use strict';
const test=require('node:test');
const assert=require('node:assert/strict');
const crypto=require('node:crypto');
const {canonicalR2Action}=require('../src/r2ActionAuth');
const {admitAuthenticatedAttemptConfirm}=
  require('../src/r2StagedAttemptConfirmAdmission');
const kp=crypto.generateKeyPairSync('ec',{namedCurve:'prime256v1'});
const publicKey=kp.publicKey.export({type:'spki',format:'der'}).toString('base64');
const identity=crypto.createHash('sha256').update(Buffer.from(publicKey,'base64'))
 .digest('hex').slice(0,16).toUpperCase();
const peerIdentity='1122334455667788';
const messageId='msg-30y-b';
const attemptObjectKey='pending/12/34/12345678-1234-4234-8234-123456789abc';
const parentKey='pending/aa/bb/aabbccdd-aabb-4abb-8abb-aabbccddaabb';
const contentSha256='a'.repeat(64),contentBytes=321,generation=1;
const now=Date.parse('2026-11-23T12:00:00Z');
function proof(op='R2_CONFIRM'){
 const nonce=crypto.randomUUID(),timestamp=now;
 const fields=op==='R2_CONFIRM'
   ?{messageId,objectKey:attemptObjectKey,generation,contentSha256,contentBytes}
   :{messageId,objectKey:attemptObjectKey};
 const signer=crypto.createSign('SHA256');
 signer.update(canonicalR2Action(op,identity,peerIdentity,fields,timestamp,nonce),'utf8');
 signer.end();
 return {v:1,timestamp,nonce,publicKey,
   signature:signer.sign(kp.privateKey).toString('base64')};
}
function db(opts={}){
 const calls=[];
 let commit=0,rollback=0;
 const tx=async(sql,args=[])=>{
   calls.push({sql,args});
   if(opts.pgDown)throw Error('PG_UNAVAILABLE');
   if(sql.includes('FROM public.identity_key_bindings'))
     return {rowCount:1,rows:[{public_key_spki:opts.badKey?'INVALID':publicKey}]};
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
       object_key:parentKey,
       sender_identity:opts.foreignParent?'FFFFFFFFFFFFFFFF':identity,
       recipient_identity:peerIdentity,
       state:opts.parentUploaded?'UPLOADED':'RESERVED',
       upload_generation:1,lease_active:!opts.leaseExpired
     }]};
   if(sql.includes('FROM public.message_r2_upload_attempts'))
     return {rowCount:opts.noAttempt?0:1,rows:opts.noAttempt?[]:[{
       object_key:attemptObjectKey,message_id:messageId,generation:2,
       sender_identity:opts.foreignAttempt?'FFFFFFFFFFFFFFFF':identity,
       recipient_identity:peerIdentity,
       state:opts.state||'PUT_ISSUED'
     }]};
   if(sql.includes('FROM public.r2_attempt_verification_claims')){
     if(!opts.existing)return {rowCount:0,rows:[]};
     return {rowCount:1,rows:[{
       claim_id:'123e4567-e89b-42d3-a456-426614174000',
       state:opts.claimState||'RUNNING',
       content_sha256:opts.conflictingHash?'b'.repeat(64):contentSha256,
       content_bytes:contentBytes,
       active:opts.active!==false
     }]};
   }
   if(sql.includes('INSERT INTO public.r2_verification_daily_budget'))
     return {rowCount:1,rows:[]};
   if(sql.includes('SELECT attempts,reserved_bytes'))
     return {rowCount:1,rows:[{
       attempts:opts.quotaExceeded?12:0,reserved_bytes:0
     }]};
   if(sql.includes('INSERT INTO public.r2_attempt_verification_claims'))
     return {rowCount:1,rows:[{
       claim_id:args[4],lease_expires_at:'2026-11-23T12:03:00Z'
     }]};
   if(sql.includes('UPDATE public.message_r2_upload_attempts'))
     return {rowCount:1,rows:[{object_key:attemptObjectKey}]};
   if(sql.includes('UPDATE public.r2_verification_daily_budget'))
     return {rowCount:1,rows:[{attempts:1,reserved_bytes:contentBytes}]};
   throw Error('UNEXPECTED_SQL '+sql);
 };
 const withTransaction=async cb=>{
   try{const x=await cb(tx);commit++;return x;}
   catch(e){rollback++;throw e;}
 };
 return {calls,withTransaction,get commit(){return commit;},
   get rollback(){return rollback;}};
}
function run(h,overrides={}){
 return admitAuthenticatedAttemptConfirm({
   withTransaction:h.withTransaction,
   identity,peerIdentity,messageId,attemptObjectKey,
   generation,contentSha256,contentBytes,proof:proof(),now,...overrides
 });
}
test('P-256 CONFIRM creates per-physical-key claim and charges budget atomically',async()=>{
 const h=db(),x=await run(h);
 assert.equal(x.authorized,true);
 assert.equal(x.granted,true);
 assert.equal(x.attemptGeneration,2);
 assert.equal(x.protocolGeneration,1);
 assert.equal(x.attemptObjectKey,attemptObjectKey);
 assert.equal(h.commit,1);
 const qs=h.calls.map(x=>x.sql);
 assert.ok(qs.findIndex(s=>s.includes('FROM public.message_r2_objects'))<
   qs.findIndex(s=>s.includes('FROM public.message_r2_upload_attempts')));
 assert.ok(qs.findIndex(s=>s.includes('FROM public.message_r2_upload_attempts'))<
   qs.findIndex(s=>s.includes('FROM public.r2_attempt_verification_claims')));
 assert.ok(qs.some(s=>s.includes('INSERT INTO public.r2_attempt_verification_claims')));
 assert.ok(qs.some(s=>s.includes('UPDATE public.message_r2_upload_attempts')));
 assert.ok(qs.some(s=>s.includes('UPDATE public.r2_verification_daily_budget')));
 assert.equal(qs.some(s=>s.includes('SET state=\'UPLOADED\'')),false);
});
test('RESERVE and ISSUE_PUT signatures cannot authorize CONFIRM',async()=>{
 for(const op of ['R2_RESERVE','R2_ISSUE_PUT']){
   const h=db(),x=await run(h,{proof:proof(op)});
   assert.equal(x.authorized,false);
   assert.equal(x.code,'INVALID_R2_IDENTITY_PROOF');
   assert.equal(h.calls.length,1);
 }
});
test('invalid key binding, unilateral pair and replay stop before claim',async()=>{
 for(const o of [{badKey:true},{unilateral:true},{replay:true}]){
   const h=db(o),x=await run(h);
   assert.equal(x.granted,false);
   assert.equal(h.calls.some(q=>q.sql.includes('INSERT INTO public.r2_attempt_verification_claims')),false);
 }
});
test('parent and attempt must match owner and be active',async()=>{
 for(const o of [{noParent:true},{foreignParent:true},
   {parentUploaded:true},{leaseExpired:true},{noAttempt:true},
   {foreignAttempt:true},{state:'ABANDONED'},{state:'VERIFIED'}]){
   const h=db(o),x=await run(h);
   assert.equal(x.granted,false);
   assert.equal(h.calls.some(q=>q.sql.includes('INSERT INTO public.r2_attempt_verification_claims')),false);
 }
});
test('live claim blocks re-admission without extra egress quota',async()=>{
 const h=db({existing:true}),x=await run(h);
 assert.equal(x.granted,false);
 assert.equal(x.code,'R2_ATTEMPT_CONFIRM_IN_PROGRESS');
 assert.equal(h.calls.some(q=>q.sql.includes('r2_verification_daily_budget')),false);
});
test('expired claim with same descriptor can retry but cannot silently change hash',async()=>{
 const good=db({existing:true,active:false,state:'VERIFYING'});
 assert.equal((await run(good)).granted,true);
 assert.equal(good.calls.some(q=>q.sql.includes('UPDATE public.message_r2_upload_attempts')),false);
 const bad=db({existing:true,active:false,conflictingHash:true,state:'VERIFYING'});
 assert.equal((await run(bad)).code,'R2_ATTEMPT_DESCRIPTOR_CONFLICT');
});
test('quota exhausted denies before claim and attempt state transition',async()=>{
 const h=db({quotaExceeded:true}),x=await run(h);
 assert.equal(x.granted,false);
 assert.equal(x.statusCode,429);
 assert.equal(h.calls.some(q=>q.sql.includes('INSERT INTO public.r2_attempt_verification_claims')),false);
});
test('malformed inputs never reach SQL',async()=>{
 for(const overrides of [{identity:'bad'},{attemptObjectKey:'../bad'},
   {generation:2},{contentSha256:'x'},{contentBytes:0},{messageId:''}]){
   const h=db(),x=await run(h,overrides);
   assert.equal(x.granted,false);
   assert.equal(h.calls.length,0);
 }
});
test('database outage fails closed, never returns a grant',async()=>{
 const h=db({pgDown:true});
 await assert.rejects(run(h),/PG_UNAVAILABLE/);
 assert.equal(h.rollback,1);
});
