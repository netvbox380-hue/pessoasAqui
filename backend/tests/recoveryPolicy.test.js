'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const {eligibility,canClaim,MIN_FAMILIES,LINK_WAIT_MS} = require('../src/recoveryPolicy');
const {canonicalInviteProof,verifyInviteIdentityProof} = require('../src/inviteAuth');

function actor(){
  const key=crypto.generateKeyPairSync('ec',{namedCurve:'prime256v1'});
  const pub=key.publicKey.export({type:'spki',format:'der'}).toString('base64');
  return {key,pub,id:crypto.createHash('sha256').update(Buffer.from(pub,'base64'))
    .digest('hex').slice(0,16).toUpperCase()};
}
function proof(who,operation,alias='',tokenHash=''){
  const timestamp=Date.now(),nonce=crypto.randomUUID();
  const m=canonicalInviteProof(operation,who.id,alias,'',tokenHash,timestamp,nonce);
  const s=crypto.createSign('SHA256');s.update(m);s.end();
  return {v:1,publicKey:who.pub,timestamp,nonce,
    signature:s.sign(who.key.privateKey).toString('base64')};
}
test('LINK exige 1 hora, QR permite conclusão após dois avales',()=>{
  const now=1_700_000_000_000;
  const link=eligibility('LINK',now),qr=eligibility('QR',now);
  assert.equal(link.eligibleAt-now,LINK_WAIT_MS);
  assert.equal(qr.eligibleAt,now);
  const common={issuer:'A',approver:'B',activeDelegates:['A','B'],now};
  assert.equal(canClaim({...common,eligibleAt:link.eligibleAt,expiresAt:link.expiresAt}),false);
  assert.equal(canClaim({...common,eligibleAt:qr.eligibleAt,expiresAt:qr.expiresAt}),true);
  assert.equal(canClaim({...common,now:link.eligibleAt,eligibleAt:link.eligibleAt,expiresAt:link.expiresAt}),true);
});
test('um familiar jamais conclui os dois papéis e delegações revogadas invalidam',()=>{
  const args={issuer:'A',approver:'A',activeDelegates:['A','B'],
    now:100,eligibleAt:0,expiresAt:200};
  assert.equal(MIN_FAMILIES,2);
  assert.equal(canClaim(args),false);
  assert.equal(canClaim({...args,approver:'B',activeDelegates:['A']}),false);
  assert.equal(canClaim({...args,approver:'B',activeDelegates:['B','C']}),false);
  assert.equal(canClaim({...args,approver:'B',activeDelegates:['A','B']}),true);
  assert.equal(canClaim({...args,approver:'B',activeDelegates:['A','B'],now:200}),false);
});
test('métodos assinados não podem ser reetiquetados nem reutilizados',()=>{
  const a=actor(),b=actor(),tok=crypto.randomBytes(32).toString('hex');
  const qr=proof(a,'RECOVERY_ISSUE',b.id);
  assert.equal(verifyInviteIdentityProof('RECOVERY_ISSUE',a.id,b.id,'','',qr),true);
  assert.equal(verifyInviteIdentityProof('RECOVERY_ISSUE_LINK',a.id,b.id,'','',qr),false);
  const link=proof(a,'RECOVERY_ISSUE_LINK',b.id);
  assert.equal(verifyInviteIdentityProof('RECOVERY_ISSUE_LINK',a.id,b.id,'','',link),true);
  const sec=proof(b,'RECOVERY_APPROVE',a.id,tok);
  assert.equal(verifyInviteIdentityProof('RECOVERY_APPROVE',b.id,a.id,'',tok,sec),true);
  assert.equal(verifyInviteIdentityProof('RECOVERY_APPROVE',b.id,b.id,'',tok,sec),false);
  const prepared=proof(a,'RECOVERY_PREPARE','',tok);
  assert.equal(verifyInviteIdentityProof('RECOVERY_PREPARE',a.id,'','',tok,prepared),true);
  assert.equal(verifyInviteIdentityProof('RECOVERY_CLAIM',a.id,'','',tok,prepared),false);
});
