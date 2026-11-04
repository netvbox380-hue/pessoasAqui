'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const crypto = require('node:crypto');
const guard = require('../src/inviteSecurity');
const auth = require('../src/inviteAuth');

const src = fs.readFileSync(path.join(__dirname, '../src/server.js'), 'utf8').replace(/\r\n/g, '\n');
const start = src.indexOf('// Um convite é uma credencial bearer.');
const end = src.indexOf('/**\n * Landing Page Web do Convite', start);
assert.ok(start >= 0 && end > start, 'invite routes were not found');

function identity() {
  const keys = crypto.generateKeyPairSync('ec', {namedCurve:'prime256v1'});
  const der = keys.publicKey.export({format:'der',type:'spki'});
  return {
    identity: crypto.createHash('sha256').update(der).digest('hex').slice(0,16).toUpperCase(),
    publicKey: der.toString('base64'),
    privateKey: keys.privateKey
  };
}
const sender = identity(), receiver = identity(), other = identity();
function proof(who, operation, alias, inviteId = '', token = '', nonce = crypto.randomUUID(), timestamp = Date.now()) {
  const digest = token ? guard.tokenDigest(token) : '';
  const message = auth.canonicalInviteProof(operation,who.identity,alias,inviteId,digest,timestamp,nonce);
  const signer=crypto.createSign('SHA256'); signer.update(message,'utf8'); signer.end();
  return {
    v:1, publicKey:who.publicKey, nonce, timestamp,
    signature:signer.sign(who.privateKey).toString('base64')
  };
}

function fixture({postgres=false,failDb=false,shared=null}={}) {
  const store=shared||{invites:new Map(),conns:new Map()};
  const activeInvites=new Map(),routes=new Map();
  const app={post(url,handler){routes.set(url,handler);}};
  let seq=0;
  const query=async(sql,args)=>{
    if(failDb)throw Error('DB down');
    if(sql.includes('INSERT INTO invites')) {
      const [id,token,s,alias,signature,expiresAt]=args;
      if(store.invites.has(id))return {rowCount:0,rows:[]};
      store.invites.set(id,{token,sender:s,alias,expiresAt,isUsed:false});
      return {rowCount:1,rows:[{id}]};
    }
    if(sql.includes('WITH claimed AS')) {
      const [id,rawToken,r,hash]=args;
      const v=store.invites.get(id);
      if(!v||v.isUsed||v.expiresAt<=Date.now()||
          !(v.token===rawToken||v.token===hash)||
          v.sender.toUpperCase()===r.toUpperCase())return {rowCount:0,rows:[]};
      v.isUsed=true;
      store.conns.set(v.sender+'->'+r,true);
      store.conns.set(r+'->'+v.sender,true);
      return {rowCount:1,rows:[{sender_identity:v.sender,sender_alias:v.alias,linked_count:'2'}]};
    }
    throw Error('Unexpected SQL: '+sql.slice(0,80));
  };
  const ctx={
    app,console,Date,URLSearchParams,Set,Map,process:{env:postgres?{DATABASE_URL:'test'}:{}},
    generateNonce(){seq++;return crypto.createHash('sha256').update('test-'+seq).digest('hex');},
    cleanId:v=>String(v||'').replace(/^peer-/i,'').trim(),
    keyId:v=>String(v||'').toLowerCase(),
    signInvitePayload:()=> 'a'.repeat(64),
    INVITE_LIFETIME_MS:guard.INVITE_LIFETIME_MS,
    tokenDigest:guard.tokenDigest,verifyStoredToken:guard.verifyStoredToken,
    validateInviteId:guard.validateInviteId,validateInviteToken:guard.validateInviteToken,
    verifyInviteIdentityProof:auth.verifyInviteIdentityProof,
    deriveInviteId:auth.deriveInviteId,
    isPostgres:()=>postgres,query,activeInvites,
    localStore:{invites:new Map(),connections:new Map()},
    activeSockets:new Map(),pendingMessages:new Map(),WebSocket:{OPEN:1}
  };
  vm.runInNewContext(src.slice(start,end),ctx);
  async function post(url,body){
    let code=200,json=null;
    const res={status(c){code=c;return this;},json(o){json=o;return this;}};
    await routes.get(url)({body},res);
    return {code,json};
  }
  return {post,store,activeInvites,ctx};
}
const createBody=(who=sender,alias='Fernando')=>({
  senderIdentity:who.identity,senderAlias:alias,
  identityProof:proof(who,'CREATE',alias)
});
const create=(f,who=sender)=>f.post('/api/invites/create',createBody(who));
const redeemBody=(invite,who=receiver,token=invite.token)=>({
  inviteId:invite.inviteId,token,receiverIdentity:who.identity,
  receiverAlias:'Maria',identityProof:proof(who,'REDEEM','Maria',invite.inviteId,token)
});
const redeem=(f,invite,who=receiver,token=invite.token)=>
  f.post('/api/invites/redeem',redeemBody(invite,who,token));

test('token is hashed and original invitation is redeemed once in memory',async()=>{
  const f=fixture(),c=await create(f);
  assert.equal(c.code,200);
  assert.equal(f.activeInvites.get(c.json.inviteId).tokenHash,guard.tokenDigest(c.json.token));
  assert.equal(new URL(c.json.inviteUrl).searchParams.get('token'),c.json.token);
  assert.equal((await redeem(f,c.json)).code,200);
  assert.equal((await redeem(f,c.json,other)).code,410);
});
test('sender cannot claim own invitation; token cannot be guessed',async()=>{
  const f=fixture(),c=(await create(f)).json;
  assert.equal((await redeem(f,c,sender)).code,400);
  assert.equal((await redeem(f,c,receiver,'0'.repeat(64))).code,410);
  assert.equal((await redeem(f,c)).code,200);
});
test('atomic postgres claim rejects concurrent recipient and survives restart',async()=>{
  const db={invites:new Map(),conns:new Map()};
  const f=fixture({postgres:true,shared:db}),c=(await create(f)).json;
  assert.equal(db.invites.get(c.inviteId).token,guard.tokenDigest(c.token));
  const [a,b]=await Promise.all([redeem(f,c),redeem(f,c,other)]);
  assert.deepEqual([a.code,b.code].sort(),[200,410]);
  assert.equal(db.conns.size,2);
  assert.equal((await redeem(fixture({postgres:true,shared:db}),c,other)).code,410);
});
test('database outage fails closed, no invitation or connection falsely approved',async()=>{
  const f=fixture({postgres:true,failDb:true});
  assert.equal((await create(f)).code,503);
  assert.equal(f.store.invites.size,0);
  const fake={inviteId:'inv_'+'a'.repeat(24),token:'f'.repeat(64)};
  assert.equal((await redeem(f,fake)).code,503);
  assert.equal(f.store.conns.size,0);
});
test('legacy plaintext invite remains redeemable with authenticated updated client',async()=>{
  const f=fixture({postgres:true}),token='a'.repeat(32),id='inv_'+'b'.repeat(12);
  f.store.invites.set(id,{token,sender:sender.identity,alias:'Antigo',expiresAt:Date.now()+10000,isUsed:false});
  assert.equal((await redeem(f,{inviteId:id,token})).code,200);
  assert.equal((await redeem(f,{inviteId:id,token},other)).code,410);
});
test('invalid and expired invitations are rejected',async()=>{
  const f=fixture();
  assert.equal((await f.post('/api/invites/redeem',{
    inviteId:'../secrets',token:'a'.repeat(64),receiverIdentity:receiver.identity
  })).code,400);
  const c=(await create(f)).json;
  f.activeInvites.get(c.inviteId).expiresAt=Date.now()-1;
  assert.equal((await redeem(f,c)).code,410);
});
test('stored token helper validates malformed values',()=>{
  const token='a'.repeat(32);
  assert.equal(guard.verifyStoredToken(guard.tokenDigest(token),token),true);
  assert.equal(guard.verifyStoredToken(token,token),true);
  assert.equal(guard.verifyStoredToken(guard.tokenDigest(token),'b'.repeat(32)),false);
  assert.equal(guard.verifyStoredToken(guard.tokenDigest(token),'../'),false);
});
test('unsigned legacy requests cannot bypass identity authentication',async()=>{
  const f=fixture();
  assert.equal((await f.post('/api/invites/create',{
    senderIdentity:sender.identity,senderAlias:'Fernando'
  })).code,401);
  const c=(await create(f)).json;
  assert.equal((await f.post('/api/invites/redeem',{
    inviteId:c.inviteId,token:c.token,
    receiverIdentity:receiver.identity,receiverAlias:'Maria'
  })).code,401);
  assert.equal((await redeem(f,c)).code,200);
});
test('forged sender and receiver identities rejected even with valid other keys',async()=>{
  const f=fixture();
  const forge=createBody(sender);
  forge.senderIdentity=receiver.identity;
  assert.equal((await f.post('/api/invites/create',forge)).code,401);
  const c=(await create(f)).json;
  const fake=redeemBody(c,receiver);
  fake.receiverIdentity=other.identity;
  assert.equal((await f.post('/api/invites/redeem',fake)).code,401);
  assert.equal((await redeem(f,c)).code,200);
});
test('signed alias and token cannot be substituted',async()=>{
  const f=fixture();
  const forged=createBody(sender);
  forged.senderAlias='NomeFalso';
  assert.equal((await f.post('/api/invites/create',forged)).code,401);
  const c=(await create(f)).json;
  const fake=redeemBody(c,receiver);
  fake.token='f'.repeat(64);
  assert.equal((await f.post('/api/invites/redeem',fake)).code,401);
  assert.equal((await redeem(f,c)).code,200);
});
test('expired or future proof timestamp is rejected',async()=>{
  const f=fixture();
  const body=createBody(sender);
  body.identityProof=proof(sender,'CREATE','Fernando','','',crypto.randomUUID(),Date.now()-300000);
  assert.equal((await f.post('/api/invites/create',body)).code,401);
  body.identityProof=proof(sender,'CREATE','Fernando','','',crypto.randomUUID(),Date.now()+300000);
  assert.equal((await f.post('/api/invites/create',body)).code,401);
});
test('replay of signed create request is rejected in memory and after PostgreSQL restart',async()=>{
  const body=createBody();
  const f=fixture();
  assert.equal((await f.post('/api/invites/create',body)).code,200);
  assert.equal((await f.post('/api/invites/create',body)).code,409);
  const db={invites:new Map(),conns:new Map()},g=fixture({postgres:true,shared:db});
  assert.equal((await g.post('/api/invites/create',body)).code,200);
  const restarted=fixture({postgres:true,shared:db});
  assert.equal((await restarted.post('/api/invites/create',body)).code,409);
});
test('recovered identity without a signed key-transfer fails safely',async()=>{
  const f=fixture();
  const altered=createBody(sender);
  altered.senderIdentity='DEADBEEFDEADBEEF';
  assert.equal((await f.post('/api/invites/create',altered)).code,401);
});
