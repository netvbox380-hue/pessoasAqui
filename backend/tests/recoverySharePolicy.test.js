'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const {MIN_SECRET_RECOVERY_FAMILIES,validateShareRelease} = require('../src/recoverySharePolicy');

const base = {
  issuer:'FAMILY_A', approver:'FAMILY_B', releaser:'FAMILY_B',
  activeDelegates:['FAMILY_A','FAMILY_B','FAMILY_C'],
  alreadyReleasedBy:[],
  preparedDeviceIdentity:'NEW_DEVICE',
  claimantIdentity:'NEW_DEVICE',
  issuedAt:1000,
  expiresAt:10000,
  now:2000
};

test('PIN e segredo V3 exigem 3 familiares autorizados distintos',()=>{
  assert.equal(MIN_SECRET_RECOVERY_FAMILIES,3);
  assert.equal(validateShareRelease(base),true);
  assert.equal(validateShareRelease({...base,activeDelegates:['FAMILY_A','FAMILY_B']}),false);
  assert.equal(validateShareRelease({...base,activeDelegates:['FAMILY_A','FAMILY_B','FAMILY_B']}),false);
});
test('familiar emissor do QR/LINK nunca libera parte V3',()=>{
  assert.equal(validateShareRelease({...base,releaser:'FAMILY_A'}),false);
  assert.equal(validateShareRelease({...base,approver:'FAMILY_A'}),false);
  assert.equal(validateShareRelease({...base,issuer:'FAMILY_C',activeDelegates:['FAMILY_A','FAMILY_B']}),false);
});
test('duas partes devem vir de guardiões distintos',()=>{
  assert.equal(validateShareRelease({...base,alreadyReleasedBy:['FAMILY_B'],releaser:'FAMILY_B'}),false);
  assert.equal(validateShareRelease({...base,alreadyReleasedBy:['FAMILY_B'],releaser:'FAMILY_C'}),true);
  assert.equal(validateShareRelease({...base,alreadyReleasedBy:['FAMILY_B','FAMILY_C'],releaser:'FAMILY_C'}),false);
  assert.equal(validateShareRelease({...base,alreadyReleasedBy:['FAMILY_A'],releaser:'FAMILY_C'}),false);
});
test('autorização só vale ao aparelho preparado e na janela ativa',()=>{
  assert.equal(validateShareRelease({...base,claimantIdentity:'OTHER_DEVICE'}),false);
  assert.equal(validateShareRelease({...base,now:999}),false);
  assert.equal(validateShareRelease({...base,now:10000}),false);
  assert.equal(validateShareRelease({...base,expiresAt:NaN}),false);
});
test('delegação revogada impede novo release',()=>{
  assert.equal(validateShareRelease({...base,activeDelegates:['FAMILY_A','FAMILY_C','FAMILY_D']}),false);
  assert.equal(validateShareRelease({...base,activeDelegates:['FAMILY_A','FAMILY_B','FAMILY_D'],releaser:'FAMILY_C'}),false);
});
