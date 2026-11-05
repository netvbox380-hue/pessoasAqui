'use strict';
const test=require('node:test');
const assert=require('node:assert/strict');
const crypto=require('node:crypto');
const {hashPin,verifyPinHash}=require('../src/crypto');

test('PIN v2 usa salt único e não armazena PIN recuperável em texto',()=>{
  const one=hashPin('987654'),two=hashPin('987654');
  assert.match(one,/^pbkdf2v2\$[a-f0-9]{32}\$[a-f0-9]{64}$/);
  assert.notEqual(one,two);
  assert.equal(verifyPinHash('987654',one),true);
  assert.equal(verifyPinHash('000000',one),false);
  assert.equal(verifyPinHash('987654',two),true);
});

test('V1 PBKDF2 e SHA legados continuam válidos apenas na leitura',()=>{
  const legacyPin='2048';
  const legacySalt='PA_SALT_2026_';
  const oldPb='pbkdf2$'+crypto.pbkdf2Sync(legacyPin,legacySalt,10000,32,'sha256').toString('hex');
  const oldSha=crypto.createHash('sha256').update(legacySalt+legacyPin).digest('hex');
  assert.equal(verifyPinHash(legacyPin,oldPb),true);
  assert.equal(verifyPinHash(legacyPin,oldSha),true);
  assert.equal(verifyPinHash('9999',oldPb),false);
  assert.equal(verifyPinHash('9999',oldSha),false);
  assert.match(hashPin(legacyPin),/^pbkdf2v2\$/);
});

test('hashes malformados e de prefixos desconhecidos são rejeitados',()=>{
  for(const payload of ['',null,'pbkdf2$nothex','pbkdf2v2$123$abcdef','md5$12345']) {
    assert.equal(verifyPinHash('987654',payload),false);
  }
});
