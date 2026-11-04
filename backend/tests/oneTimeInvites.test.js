'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const crypto = require('node:crypto');
const guard = require('../src/inviteSecurity');

const src = fs.readFileSync(path.join(__dirname, '../src/server.js'), 'utf8').replace(/\r\n/g, '\n');
const start = src.indexOf('// Um convite é uma credencial bearer.');
const end = src.indexOf('/**\n * Landing Page Web do Convite', start);
assert.ok(start >= 0 && end > start, 'invite routes were not found');

function fixture({postgres = false, failDb = false, shared = null} = {}) {
  const store = shared || {invites: new Map(), conns: new Map()};
  const activeInvites = new Map();
  const routes = new Map();
  const app = {
    post(url,handler){routes.set(url,handler);}
  };
  let seq = 0;
  const query = async (sql, args) => {
    if (failDb) throw Error('DB down');
    if (sql.includes('INSERT INTO invites')) {
      const [id, token, sender, alias, signature, expiresAt] = args;
      if (store.invites.has(id)) return {rowCount: 0, rows: []};
      store.invites.set(id,{token,sender,alias,expiresAt,isUsed:false});
      return {rowCount: 1,rows:[{id}]};
    }
    if (sql.includes('WITH claimed AS')) {
      const [id, rawToken, receiver, hash] = args;
      const v=store.invites.get(id);
      if(!v || v.isUsed || v.expiresAt <= Date.now() ||
         !(v.token === rawToken || v.token === hash) ||
         v.sender.toUpperCase() === receiver.toUpperCase()) return {rowCount:0,rows:[]};
      v.isUsed=true;
      store.conns.set(v.sender+'->'+receiver,true);
      store.conns.set(receiver+'->'+v.sender,true);
      return {rowCount:1,rows:[{sender_identity:v.sender,sender_alias:v.alias,linked_count:'2'}]};
    }
    throw Error('Unexpected SQL: '+sql.slice(0,60));
  };
  const ctx = {
    app, console, Date, URLSearchParams, Set, Map, process:{env:postgres?{DATABASE_URL:'test'}:{}},
    generateNonce(){seq++;return crypto.createHash('sha256').update('test-'+seq).digest('hex');},
    cleanId:v=>String(v||'').replace(/^peer-/i,'').trim().toUpperCase(),
    keyId:v=>String(v||'').toUpperCase(),
    signInvitePayload:()=> 'a'.repeat(64),
    INVITE_LIFETIME_MS: guard.INVITE_LIFETIME_MS,
    tokenDigest:guard.tokenDigest, verifyStoredToken:guard.verifyStoredToken,
    validateInviteId:guard.validateInviteId, validateInviteToken:guard.validateInviteToken,
    isPostgres:()=>postgres,
    query, activeInvites, localStore:{invites:new Map(),connections:new Map()},
    activeSockets:new Map(),pendingMessages:new Map(),WebSocket:{OPEN:1}
  };
  vm.runInNewContext(src.slice(start,end),ctx);
  async function post(url,body) {
    let code=200, json=null;
    const res={status(c){code=c;return this;},json(o){json=o;return this;}};
    await routes.get(url)({body},res);
    return {code,json};
  }
  return {post,store,activeInvites,ctx};
}

const sender = 'A1B2C3D4E5F60708';
const receiver = 'B1B2C3D4E5F60708';
const other = 'C1B2C3D4E5F60708';
const create = f=>f.post('/api/invites/create',
  {senderIdentity:sender,senderAlias:'Fernando'});
const redeem = (f, invite, user=receiver, token=invite.token)=>f.post('/api/invites/redeem',
  {inviteId:invite.inviteId,token,receiverIdentity:user,receiverAlias:'Maria'});

test('token is hashed and original invitation is redeemed once in memory', async () => {
  const f=fixture();
  const c=await create(f);
  assert.equal(c.code,200);
  assert.equal(f.activeInvites.get(c.json.inviteId).tokenHash,guard.tokenDigest(c.json.token));
  const url=new URL(c.json.inviteUrl);
  assert.equal(url.searchParams.get('token'),c.json.token);
  const a=await redeem(f,c.json);
  const b=await redeem(f,c.json,other);
  assert.equal(a.code,200);
  assert.equal(b.code,410);
});

test('sender cannot claim own invitation; token cannot be guessed',async()=>{
  const f=fixture();const c=(await create(f)).json;
  assert.equal((await redeem(f,c,sender)).code,400);
  assert.equal((await redeem(f,c,receiver,'0'.repeat(64))).code,410);
  assert.equal((await redeem(f,c)).code,200);
});

test('atomic postgres claim rejects second concurrent recipient and survives restart',async()=>{
  const db={invites:new Map(),conns:new Map()};
  const f=fixture({postgres:true,shared:db});
  const c=(await create(f)).json;
  assert.equal(db.invites.get(c.inviteId).token,guard.tokenDigest(c.token));
  const [a,b]=await Promise.all([redeem(f,c,receiver),redeem(f,c,other)]);
  assert.deepEqual([a.code,b.code].sort(),[200,410]);
  assert.equal(db.conns.size,2);
  const restarted=fixture({postgres:true,shared:db});
  assert.equal((await redeem(restarted,c,other)).code,410);
});

test('database outage fails closed, no invite or connection falsely approved',async()=>{
  const f=fixture({postgres:true,failDb:true});
  const c=await create(f);
  assert.equal(c.code,503);
  assert.equal(f.store.invites.size,0);
  const fake={inviteId:'inv_'+'a'.repeat(24),token:'f'.repeat(64)};
  assert.equal((await redeem(f,fake)).code,503);
  assert.equal(f.store.conns.size,0);
});

test('legacy plaintext invite stored in database remains redeemable once',async()=>{
  const f=fixture({postgres:true});
  const token='a'.repeat(32), id='inv_'+'b'.repeat(12);
  f.store.invites.set(id,{token,sender,alias:'Antigo',expiresAt:Date.now()+10000,isUsed:false});
  assert.equal((await redeem(f,{inviteId:id,token})).code,200);
  assert.equal((await redeem(f,{inviteId:id,token},other)).code,410);
});

test('invalid and expired invitations are rejected',async()=>{
  const f=fixture();
  assert.equal((await f.post('/api/invites/redeem',
    {inviteId:'../secrets',token:'a'.repeat(64),receiverIdentity:receiver})).code,400);
  const c=(await create(f)).json;
  f.activeInvites.get(c.inviteId).expiresAt=Date.now()-1;
  assert.equal((await redeem(f,c)).code,410);
});

test('stored token helper constant-time validation rejects malformed values',()=>{
  const token='a'.repeat(32);
  assert.equal(guard.verifyStoredToken(guard.tokenDigest(token),token),true);
  assert.equal(guard.verifyStoredToken(token,token),true);
  assert.equal(guard.verifyStoredToken(guard.tokenDigest(token),'b'.repeat(32)),false);
  assert.equal(guard.verifyStoredToken(guard.tokenDigest(token),'../'),false);
});
