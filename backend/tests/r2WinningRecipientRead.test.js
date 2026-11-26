'use strict';
const test=require('node:test');
const assert=require('node:assert/strict');
const crypto=require('node:crypto');
const {authorizeWinningRecipientR2Read}=require('../src/r2WinningRecipientReadAuth');
const {admitAuthenticatedWinningR2Read,MAX_DAILY_READS,MAX_DAILY_READ_BYTES}=require('../src/r2ReadAdmission');
const {canonicalR2Action}=require('../src/r2ActionAuth');
const {revalidateR2ReadAtStreamStart}=require('../src/r2StreamStartGate');
const {makeOpaqueObjectKey}=require('../src/r2CiphertextAdapter');
const identity='AAAAAAAAAAAAAAAA',peerIdentity='BBBBBBBBBBBBBBBB';
const objectKey=makeOpaqueObjectKey(),messageId='winner-test';
const digest='a'.repeat(64);
const row={object_key:objectKey,content_sha256:digest,content_bytes:'123'};
const args={authenticatedIdentity:identity,peerIdentity,messageId,objectKey};
const result=rows=>({rows,rowCount:rows.length});
const pair=[{from_identity:identity,to_identity:peerIdentity,is_mutual:true},
{from_identity:peerIdentity,to_identity:identity,is_mutual:true}];
function fixture({winner=[row],terminal=[],quota=true,binding,pairs=pair}={}){
  const calls=[];
  const tx=async(sql,params)=>{
    calls.push({sql,params});
    if(sql.includes('identity_key_bindings'))return result(binding?[{public_key_spki:binding}]:[]);
    if(sql.includes('public.connections'))return result(pairs);
    if(sql.includes('r2_action_nonces'))return result([{}]);
    if(sql.includes('message_r2_winning_attempts'))return result(winner);
    if(sql.includes('message_delivery_receipts'))return result(terminal);
    if(sql.includes('INSERT INTO public.r2_read_daily_budget'))return result([]);
    if(sql.includes('UPDATE public.r2_read_daily_budget'))return result(quota?[{}]:[]);
    throw Error('Unexpected SQL: '+sql);
  };
  return {calls,tx,withTransaction:fn=>fn(tx)};
}
function signed(){
  const {publicKey,privateKey}=crypto.generateKeyPairSync('ec',{namedCurve:'prime256v1'});
  const binding=publicKey.export({format:'der',type:'spki'}).toString('base64');
  const now=Date.now(),nonce=crypto.randomUUID();
  const canonical=canonicalR2Action('R2_READ',identity,peerIdentity,{messageId,objectKey},now,nonce);
  const signature=crypto.sign('sha256',Buffer.from(canonical),privateKey).toString('base64');
  return {binding,request:{identity,peerIdentity,messageId,objectKey,now,
    proof:{v:1,timestamp:now,nonce,publicKey:binding,signature}}};
}
test('winner authorization selects exact physical descriptor and locked relational state',async()=>{
  const f=fixture();
  const v=await authorizeWinningRecipientR2Read({...args,tx:f.tx});
  assert.equal(v.allowed,true);assert.equal(v.objectKey,objectKey);
  assert.equal(v.expectedBytes,123);assert.equal(v.expectedSha256,digest);
  const sql=f.calls[0].sql;
  for(const token of ['w.object_key=$1','o.object_key<>w.object_key','r2_winner_attach_mode IS TRUE',
    "a.state='VERIFIED'","c.state='COMPLETED'","o.state='ATTACHED'",
    'm.r2_winning_object_key=w.object_key','m.expires_at>clock_timestamp()','FOR SHARE OF o,w,a,c,m'])
    assert.ok(sql.includes(token),token);
});
test('missing winner denies without fallback',async()=>{
  const f=fixture({winner:[]});
  assert.equal((await authorizeWinningRecipientR2Read({...args,tx:f.tx})).allowed,false);
  assert.equal(f.calls.length,1);
});
test('terminal receipt denies winner',async()=>{
  const f=fixture({terminal:[{state:'DELIVERED'}]});
  assert.equal((await authorizeWinningRecipientR2Read({...args,tx:f.tx})).statusCode,410);
});
test('wrong physical key or descriptor rejected',async()=>{
  for(const bad of [{...row,object_key:makeOpaqueObjectKey()},{...row,content_bytes:'0'},{...row,content_sha256:'bad'}]){
    const f=fixture({winner:[bad]});
    assert.equal((await authorizeWinningRecipientR2Read({...args,tx:f.tx})).allowed,false);
  }
});
test('signed winner admission uses persistent unchanged quota',async()=>{
  const {binding,request}=signed();const f=fixture({binding});
  const a=await admitAuthenticatedWinningR2Read({...request,withTransaction:f.withTransaction});
  assert.equal(a.admitted,true);assert.equal(a.value.winnerMode,true);assert.equal(a.value.identityKey,binding);
  assert.deepEqual(f.calls.find(x=>x.sql.includes('UPDATE public.r2_read_daily_budget')).params,
    [identity,123,24,300*1024*1024]);
  assert.equal(MAX_DAILY_READS,24);assert.equal(MAX_DAILY_READ_BYTES,300*1024*1024);
});
test('winner quota refusal and signature tampering deny',async()=>{
  const {binding,request}=signed();const f=fixture({binding,quota:false});
  assert.equal((await admitAuthenticatedWinningR2Read({...request,withTransaction:f.withTransaction})).statusCode,429);
  const g=fixture({binding});
  assert.equal((await admitAuthenticatedWinningR2Read({...request,objectKey:makeOpaqueObjectKey(),withTransaction:g.withTransaction})).authorized,false);
});
test('fresh winner gate rejects rotated identity, revoked pair, changed descriptor',async()=>{
  const {binding,request}=signed();const f=fixture({binding});
  const admission=await admitAuthenticatedWinningR2Read({...request,withTransaction:f.withTransaction});
  assert.equal((await revalidateR2ReadAtStreamStart({admission,withTransaction:f.withTransaction})).allowed,true);
  for(const g of [fixture({binding:'rotated'}),fixture({binding,pairs:[]}),fixture({binding,winner:[{...row,content_bytes:'124'}]})]){
    assert.equal((await revalidateR2ReadAtStreamStart({admission,withTransaction:g.withTransaction})).allowed,false);
  }
});
