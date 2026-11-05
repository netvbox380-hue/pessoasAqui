'use strict';
const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm'),path=require('node:path');
const source=fs.readFileSync(path.join(__dirname,'../src/server.js'),'utf8');
const begin=source.indexOf('function unwrapE2eeIfCompatible('),end=source.indexOf('function deliverMessageToTarget(',begin);
assert.ok(begin>=0&&end>begin);
const block=source.slice(begin,end);
const relay=vm.runInNewContext(block+'\nunwrapE2eeIfCompatible;');
test('servidor encaminha E2EE sem decifrar conteúdo legado ou moderno',()=>{
  for(const envelope of ['ENC:dG9rZW4=','ENC_ECDH:dG9rZW4=']) {
    assert.equal(relay(envelope,'nonce','A','B'),envelope);
  }
  assert.doesNotMatch(block,/createDecipheriv|SECURE_SALT/);
});
