'use strict';
const test=require('node:test');
const assert=require('node:assert/strict');
const {
  preflightAttemptWinner,settleVerifiedAttemptWinner
}=require('../src/r2StagedAttemptWinnerFinalizer');

const t=Object.freeze({
  authorized:true,granted:true,identity:'AAAABBBBCCCCDDDD',
  peerIdentity:'1111222233334444',
  messageId:'msg-winner',
  attemptObjectKey:'pending/12/34/12345678-1234-4234-8234-123456789abc',
  attemptGeneration:2,protocolGeneration:1,
  expectedSha256:'b'.repeat(64),expectedBytes:1234,
  claimId:'123e4567-e89b-42d3-a456-426614174000'
});
const key='ACTIVE_TEST_KEY';
const verified=Object.freeze({
  verified:true,objectKey:t.attemptObjectKey,
  bytes:t.expectedBytes,sha256:t.expectedSha256
});
function mock(o={}){
 const sql=[];let rolled=0,committed=0;
 let state={sealed:false,claim:'RUNNING',attempt:'VERIFYING',winner:null};
 const query=async(s,args=[])=>{
   sql.push({s,args});
   if(o.dbDown)throw Error('PG_DOWN');
   if(s.includes('FROM public.identity_key_bindings'))
     return {rowCount:1,rows:[{public_key_spki:o.rotated?'ROTATED':key}]};
   if(s.includes('FROM public.connections')){
     const rows=[{from_identity:t.identity,to_identity:t.peerIdentity,is_mutual:true}];
     if(!o.revoked)rows.push({from_identity:t.peerIdentity,to_identity:t.identity,is_mutual:true});
     return {rowCount:rows.length,rows};
   }
   if(s.includes('FROM public.message_r2_objects'))
     return {rowCount:o.noParent?0:1,rows:o.noParent?[]:[{
       object_key:'pending/aa/bb/aabbccdd-aabb-4abb-8abb-aabbccddaabb',
       sender_identity:t.identity,recipient_identity:t.peerIdentity,
       state:o.parentTerminal?'ATTACHED':'RESERVED',
       upload_generation:1,lease_active:!o.expired
     }]};
   if(s.includes('FROM public.message_r2_upload_attempts'))
     return {rowCount:o.noAttempt?0:1,rows:o.noAttempt?[]:[{
       object_key:t.attemptObjectKey,message_id:t.messageId,generation:2,
       sender_identity:t.identity,recipient_identity:t.peerIdentity,
       state:o.attemptAbandoned?'ABANDONED':state.attempt
     }]};
   if(s.includes('FROM public.r2_attempt_verification_claims'))
     return {rowCount:o.noClaim?0:1,rows:o.noClaim?[]:[{
       claim_id:t.claimId,message_id:t.messageId,identity_id:t.identity,
       generation:2,content_sha256:t.expectedSha256,
       content_bytes:t.expectedBytes,
       state:state.claim,active:!o.claimExpired
     }]};
   if(s.includes('FROM public.message_r2_winning_attempts'))
     return {rowCount:o.winnerExists?1:0,
       rows:o.winnerExists?[{object_key:'other'}]:[]};
   if(s.includes('UPDATE public.r2_put_capability_windows')){
     state.sealed=true;return {rowCount:1,rows:[{sealed_at:'2026-11-23T21:00:00Z'}]};
   }
   if(s.includes('FROM public.r2_put_capability_windows'))
     return {rowCount:o.windowActive?0:1,
       rows:o.windowActive?[]:[{object_key:t.attemptObjectKey}]};
   if(s.includes('UPDATE public.r2_attempt_verification_claims')){
     if(o.settleFails)return {rowCount:0,rows:[]};
     state.claim='COMPLETED';return {rowCount:1,rows:[{claim_id:t.claimId}]};
   }
   if(s.includes('UPDATE public.message_r2_upload_attempts')){
     if(o.attemptFails)return {rowCount:0,rows:[]};
     state.attempt='VERIFIED';return {rowCount:1,rows:[{object_key:t.attemptObjectKey}]};
   }
   if(s.includes('INSERT INTO public.message_r2_winning_attempts')){
     if(o.winnerFails)return {rowCount:0,rows:[]};
     state.winner=t.attemptObjectKey;return {rowCount:1,rows:[{message_id:t.messageId}]};
   }
   throw Error('UNEXPECTED '+s);
 };
 const withTransaction=async cb=>{
   const before={...state};
   try{const x=await cb(query);committed++;return x;}
   catch(e){rolled++;state=before;throw e;}
 };
 return {withTransaction,sql,get state(){return {...state};},
   get rolled(){return rolled;},get committed(){return committed;}};
}
const pre=(m,extra={})=>preflightAttemptWinner({
  withTransaction:m.withTransaction,ticket:t,activePublicKey:key,...extra
});
const fin=(m,extra={})=>settleVerifiedAttemptWinner({
  withTransaction:m.withTransaction,ticket:t,activePublicKey:key,
  verification:verified,...extra
});
test('preflight locks parent, physical attempt and claim, then seals physical PUT',async()=>{
 const m=mock(),res=await pre(m);
 assert.equal(res.ready,true);
 assert.equal(m.state.sealed,true);
 const s=m.sql.map(x=>x.s);
 const a=s.findIndex(x=>x.includes('FROM public.message_r2_objects'));
 const b=s.findIndex(x=>x.includes('FROM public.message_r2_upload_attempts'));
 const c=s.findIndex(x=>x.includes('FROM public.r2_attempt_verification_claims'));
 const d=s.findIndex(x=>x.includes('UPDATE public.r2_put_capability_windows'));
 assert.ok(a>=0&&b>a&&c>b&&d>c);
});
test('finalization sets claim COMPLETED and attempt VERIFIED before immutable pin INSERT',async()=>{
 const m=mock(),x=await fin(m);
 assert.equal(x.confirmed,true);
 assert.equal(x.selected,true);
 assert.equal(x.attemptObjectKey,t.attemptObjectKey);
 assert.deepEqual(m.state,{
   sealed:false,claim:'COMPLETED',attempt:'VERIFIED',winner:t.attemptObjectKey
 });
 const s=m.sql.map(x=>x.s);
 const a=s.findIndex(x=>x.includes('UPDATE public.r2_attempt_verification_claims'));
 const b=s.findIndex(x=>x.includes('UPDATE public.message_r2_upload_attempts'));
 const c=s.findIndex(x=>x.includes('INSERT INTO public.message_r2_winning_attempts'));
 assert.ok(a>=0&&b>a&&c>b);
 assert.equal(s.some(x=>x.includes("state='UPLOADED'")),false);
});
test('never GET when a PUT capability is still in its retirement window',async()=>{
 const m=mock({windowActive:true}),x=await pre(m);
 assert.equal(x.ready,undefined);
 assert.equal(x.code,'R2_WINNER_PUT_WINDOW_ACTIVE');
 assert.equal(m.state.sealed,true);
});
test('revoked pair, key rotation, expired media, orphan attempt or claim deny',async()=>{
 for(const options of [{rotated:true},{revoked:true},{expired:true},
   {parentTerminal:true},{attemptAbandoned:true},{claimExpired:true},
   {noAttempt:true},{noClaim:true},{winnerExists:true}]){
   const m=mock(options);
   const x=await pre(m);
   assert.equal(x.ready,undefined);
   assert.equal(m.state.sealed,false);
 }
});
test('untrusted SHA, bytes or physical key cannot settle',async()=>{
 for(const bad of [
   {...verified,sha256:'a'.repeat(64)},
   {...verified,bytes:1},
   {...verified,objectKey:'different'},
   {verified:false,...verified,verified:false}
 ]){
   const m=mock(),x=await fin(m,{verification:bad});
   assert.equal(x.confirmed,false);
   assert.equal(m.sql.length,0);
 }
});
test('claim settlement failure rolls back entire finalization',async()=>{
 const m=mock({settleFails:true});
 await assert.rejects(fin(m),/R2_WINNER_CLAIM_SETTLEMENT_FAILED/);
 assert.equal(m.rolled,1);
 assert.equal(m.state.winner,null);
 assert.equal(m.state.attempt,'VERIFYING');
});
test('attempt transition failure rolls back completed claim',async()=>{
 const m=mock({attemptFails:true});
 await assert.rejects(fin(m),/R2_WINNER_ATTEMPT_TRANSITION_FAILED/);
 assert.equal(m.rolled,1);
 assert.equal(m.state.claim,'RUNNING');
 assert.equal(m.state.winner,null);
});
test('winner INSERT error rolls back both claim and attempt transitions',async()=>{
 const m=mock({winnerFails:true});
 await assert.rejects(fin(m),/R2_WINNER_PIN_INSERT_FAILED/);
 assert.equal(m.rolled,1);
 assert.equal(m.state.claim,'RUNNING');
 assert.equal(m.state.attempt,'VERIFYING');
});
test('malformed internal ticket fails before any DB access',async()=>{
 const m=mock();
 await assert.rejects(pre(m,{ticket:{...t,claimId:'bad'}}),/Trusted/);
 await assert.rejects(fin(m,{ticket:{...t,attemptGeneration:0}}),/Trusted/);
 assert.equal(m.sql.length,0);
});
test('SQL errors never make a positive winner assertion',async()=>{
 const m=mock({dbDown:true});
 await assert.rejects(pre(m),/PG_DOWN/);
 assert.equal(m.rolled,1);
 assert.equal(m.state.winner,null);
});
