'use strict';
const test=require('node:test'),assert=require('node:assert/strict'),crypto=require('node:crypto');
const {identityFromPublicKey,verifyWsIdentity}=require('../src/identityAuth');
function user(){
 const pair=crypto.generateKeyPairSync('ec',{namedCurve:'prime256v1'});
 const pub=pair.publicKey.export({format:'der',type:'spki'}).toString('base64');
 return {pair,pub,id:identityFromPublicKey(pub)};
}
function sign(who,id,challenge){
 const s=crypto.createSign('SHA256');s.update('PA-WS-AUTH-V2|'+id+'|'+challenge);s.end();
 return s.sign(who.pair.privateKey).toString('base64');
}
test('WebSocket rejeita ausência de prova, falsificação, replay e chave revogada',()=>{
 const a=user(),b=user(),challenge=crypto.randomBytes(24).toString('hex');
 const proof=sign(a,a.id,challenge);
 assert.equal(verifyWsIdentity(a.id,a.pub,proof,challenge,challenge),true);
 assert.equal(verifyWsIdentity(a.id,a.pub,'',challenge,challenge),false);
 assert.equal(verifyWsIdentity(a.id,b.pub,sign(b,a.id,challenge),challenge,challenge),false);
 assert.equal(verifyWsIdentity(a.id,a.pub,proof,challenge,crypto.randomBytes(24).toString('hex')),false);
 assert.equal(verifyWsIdentity(a.id,a.pub,proof,challenge,challenge,b.pub),false);
 assert.equal(verifyWsIdentity(a.id,b.pub,sign(b,a.id,challenge),challenge,challenge,b.pub),true);
});
