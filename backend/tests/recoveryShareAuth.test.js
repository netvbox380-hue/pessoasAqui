'use strict';
const test=require('node:test');
const assert=require('node:assert/strict');
const crypto=require('node:crypto');
const {canonicalInviteProof,verifyInviteIdentityProof}=require('../src/inviteAuth');

test('release share: domain separated signed proof required',()=>{
  const kp=crypto.generateKeyPairSync('ec',{namedCurve:'prime256v1'});
  const publicKey=kp.publicKey.export({format:'der',type:'spki'}).toString('base64');
  const id=crypto.createHash('sha256').update(Buffer.from(publicKey,'base64'))
    .digest('hex').slice(0,16).toUpperCase();
  const target='A'.repeat(16);
  const hash=crypto.createHash('sha256').update('PA-REC-'+'F'.repeat(64)).digest('hex');
  const timestamp=Date.now(),nonce=crypto.randomUUID();
  const operation='RECOVERY_SHARE_RELEASE';
  const signed=canonicalInviteProof(operation,id,target,'',hash,timestamp,nonce);
  const sig=crypto.createSign('SHA256');sig.update(signed);sig.end();
  const proof={v:1,publicKey,timestamp,nonce,
    signature:sig.sign(kp.privateKey).toString('base64')};
  assert.equal(verifyInviteIdentityProof(operation,id,target,'',hash,proof),true);
  assert.equal(verifyInviteIdentityProof('RECOVERY_GUARDIANS',id,target,'',hash,proof),false);
  assert.equal(verifyInviteIdentityProof(operation,id,'B'.repeat(16),'',hash,proof),false);
  assert.equal(verifyInviteIdentityProof(operation,id,target,'','0'.repeat(64),proof),false);
});
