'use strict';
const test=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const vm=require('node:vm');

const server=fs.readFileSync(path.join(__dirname,'../src/server.js'),'utf8');
const anchor="app.post('/api/connections/unmark'";
const start=server.indexOf(anchor);
const end=server.indexOf('\n});',start);
assert.ok(start>0&&end>start);
const handler=server.slice(start,end+4);
const imports=server.slice(0,start);

test('R2 gate is required at server module scope, never in VM-extracted route',()=>{
  assert.match(imports,/require\('\.\/r2UnmarkRevocationGate'\)/);
  assert.doesNotMatch(handler,/require\(/);
});
test('legacy sandbox can evaluate and execute UNMARK without require global',async()=>{
  let route,postMutations=0;
  const context={
    app:{post:(path,fn)=>{if(path==='/api/connections/unmark')route=fn;}},
    cleanId:x=>String(x||'').toUpperCase(),keyId:x=>x,
    pairKey:(a,b)=>[a,b].sort().join(':'),revisionsEnabled:()=>false,
    isPostgres:()=>false,authorizeConnectionWrite:async()=>true,
    process:{env:{}},
    localStore:{connections:new Map()},
    revokedConnections:new Set(),
    activePresence:new Map(),activeSockets:new Map(),
    WebSocket:{OPEN:1},
    purgePendingMessagesRam:()=>{},
    Date,console
  };
  vm.runInNewContext(handler,context);
  assert.equal(typeof route,'function');
  let code=200,body;
  const res={
    status(n){code=n;return this;},
    json(value){body=value;return this;}
  };
  await route({body:{
    fromIdentity:'AABBCCDDEEFF0011',
    toIdentity:'1122334455667788'
  }},res);
  assert.equal(code,200);
  assert.equal(body.success,true);
  assert.equal(postMutations,0);
});
test('gate lookup in handler stays explicit false if VM imports are missing',()=>{
  assert.match(handler,
    /typeof r2RevocationBridgeEnabled === 'function'/);
  assert.match(handler,/\? r2RevocationBridgeEnabled\(\)/);
  assert.match(handler,/: false/);
});
