'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const { canonicalInviteProof, verifyInviteIdentityProof } = require('../src/inviteAuth');

function signedVisibility(visible) {
  const pair = crypto.generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
  const der = pair.publicKey.export({ type: 'spki', format: 'der' });
  const publicKey = der.toString('base64');
  const identity = crypto.createHash('sha256').update(der).digest('hex').slice(0, 16).toUpperCase();
  const alias = 'Teste';
  const inviteId = '';
  const tokenHash = crypto.createHash('sha256').update(String(visible)).digest('hex');
  const timestamp = Date.now();
  const nonce = crypto.randomUUID();
  const canonical = canonicalInviteProof('PRESENCE_VISIBILITY', identity, alias, inviteId, tokenHash, timestamp, nonce);
  const signer = crypto.createSign('SHA256');
  signer.update(canonical, 'utf8');
  signer.end();
  const proof = { v: 1, publicKey, timestamp, nonce, signature: signer.sign(pair.privateKey).toString('base64') };
  return { identity, alias, inviteId, tokenHash, proof };
}

test('PRESENCE_VISIBILITY accepts a valid signed SHA-256 proof for true and false', () => {
  for (const visible of [true, false]) {
    const p = signedVisibility(visible);
    assert.equal(verifyInviteIdentityProof('PRESENCE_VISIBILITY', p.identity, p.alias,
      p.inviteId, p.tokenHash, p.proof), true);
  }
});

test('PRESENCE_VISIBILITY rejects missing or malformed SHA-256 token hash', () => {
  const p = signedVisibility(true);
  assert.equal(verifyInviteIdentityProof('PRESENCE_VISIBILITY', p.identity, p.alias,
    p.inviteId, '', p.proof), false);
  assert.equal(verifyInviteIdentityProof('PRESENCE_VISIBILITY', p.identity, p.alias,
    p.inviteId, 'not-a-sha256', p.proof), false);
});
