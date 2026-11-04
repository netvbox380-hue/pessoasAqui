'use strict';
const test=require('node:test');
const assert=require('node:assert/strict');
const crypto=require('node:crypto');
const {registerFamilyRecoveryRoutes,publicKeyIdentity}=require('../src/familyRecoveryRoutes');
const {canonicalInviteProof,verifyInviteIdentityProof}=require('../src/inviteAuth');
const sha=s=>crypto.createHash('sha256').update(s).digest('hex');
function person(){
  const {privateKey,publicKey}=crypto.generateKeyPairSync('ec',{namedCurve:'prime256v1'});
  const pub=publicKey.export({format:'der',type:'spki'}).toString('base64');
  return {id:publicKeyIdentity(pub),pub,privateKey};
}
const owner=person(),family=person(),newDevice=person(),stranger=person();
function proof(who,op,identity,alias,token=''){
  const timestamp=Date.now(),nonce=crypto.randomUUID();
  const tokenHash=token?sha(token):'';
  const message=canonicalInviteProof(op,identity,alias,'',tokenHash,timestamp,nonce);
  const sign=crypto.createSign('SHA256');sign.update(message);sign.end();
  return {v:1,publicKey:who.pub,nonce,timestamp,signature:sign.sign(who.privateKey).toString('base64')};
}
function setup(){
  let postgres=true,failSql=false;
  const routes=new Map(),auths=new Map(),grants=new Map(),delegations=new Map();
  const nonceSet=new Set();
  const people=new Set([owner.id,family.id,newDevice.id,stranger.id]);
  const app={post:(p,fn)=>routes.set(p,fn)};
  async function query(sql,args=[]){
    if(failSql)throw Error('DB unavailable');
    if(sql.includes('SELECT public_key_spki FROM identity_key_bindings'))
      return {rows:auths.has(args[0])?[{public_key_spki:auths.get(args[0])}]:[],rowCount:auths.has(args[0])?1:0};
    if(sql.includes('INSERT INTO recovery_proof_nonces')){
      const key=args.join(':');
      if(nonceSet.has(key))return {rowCount:0,rows:[]};
      nonceSet.add(key);return {rowCount:1,rows:[{nonce:args[2]}]};
    }
    if(sql.includes('INSERT INTO recovery_delegations')){
      if(!people.has(args[0])||!people.has(args[1]))return {rowCount:0,rows:[]};
      delegations.set(args[0]+'|'+args[1],{key:args[2],active:true});
      return {rowCount:1,rows:[{target_identity:args[0]}]};
    }
    if(sql.includes('SELECT family_public_key FROM recovery_delegations')){
      const d=delegations.get(args[0]+'|'+args[1]);
      return {rowCount:d?.active?1:0,rows:d?.active?[{family_public_key:d.key}]:[]};
    }
    if(sql.includes('SELECT generation FROM identity_key_bindings'))
      return {rowCount:auths.has(args[0])?1:0,rows:auths.has(args[0])?[{generation:1}]:[]};
    if(sql.includes('INSERT INTO recovery_grants')){
      const [id,tokenHash,target,by,pub,gen]=args;
      if(grants.has(id)||[...grants.values()].some(x=>x.hash===tokenHash))return {rowCount:0,rows:[]};
      const d=delegations.get(target+'|'+by);
      if(!d?.active||d.key!==pub)return {rowCount:0,rows:[]};
      const expires_at=new Date(Date.now()+900000).toISOString();
      grants.set(id,{id,hash:tokenHash,target_identity:target,family_identity:by,
        family_public_key:pub,expected_generation:gen,expires_at,used_at:null});
      return {rowCount:1,rows:[{expires_at}]};
    }
    if(sql.includes('FROM recovery_grants WHERE token_hash=')){
      const grant=[...grants.values()].find(g=>g.hash===args[0]);
      return {rowCount:grant?1:0,rows:grant?[grant]:[]};
    }
    if(sql.includes('SELECT id FROM identities')){
      return {rowCount:people.has(args[0])?1:0,rows:people.has(args[0])?[{id:args[0]}]:[]};
    }
    if(sql.includes('INSERT INTO identity_key_bindings')){
      auths.set(args[0],args[1]);return {rowCount:1,rows:[{generation:args[2]}]};
    }
    if(sql.includes('UPDATE recovery_grants SET used_at=')){
      for(const g of grants.values())if(g.target_identity===args[0]&&!g.used_at)g.used_at=new Date();
      return {rowCount:1,rows:[]};
    }
    if(sql.includes('UPDATE devices SET'))return {rowCount:0,rows:[]};
    throw Error('Unexpected SQL '+sql.slice(0,100));
  }
  // The fixture provides functional behavior for endpoints, not a PostgreSQL
  // serialization guarantee; transactional concurrency must be tested with a DB.
  const withTransaction=async fn=>fn(query);
  registerFamilyRecoveryRoutes(app,{
    query,withTransaction,isPostgres:()=>postgres,databaseRequired:true,
    activeSockets:new Map(),WebSocket:{OPEN:1}
  });
  async function post(route,body){
    let status=200,data=null;
    const response={status(v){status=v;return this;},json(v){data=v;return this;}};
    await routes.get(route)({body},response);
    return {status,data};
  }
  const delegate=(who=owner,by=family)=>post('/api/family/recovery/delegate',{
    targetIdentity:owner.id,familyIdentity:by.id,familyPublicKey:by.pub,
    identityProof:proof(who,'RECOVERY_DELEGATE',owner.id,by.id,by.pub)
  });
  const issue=(by=family)=>post('/api/family/recovery/issue',{
    targetIdentity:owner.id,familyIdentity:by.id,
    identityProof:proof(by,'RECOVERY_ISSUE',by.id,owner.id)
  });
  const claim=(token,newWho=newDevice)=>post('/api/family/recovery/claim',{
    recoveryKey:token,newDeviceIdentity:newWho.id,
    identityProof:proof(newWho,'RECOVERY_CLAIM',newWho.id,'',token)
  });
  return {post,delegate,issue,claim,auths,delegations,grants,nonceSet,
    fail:()=>failSql=true,offline:()=>postgres=false};
}
test('normal family and owner identity keys self-certify',()=>{
  assert.equal(publicKeyIdentity(owner.pub),owner.id);
  assert.equal(publicKeyIdentity('wrong'),null);
});
test('stranger cannot authorize a family for the owner',async()=>{
  const a=setup();
  assert.equal((await a.delegate(stranger)).status,401);
  assert.equal((await a.issue()).status,403);
});
test('owner permits family, family signs grant, new device signs claim',async()=>{
  const a=setup();
  assert.equal((await a.delegate()).status,200);
  const issuance=await a.issue();
  assert.equal(issuance.status,200);
  assert.match(issuance.data.recoveryKey,/^PA-REC-[A-F0-9]{64}$/);
  const token=issuance.data.recoveryKey;
  assert.equal([...a.grants.values()][0].hash,sha(token));
  assert.equal((await a.claim(token)).status,200);
  assert.equal(a.auths.get(owner.id),newDevice.pub);
  assert.equal((await a.claim(token)).status,410);
  // A chave anterior ainda comprova hash original, mas deixa de ser autorizada.
  assert.equal(verifyInviteIdentityProof('CREATE',owner.id,'Fernando','','',
    proof(owner,'CREATE',owner.id,'Fernando'),Date.now(),newDevice.pub),false);
  assert.equal(verifyInviteIdentityProof('CREATE',owner.id,'Fernando','','',
    proof(newDevice,'CREATE',owner.id,'Fernando'),Date.now(),newDevice.pub),true);
});
test('forged grant or changed recipient proof rejected',async()=>{
  const a=setup();
  await a.delegate();
  const grant=await a.issue();
  const t=grant.data.recoveryKey;
  const forged=await a.post('/api/family/recovery/claim',{
    recoveryKey:t,newDeviceIdentity:newDevice.id,
    identityProof:proof(stranger,'RECOVERY_CLAIM',newDevice.id,'',t)
  });
  assert.equal(forged.status,401);
  assert.equal((await a.claim(t)).status,200);
});
test('disabled family cannot create grant or take over the owner',async()=>{
  const a=setup();await a.delegate();
  a.delegations.get(owner.id+'|'+family.id).active=false;
  assert.equal((await a.issue()).status,403);
});
test('system fails closed with PostgreSQL unavailable',async()=>{
  const a=setup();a.offline();
  assert.equal((await a.delegate()).status,503);
  assert.equal((await a.issue()).status,503);
  assert.equal((await a.claim('PA-REC-'+'A'.repeat(64))).status,503);
});
test('weak legacy eight-char recovery keys are invalid',async()=>{
  const a=setup();await a.delegate();
  assert.equal((await a.claim('PA-REC-994A1F82')).status,400);
});
