'use strict';
// Node built-ins only. Exercises the REAL handlers extracted from server.js;
// no external services, Postgres or Render required.
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, '../src/server.js'), 'utf8').replace(/\r\n/g, '\n');
const handlerSection = source.slice(source.indexOf("app.get('/api/connections/status'"), source.lastIndexOf('/**', source.indexOf("app.post('/api/invites/create'")));
assert.ok(handlerSection.startsWith('app.get('));

const ID_A = 'A1A1A1A1A1A1A1A1';
const ID_B = 'B2B2B2B2B2B2B2B2';

function fixture({postgres=false, databaseError=false, registeredIdentities=[], connectionRows=[]} = {}) {
  const routes = new Map();
  const conns = new Map();
  const sent = [];
  const dbOps = [];
  const durableRows = connectionRows.map(row => ({...row}));
  const app = {get: (route, handler) => routes.set(`GET ${route}`, handler),post: (route, handler) => routes.set(`POST ${route}`, handler)};
  const cleanId = v => String(v || '').replace(/^peer-/i,'').trim().toUpperCase();
  const context = {app, localStore:{connections:conns}, isPostgres:()=>postgres,
    query:async (sql,args)=> {
      dbOps.push({sql,args});
      if(databaseError)throw Error('DB down');
      if(sql.includes('SELECT id FROM identities WHERE id = ANY')) {
        const rows=args[0].filter(id=>registeredIdentities.includes(id)).map(id=>({id}));
        return {rows,rowCount:rows.length};
      }
      if (sql.includes('SELECT from_identity, to_identity, is_mutual FROM connections')) {
        const rows = durableRows.filter(row =>
          row.from_identity.toUpperCase() === args[0].toUpperCase() ||
          row.to_identity.toUpperCase() === args[0].toUpperCase());
        return {rows,rowCount:rows.length};
      }
      if (sql.includes('SELECT 1 FROM connections')) {
        const rows = durableRows.filter(row =>
          row.from_identity.toUpperCase() === args[0].toUpperCase() &&
          row.to_identity.toUpperCase() === args[1].toUpperCase());
        return {rows,rowCount:rows.length};
      }
      if (sql.includes('INSERT INTO connections')) {
        const [from_identity,to_identity,is_mutual] = args;
        const existing = durableRows.find(row => row.from_identity === from_identity && row.to_identity === to_identity);
        if (existing) existing.is_mutual = is_mutual;
        else durableRows.push({from_identity,to_identity,is_mutual});
        return {rows:[],rowCount:1};
      }
      if (sql.includes('UPDATE connections SET is_mutual = TRUE')) {
        durableRows.filter(row => [args[0],args[1]].includes(row.from_identity) &&
          [args[0],args[1]].includes(row.to_identity)).forEach(row => {row.is_mutual=true});
        return {rows:[],rowCount:2};
      }
      return {rows:[],rowCount:0};
    },
    cleanId, keyId:cleanId,
    withTransaction: async work => work((sql,args) => context.query(sql,args)),
    activePresence:new Map(), activeSockets:new Map(), pendingMessages:new Map(),
    WebSocket:{OPEN:1}, console, Date, process:{env:{}}, verifySignedHttp:async()=>true};
  vm.runInNewContext(handlerSection, context);
  async function call(method, route, body={}, query={}) {
    let code = 200, payload;
    const res = {status(v){code=v;return this},json(v){payload=v;return this}};
    const handler = routes.get(`${method} ${route}`);
    assert.ok(handler, `missing handler ${method} ${route}`);
    await handler({body,query},res);
    return {code,payload};
  }
  return {call,conns,sent,dbOps,context};
}

test('legacy client cannot unilaterally resurrect a revoked mutual relationship via /sync',async()=>{
  const f=fixture();
  const res=await f.call('POST','/api/connections/sync',{myIdentity:ID_A,mutualPeers:[ID_B]});
  assert.equal(res.code,410);assert.equal(f.conns.size,0);
});

test('bilateral consent is needed; either side can revoke; old reciprocal mark does not survive',async()=>{
  const f=fixture();
  let r=await f.call('POST','/api/connections/mark',{fromIdentity:ID_A,toIdentity:ID_B});
  assert.equal(r.payload.isMutualConnection,false);
  r=await f.call('POST','/api/connections/mark',{fromIdentity:ID_B,toIdentity:ID_A});
  assert.equal(r.payload.isMutualConnection,true);
  assert.equal(f.conns.size,2);
  r=await f.call('POST','/api/connections/unmark',{fromIdentity:ID_A,toIdentity:ID_B});
  assert.equal(r.code,200);assert.equal(f.conns.size,0);
  const statusA=await f.call('POST','/api/connections/status',{myIdentity:ID_A});
  const statusB=await f.call('POST','/api/connections/status',{myIdentity:ID_B});
  assert.equal(statusA.payload.mutual.length,0);
  assert.equal(statusB.payload.mutual.length,0);
  r=await f.call('POST','/api/connections/mark',{fromIdentity:ID_A,toIdentity:ID_B});
  assert.equal(r.payload.isMutualConnection,false,'new approval must be from both participants');
});

test('remote client gets MUTUAL_CONNECTION_BROKEN websocket event on revocation',async()=>{
  const f=fixture();
  const notifications=[];
  f.context.activeSockets.set(ID_B,{readyState:1,send:v=>notifications.push(JSON.parse(v))});
  await f.call('POST','/api/connections/mark',{fromIdentity:ID_A,toIdentity:ID_B});
  await f.call('POST','/api/connections/mark',{fromIdentity:ID_B,toIdentity:ID_A});
  await f.call('POST','/api/connections/unmark',{fromIdentity:ID_A,toIdentity:ID_B});
  assert.ok(notifications.some(n=>n.type==='MUTUAL_CONNECTION_BROKEN' && n.partnerIdentity===ID_A));
});

test('database failure does not acknowledge or locally apply a revocation',async()=>{
  const f=fixture({postgres:true,databaseError:true});
  f.conns.set(`${ID_A}->${ID_B}`,{fromIdentity:ID_A,toIdentity:ID_B,isMutual:true});
  f.conns.set(`${ID_B}->${ID_A}`,{fromIdentity:ID_B,toIdentity:ID_A,isMutual:true});
  const res=await f.call('POST','/api/connections/unmark',{fromIdentity:ID_A,toIdentity:ID_B});
  assert.equal(res.code,503);assert.equal(f.conns.size,2);
});

test('database status failure is not mislabeled as empty authoritative snapshot',async()=>{
  const f=fixture({postgres:true,databaseError:true});
  const res=await f.call('POST','/api/connections/status',{myIdentity:ID_A});
  assert.equal(res.code,503);
});

test('com DATABASE_URL configurada e PostgreSQL desconectado, relações não são fabricadas no cache', async () => {
  const f=fixture();
  f.context.process.env.DATABASE_URL='postgres://disconnected';
  const status=await f.call('POST','/api/connections/status',{myIdentity:ID_A});
  const mark=await f.call('POST','/api/connections/mark',{fromIdentity:ID_A,toIdentity:ID_B});
  const revoke=await f.call('POST','/api/connections/unmark',{fromIdentity:ID_A,toIdentity:ID_B});
  assert.equal(status.code,503);
  assert.equal(mark.code,503);
  assert.equal(revoke.code,503);
  assert.equal(f.conns.size,0);
});

test('PostgreSQL refuses a mark if sender is not registered; no FK write or phantom connection',async()=>{
  const f=fixture({postgres:true,registeredIdentities:[ID_B]});
  const r=await f.call('POST','/api/connections/mark',{fromIdentity:ID_A,toIdentity:ID_B});
  assert.equal(r.code,409);
  assert.equal(r.payload.code,'SENDER_IDENTITY_NOT_REGISTERED');
  assert.equal(f.conns.size,0);
  assert.ok(!f.dbOps.some(op=>/INSERT INTO connections/i.test(op.sql)));
});

test('PostgreSQL refuses a mark if target is not registered; no FK write or phantom connection',async()=>{
  const f=fixture({postgres:true,registeredIdentities:[ID_A]});
  const r=await f.call('POST','/api/connections/mark',{fromIdentity:ID_A,toIdentity:ID_B});
  assert.equal(r.code,409);
  assert.equal(r.payload.code,'TARGET_IDENTITY_NOT_REGISTERED');
  assert.equal(f.conns.size,0);
  assert.ok(!f.dbOps.some(op=>/INSERT INTO connections/i.test(op.sql)));
});

test('PostgreSQL permits reciprocal consent with both identities registered',async()=>{
  const f=fixture({postgres:true,registeredIdentities:[ID_A,ID_B]});
  const a=await f.call('POST','/api/connections/mark',{fromIdentity:ID_A,toIdentity:ID_B});
  const b=await f.call('POST','/api/connections/mark',{fromIdentity:ID_B,toIdentity:ID_A});
  assert.equal(a.code,200);
  assert.equal(a.payload.isMutualConnection,false);
  assert.equal(b.code,200);
  assert.equal(b.payload.isMutualConnection,true);
  assert.ok(f.dbOps.some(op=>/INSERT INTO connections/i.test(op.sql)));
});


test('PostgreSQL snapshot ignores stale RAM links and requires reciprocal committed flags', async () => {
  const f = fixture({postgres:true, connectionRows:[
    {from_identity:ID_A, to_identity:ID_B, is_mutual:true}
  ]});
  f.conns.set(`${ID_A}->${ID_B}`, {fromIdentity:ID_A,toIdentity:ID_B,isMutual:true});
  f.conns.set(`${ID_B}->${ID_A}`, {fromIdentity:ID_B,toIdentity:ID_A,isMutual:true});
  const oneWay = await f.call('POST','/api/connections/status',{myIdentity:ID_A});
  assert.equal(oneWay.code,200);
  assert.deepEqual(Array.from(oneWay.payload.markedByMe),[ID_B]);
  assert.deepEqual(Array.from(oneWay.payload.markingMe),[]);
  assert.deepEqual(Array.from(oneWay.payload.mutual),[]);
});

test('PostgreSQL status confirms only two durable mutual directions', async () => {
  const f = fixture({postgres:true, connectionRows:[
    {from_identity:ID_A,to_identity:ID_B,is_mutual:true},
    {from_identity:ID_B,to_identity:ID_A,is_mutual:true}
  ]});
  const status = await f.call('POST','/api/connections/status',{myIdentity:ID_A});
  assert.equal(status.code,200);
  assert.deepEqual(Array.from(status.payload.mutual),[ID_B]);
});


test('PostgreSQL marking takes pair lock before mutation and uses reciprocal rows', async () => {
  const f=fixture({postgres:true,registeredIdentities:[ID_A,ID_B]});
  const first=await f.call('POST','/api/connections/mark',{fromIdentity:ID_A,toIdentity:ID_B});
  const second=await f.call('POST','/api/connections/mark',{fromIdentity:ID_B,toIdentity:ID_A});
  assert.equal(first.code,200);
  assert.equal(second.code,200);
  assert.equal(second.payload.isMutualConnection,true);
  const statements=f.dbOps.map(o=>o.sql);
  const lockIndex=statements.findIndex(sql=>sql.includes('pg_advisory_xact_lock'));
  const insertIndex=statements.findIndex(sql=>sql.includes('INSERT INTO connections'));
  assert.ok(lockIndex>=0 && insertIndex>lockIndex,'pair lock must precede the insert');
  assert.ok(statements.some(sql=>sql.includes('UPDATE connections SET is_mutual = TRUE')));
});

test('PostgreSQL revocation takes same pair lock before bilateral DELETE', async () => {
  const f=fixture({postgres:true,registeredIdentities:[ID_A,ID_B]});
  const result=await f.call('POST','/api/connections/unmark',{fromIdentity:ID_A,toIdentity:ID_B});
  assert.equal(result.code,200);
  const statements=f.dbOps.map(op=>op.sql);
  const lockIndex=statements.findIndex(sql=>sql.includes('pg_advisory_xact_lock'));
  const deleteIndex=statements.findIndex(sql=>sql.includes('DELETE FROM connections'));
  assert.ok(lockIndex>=0 && deleteIndex>lockIndex,'pair lock must precede revocation');
});
