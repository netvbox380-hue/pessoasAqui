'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const {canonicalInviteProof, verifyInviteIdentityProof} = require('../src/inviteAuth');

function actor() {
  const pair = crypto.generateKeyPairSync('ec', {namedCurve: 'prime256v1'});
  const publicKey = pair.publicKey.export({format:'der',type:'spki'}).toString('base64');
  const identity = crypto.createHash('sha256').update(Buffer.from(publicKey,'base64'))
    .digest('hex').slice(0,16).toUpperCase();
  return {pair, publicKey, identity};
}
function proof(actor, operation, alias, tokenHash = '', timestamp = Date.now()) {
  const nonce = crypto.randomUUID();
  const message = canonicalInviteProof(operation,actor.identity,alias,'',tokenHash,timestamp,nonce);
  const signer = crypto.createSign('SHA256');
  signer.update(message,'utf8'); signer.end();
  return {v:1,publicKey:actor.publicKey,timestamp,nonce,
    signature:signer.sign(actor.pair.privateKey).toString('base64')};
}
test('denúncia aceita prova assinada vinculada ao denunciado e conteúdo', () => {
  const reporter=actor(), accused=actor(), digest=crypto.randomBytes(32).toString('hex');
  const p=proof(reporter,'REPORT_CREATE',accused.identity,digest);
  assert.equal(verifyInviteIdentityProof('REPORT_CREATE',reporter.identity,
    accused.identity,'',digest,p),true);
  assert.equal(verifyInviteIdentityProof('REPORT_CREATE',reporter.identity,
    actor().identity,'',digest,p),false);
  assert.equal(verifyInviteIdentityProof('REPORT_CREATE',reporter.identity,
    accused.identity,'',crypto.randomBytes(32).toString('hex'),p),false);
  assert.equal(verifyInviteIdentityProof('REPORT_CREATE',reporter.identity,
    accused.identity,'',digest,{...p,signature:'not-a-signature'}),false);
});
test('consulta de chave exige assinatura sobre a identidade alvo', () => {
  const requester=actor(), target=actor();
  const p=proof(requester,'IDENTITY_KEY_LOOKUP',target.identity);
  assert.equal(verifyInviteIdentityProof('IDENTITY_KEY_LOOKUP',requester.identity,
    target.identity,'','',p),true);
  assert.equal(verifyInviteIdentityProof('IDENTITY_KEY_LOOKUP',requester.identity,
    actor().identity,'','',p),false);
  assert.equal(verifyInviteIdentityProof('IDENTITY_KEY_LOOKUP',requester.identity,
    target.identity,'','bad',p),false);
});
