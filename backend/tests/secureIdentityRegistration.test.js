'use strict';
const test=require('node:test'),assert=require('node:assert/strict'),crypto=require('node:crypto'),fs=require('node:fs'),vm=require('node:vm'),path=require('node:path');
const auth=require('../src/inviteAuth'),{hashPin}=require('../src/crypto');
const src=fs.readFileSync(path.join(__dirname,'../src/server.js'),'utf8').replace(/\r\n/g, '\n');
const start=src.indexOf("app.post('/api/identities/register'"),stop=src.indexOf('/**\n * Consulta de Status',start);
assert.ok(start>=0 && stop>start);
function fixture(){
 const handlers=new Map(),identities=new Map();
 const context={app:{post:(u,h)=>handlers.set(u,h)},localStore:{identities},crypto,hashPin,process:{env:{}},
   cleanId:s=>String(s||'').trim().toUpperCase(),isPostgres:()=>false,
   verifyActiveInviteProof:async(...args)=>auth.verifyInviteIdentityProof(...args)};
 vm.runInNewContext(src.slice(start,stop),context);
 return async body=>{let status=200,payload;
  await handlers.get('/api/identities/register')({body},{status(v){status=v;return this},json(v){payload=v;return this}});
  return {status,payload,identities};
 };
}
function owner(){
 const kp=crypto.generateKeyPairSync('ec',{namedCurve:'prime256v1'});
 const der=kp.publicKey.export({type:'spki',format:'der'});
 const id=crypto.createHash('sha256').update(der).digest('hex').slice(0,16).toUpperCase();
 const alias='Maria',nonce=crypto.randomUUID(),timestamp=Date.now();
 const canonical=auth.canonicalInviteProof('IDENTITY_REGISTER',id,alias,'','',timestamp,nonce);
 const sig=crypto.createSign('SHA256');sig.update(canonical);sig.end();
 return {id,alias,proof:{v:1,publicKey:der.toString('base64'),timestamp,nonce,signature:sig.sign(kp.privateKey).toString('base64')}};
}
test('identidade precisa comprovar posse da chave P-256',async()=>{
 const call=fixture(),a=owner();
 let result=await call({identityHash:a.id,alias:a.alias});
 assert.equal(result.status,401);assert.equal(result.identities.size,0);
 result=await call({identityHash:'AAAAAAAAAAAAAAAA',alias:a.alias,identityProof:a.proof});
 assert.equal(result.status,401);
 result=await call({identityHash:a.id,alias:a.alias,identityProof:a.proof});
 assert.equal(result.status,200);assert.equal(result.identities.size,1);
});
