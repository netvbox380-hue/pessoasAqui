'use strict';
const test=require('node:test'),assert=require('node:assert/strict'),crypto=require('node:crypto'),fs=require('node:fs'),vm=require('node:vm'),path=require('node:path');
const auth=require('../src/inviteAuth');
const src=fs.readFileSync(path.join(__dirname,'../src/server.js'),'utf8');
const a=src.indexOf('function digestHttpParts('),b=src.indexOf('/**\n * Heartbeat de Presença',a);
const c=src.indexOf("app.get('/api/messages/pending'"),d=src.indexOf('/**\n * Registro / Inicialização',c);
assert.ok(a>=0&&b>a&&c>=0&&d>c);
function account(){
 const kp=crypto.generateKeyPairSync('ec',{namedCurve:'prime256v1'});
 const der=kp.publicKey.export({type:'spki',format:'der'});
 return {kp,pub:der.toString('base64'),id:crypto.createHash('sha256').update(der).digest('hex').slice(0,16).toUpperCase()};
}
function proof(u){
 const timestamp=Date.now(),nonce=crypto.randomUUID();
 const canonical=auth.canonicalInviteProof('MESSAGE_PENDING',u.id,'','','',timestamp,nonce);
 const s=crypto.createSign('SHA256');s.update(canonical);s.end();
 return {v:1,publicKey:u.pub,nonce,timestamp,signature:s.sign(u.kp.privateKey).toString('base64')};
}
test('HTTP pending rejeita requisições sem prova, replay e GET legado',async()=>{
 const routes=new Map(),pendingMessages=new Map();
 vm.runInNewContext(src.slice(a,b)+'\n'+src.slice(c,d),{crypto,process:{env:{}},
  app:{get:(u,h)=>routes.set('GET '+u,h),post:(u,h)=>routes.set('POST '+u,h)},
  pendingMessages,keyId:x=>String(x).toUpperCase(),cleanId:x=>String(x).toUpperCase(),
  isPostgres:()=>false,verifyActiveInviteProof:async(...args)=>auth.verifyInviteIdentityProof(...args)});
 const user=account(),body={identityHash:user.id};pendingMessages.set(user.id,[{senderHash:'A',ciphertextPayload:'ENC_ECDH:abc'}]);
 async function send(method,body){let status=200,payload;
  await routes.get(method+' /api/messages/pending')({body},{status(v){status=v;return this},json(v){payload=v;return this}});return{status,payload};}
 assert.equal((await send('POST',body)).status,401);assert.equal(pendingMessages.size,1);
 body.identityProof=proof(user);
 let result=await send('POST',body);assert.equal(result.status,200);assert.equal(result.payload.messages.length,1);
 pendingMessages.set(user.id,[{senderHash:'A',ciphertextPayload:'ENC_ECDH:def'}]);
 assert.equal((await send('POST',body)).status,409);assert.equal(pendingMessages.size,1);
 assert.equal((await send('GET',{})).status,401);
});
