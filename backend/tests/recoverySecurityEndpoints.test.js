'use strict';
const test=require('node:test'),assert=require('node:assert/strict'),crypto=require('node:crypto');
const {canonicalInviteProof,verifyInviteIdentityProof}=require('../src/inviteAuth');
const {eligibility,canClaim}=require('../src/recoveryPolicy');
function who(){
  const keys=crypto.generateKeyPairSync('ec',{namedCurve:'prime256v1'});
  const pub=keys.publicKey.export({type:'spki',format:'der'}).toString('base64');
  const identity=crypto.createHash('sha256').update(Buffer.from(pub,'base64'))
    .digest('hex').slice(0,16).toUpperCase();
  return {keys,pub,identity};
}
function proof(actor,op,alias,hash){
  const nonce=crypto.randomUUID(),timestamp=Date.now();
  const payload=canonicalInviteProof(op,actor.identity,alias,'',hash,timestamp,nonce);
  const signer=crypto.createSign('SHA256');signer.update(payload,'utf8');signer.end();
  return {v:1,publicKey:actor.pub,nonce,timestamp,
    signature:signer.sign(actor.keys.privateKey).toString('base64')};
}
const digestParts=(...parts)=>crypto.createHash('sha256').update(
  parts.map(s=>String(s).length+':'+String(s)).join('|')).digest('hex');
test('denúncia familiar é vinculada à vítima, relato e identidade autenticada',()=>{
  const maria=who(),joao=who();
  const hash=digestParts(maria.identity,joao.identity,'Maria','SECURITY_RECOVERY',
    'Perdi contato com João. Recuperação suspeita.');
  const signed=proof(maria,'FAMILY_INCIDENT',joao.identity,hash);
  assert.equal(verifyInviteIdentityProof('FAMILY_INCIDENT',maria.identity,
    joao.identity,'',hash,signed),true);
  assert.equal(verifyInviteIdentityProof('FAMILY_INCIDENT',maria.identity,
    maria.identity,'',hash,signed),false);
  assert.equal(verifyInviteIdentityProof('FAMILY_INCIDENT',maria.identity,
    joao.identity,'',digestParts('relato trocado'),signed),false);
});
test('consulta de prontidão e contestação só aceitam provas próprias',()=>{
  const joao=who();
  for(const operation of ['RECOVERY_READINESS','RECOVERY_CONTEST']){
    const signed=proof(joao,operation,'','');
    assert.equal(verifyInviteIdentityProof(operation,joao.identity,'','',
      '',signed),true);
    assert.equal(verifyInviteIdentityProof(operation,joao.identity,'','',
      '00'.repeat(32),signed),false);
  }
});
test('LINK precisa aguardar 3600000ms e vencer antes de qualquer sessão expirada',()=>{
  const issueTime=Date.now(),link=eligibility('LINK',issueTime);
  const args={issuer:'Maria',approver:'Pedro',activeDelegates:['Maria','Pedro'],
    now:issueTime,eligibleAt:link.eligibleAt,expiresAt:link.expiresAt};
  assert.equal(canClaim(args),false);
  assert.equal(canClaim({...args,now:issueTime+3600000}),true);
  assert.equal(canClaim({...args,now:link.expiresAt}),false);
});
