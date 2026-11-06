'use strict';
const test=require('node:test');
const assert=require('node:assert/strict');
const crypto=require('node:crypto');
const {registerFamilyRecoveryRoutes}=require('../src/familyRecoveryRoutes');
const {canonicalInviteProof}=require('../src/inviteAuth');

function identity(){
 const pair=crypto.generateKeyPairSync('ec',{namedCurve:'prime256v1'});
 const publicKey=pair.publicKey.export({format:'der',type:'spki'}).toString('base64');
 return {pair,publicKey,id:crypto.createHash('sha256')
   .update(Buffer.from(publicKey,'base64')).digest('hex').slice(0,16).toUpperCase()};
}
function proof(actor,owner){
 const op='RECOVERY_READINESS',timestamp=Date.now(),nonce=crypto.randomUUID();
 const body=canonicalInviteProof(op,owner,'','','',timestamp,nonce);
 const signer=crypto.createSign('SHA256');signer.update(body);signer.end();
 return {v:1,publicKey:actor.publicKey,timestamp,nonce,
   signature:signer.sign(actor.pair.privateKey).toString('base64')};
}
function fixture(){
 const owner=identity(),familyA=identity(),familyB=identity(),familyC=identity();
 const routes=new Map(),nonces=new Set();
 const query=async(sql,args)=>{
   if(sql.includes('SELECT public_key_spki FROM identity_key_bindings'))
     return {rows:args[0]===owner.id?[{public_key_spki:owner.publicKey}]:[],rowCount:args[0]===owner.id?1:0};
   if(sql.includes('INSERT INTO recovery_proof_nonces')) {
     const k=args.join('|');if(nonces.has(k))return {rows:[],rowCount:0};
     nonces.add(k);return {rows:[{nonce:args[2]}],rowCount:1};
   }
   if(sql.includes('SELECT family_identity FROM recovery_delegations')) {
     return {rows:[familyA.id,familyB.id,familyC.id].map(family_identity=>({family_identity})),rowCount:3};
   }
   throw new Error('Unexpected SQL');
 };
 registerFamilyRecoveryRoutes({post:(route,fn)=>routes.set(route,fn)},{
   query,withTransaction:async fn=>fn(query),isPostgres:()=>true,
   databaseRequired:true,activeSockets:new Map(),WebSocket:{OPEN:1}
 });
 async function request(who=owner,p=proof(who,owner.id)){
   let code=200,json=null;
   await routes.get('/api/family/recovery/delegates')({
     body:{targetIdentity:owner.id,identityProof:p}
   },{status(n){code=n;return this},json(v){json=v;return this}});
   return {code,json};
 }
 return {owner,familyA,familyB,familyC,request};
}
test('server returns only verified active guardians to owner',async()=>{
 const f=fixture();const result=await f.request();
 assert.equal(result.code,200);assert.equal(result.json.success,true);
 assert.deepEqual(result.json.authorizedFamilyIdentities.sort(),
   [f.familyA.id,f.familyB.id,f.familyC.id].sort());
});
test('stranger cannot obtain family guardian identities',async()=>{
 const f=fixture(),other=identity();
 const result=await f.request(other,proof(other,f.owner.id));
 assert.equal(result.code,401);
});
test('guardian discovery rejects nonce replay',async()=>{
 const f=fixture(),p=proof(f.owner,f.owner.id);
 assert.equal((await f.request(f.owner,p)).code,200);
 assert.equal((await f.request(f.owner,p)).code,409);
});
