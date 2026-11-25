'use strict';
const test=require('node:test');
const assert=require('node:assert/strict');
const crypto=require('node:crypto');
const {canonicalR2Action}=require('../src/r2ActionAuth');
const {attachAuthenticatedWinningR2Media}=
 require('../src/r2StagedWinningMediaAttach');
const keys=crypto.generateKeyPairSync('ec',{namedCurve:'prime256v1'});
const publicKey=keys.publicKey.export({type:'spki',format:'der'}).toString('base64');
const identity=crypto.createHash('sha256').update(Buffer.from(publicKey,'base64'))
 .digest('hex').slice(0,16).toUpperCase();
const peerIdentity='1122334455667788',messageId='msg-30y-d1';
const objectKey='pending/12/34/12345678-1234-4234-8234-123456789abc';
const legacyKey='pending/aa/bb/aabbccdd-aabb-4abb-8abb-aabbccddaabb';
const ciphertextPayload='ENC_ECDH:some-opaque-encrypted-descriptor';
const ciphertextDigest=crypto.createHash('sha256')
 .update(ciphertextPayload,'utf8').digest('hex');
const contentSha256='a'.repeat(64),contentBytes=500,generation=1;
const senderAlias='Sender',ivNonce='encrypted-iv';
const now=Date.parse('2026-11-24T12:00:00Z');
function makeProof(op='R2_ATTACH',override={}){
 const nonce=crypto.randomUUID();
 const fields={messageId,objectKey,generation,contentSha256,
   contentBytes,ciphertextDigest};
 const signed=crypto.createSign('SHA256');
 signed.update(canonicalR2Action(op,identity,peerIdentity,
   op==='R2_ATTACH'?{...fields,...override}:
     {messageId,objectKey},now,nonce),'utf8');
 signed.end();
 return {v:1,timestamp:now,nonce,publicKey,
   signature:signed.sign(keys.privateKey).toString('base64')};
}
function mock(o={}){
 const calls=[];
 let commits=0,rollbacks=0;
 const tx=async(s,args=[])=>{
   calls.push({s,args});
   if(o.dbDown)throw Error('PG_DOWN');
   if(s.includes('FROM public.identity_key_bindings'))
     return {rowCount:1,rows:[{public_key_spki:o.rotated?'BAD':publicKey}]};
   if(s.includes('FROM public.connections')){
     const rows=[{from_identity:identity,to_identity:peerIdentity,is_mutual:true}];
     if(!o.unilateral)rows.push({
       from_identity:peerIdentity,to_identity:identity,is_mutual:true
     });
     return {rowCount:rows.length,rows};
   }
   if(s.includes('INSERT INTO public.r2_action_nonces'))
     return {rowCount:o.replay?0:1,rows:o.replay?[]:[{nonce:args[1]}]};
   if(s.includes('FROM public.message_r2_objects'))
     return {rowCount:1,rows:[{
       object_key:legacyKey,sender_identity:identity,
       recipient_identity:peerIdentity,state:o.attached?'ATTACHED':'RESERVED',
       upload_generation:1,lease_active:!o.expired
     }]};
   if(s.includes('FROM public.message_r2_winning_attempts'))
     return {rowCount:o.notWinner?0:1,rows:o.notWinner?[]:[{
       object_key:objectKey,message_id:messageId,
       sender_identity:identity,recipient_identity:peerIdentity,
       generation:2,claim_id:'123e4567-e89b-42d3-a456-426614174000',
       content_sha256:o.wrongHash?'b'.repeat(64):contentSha256,
       content_bytes:contentBytes,
       attempt_state:o.attemptBad?'ABANDONED':'VERIFIED',
       claim_state:o.claimBad?'RUNNING':'COMPLETED',
       completed_at:'2026-11-24T12:00:00Z'
     }]};
   if(s.includes('FROM public.message_delivery_receipts'))
     return {rowCount:o.terminal?1:0,rows:o.terminal?[{state:'DELIVERED'}]:[]};
   if(s.includes('FROM public.e2ee_messages'))
     return {rowCount:o.existing?1:0,rows:o.existing?[{
       sender_identity:identity,recipient_identity:peerIdentity,
       sender_alias:senderAlias,
       ciphertext_payload:o.collision?'ENC_ECDH:different':ciphertextPayload,
       iv_nonce:ivNonce,delivery_state:'PENDING',is_cancelled:false,
       is_delivered:false,r2_winning_object_key:objectKey
     }]:[]};
   if(s.includes('INSERT INTO public.e2ee_messages'))
     return {rowCount:o.insertFail?0:1,rows:[{id:messageId}]};
   if(s.includes('UPDATE public.message_r2_objects'))
     return {rowCount:o.updateFail?0:1,rows:[{message_id:messageId}]};
   throw Error('UNEXPECTED SQL '+s);
 };
 const withTransaction=async cb=>{
   try{const out=await cb(tx);commits++;return out;}
   catch(e){rollbacks++;throw e;}
 };
 return {calls,withTransaction,get commits(){return commits;},
   get rollbacks(){return rollbacks;}};
}
function attach(m,options={}){
 return attachAuthenticatedWinningR2Media({
   withTransaction:m.withTransaction,identity,peerIdentity,messageId,
   objectKey,generation,contentSha256,contentBytes,ciphertextDigest,
   ciphertextPayload,ivNonce,senderAlias,proof:makeProof(),now,...options
 });
}
test('winner physical key and envelope are inserted under same transaction as parent ATTACHED',async()=>{
 const m=mock(),r=await attach(m);
 assert.equal(r.success,true);
 assert.equal(r.duplicate,false);
 assert.equal(r.objectKey,objectKey);
 assert.equal(m.commits,1);
 const q=m.calls.map(x=>x.s);
 const p=q.findIndex(s=>s.includes('FROM public.message_r2_objects'));
 const w=q.findIndex(s=>s.includes('FROM public.message_r2_winning_attempts'));
 const i=q.findIndex(s=>s.includes('INSERT INTO public.e2ee_messages'));
 const u=q.findIndex(s=>s.includes('UPDATE public.message_r2_objects'));
 assert.ok(p>=0&&w>p&&i>w&&u>i);
 assert.equal(m.calls[i].args.at(-1),objectKey);
 assert.match(q[p],/FOR UPDATE/);
 assert.match(q[w],/FOR SHARE OF w,a,c/);
});
test('idempotent exact retry preserves timestamps and avoids writes',async()=>{
 const m=mock({existing:true,attached:true});
 const r=await attach(m);
 assert.equal(r.success,true);
 assert.equal(r.duplicate,true);
 assert.equal(m.calls.some(x=>x.s.includes('UPDATE public.message_r2_objects')),false);
 assert.equal(m.calls.some(x=>x.s.includes('INSERT INTO public.e2ee_messages')),false);
});
test('wrong winner and unverified attempt or claim never attach',async()=>{
 for(const opts of [{notWinner:true},{wrongHash:true},
   {attemptBad:true},{claimBad:true}]){
   const m=mock(opts),r=await attach(m);
   assert.equal(r.success,false);
   assert.equal(m.calls.some(x=>x.s.includes('INSERT INTO public.e2ee_messages')),false);
 }
});
test('signed R2_ATTACH cannot authorize a changed descriptor or hash',async()=>{
 const m=mock();
 const r=await attach(m,{ciphertextPayload:'ENC_ECDH:swapped-descriptor'});
 assert.equal(r.success,false);
 assert.equal(m.calls.length,0);
});
test('cross operation signatures, unilateral pair and rotated key deny',async()=>{
 for(const opts of [{unilateral:true},{rotated:true},{replay:true}]){
   const m=mock(opts),r=await attach(m);
   assert.equal(r.success,false);
   assert.equal(m.calls.some(x=>x.s.includes('FROM public.message_r2_objects')),false);
 }
 const m=mock();
 const invalid=await attach(m,{proof:makeProof('R2_ISSUE_PUT')});
 assert.equal(invalid.success,false);
 assert.equal(m.calls.some(x=>x.s.includes('FROM public.message_r2_objects')),false);
});
test('terminal receipt or different existing payload blocks resurrection',async()=>{
 for(const opts of [{terminal:true},{collision:true,existing:true},
   {existing:true},{expired:true}]){
   const m=mock(opts),r=await attach(m);
   assert.equal(r.success,false);
   assert.equal(m.calls.some(x=>x.s.includes('INSERT INTO public.e2ee_messages')),false);
 }
});
test('message insert or parent state failure causes rollback',async()=>{
 for(const opts of [{insertFail:true},{updateFail:true},{dbDown:true}]){
   const m=mock(opts);
   await assert.rejects(attach(m));
   assert.equal(m.rollbacks,1);
 }
});
test('invalid object key, digest, nonce or size never opens database',async()=>{
 for(const changes of [{objectKey:'../wrong'},{contentBytes:0},
   {ivNonce:'invalid\nnonce'},{ciphertextDigest:'b'.repeat(64)}]){
   const m=mock(),r=await attach(m,changes);
   assert.equal(r.success,false);
   assert.equal(m.calls.length,0);
 }
});
