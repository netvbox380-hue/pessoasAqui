'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const {
  canonicalR2Action, verifyR2ActionProof, executeAuthenticatedR2Action,
  PROOF_WINDOW_MS
} = require('../src/r2ActionAuth');

const keyPair = crypto.generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
const otherPair = crypto.generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
const publicKey = keyPair.publicKey.export({ type: 'spki', format: 'der' }).toString('base64');
const identity = crypto.createHash('sha256')
  .update(Buffer.from(publicKey, 'base64')).digest('hex').slice(0, 16).toUpperCase();
const peer = 'FEDCBA9876543210';
const objectKey = 'pending/12/34/12345678-1234-4234-8234-123456789abc';
const fields = Object.freeze({messageId:'msg-r2-1', objectKey});
const now = 1794823200000; // stable test clock, not a production timestamp

function createProof(operation = 'R2_RESERVE', f = fields, opts = {}) {
  const timestamp = opts.timestamp ?? now;
  const nonce = opts.nonce ?? crypto.randomUUID();
  const signedIdentity = opts.identity ?? identity;
  const signedPeer = opts.peerIdentity ?? peer;
  const bytes = canonicalR2Action(operation, signedIdentity, signedPeer, f, timestamp, nonce);
  const signer = crypto.createSign('SHA256');
  signer.update(bytes, 'utf8');
  signer.end();
  return {
    v:1,publicKey:opts.publicKey ?? publicKey,timestamp,nonce,
    signature:signer.sign(opts.privateKey ?? keyPair.privateKey).toString('base64')
  };
}
function dbMock(options = {}) {
  const calls = [];
  const forward = {from_identity:identity,to_identity:peer,is_mutual:true};
  const reverse = {from_identity:peer,to_identity:identity,is_mutual:true};
  const tx = async (sql,params) => {
    calls.push({sql,params});
    if (options.dbError) throw Error('POSTGRES_DOWN');
    if (sql.includes('FROM public.identity_key_bindings')) {
      if(options.noBinding)return {rowCount:0,rows:[]};
      return {rowCount:1,rows:[{public_key_spki:options.activeKey ?? publicKey}]};
    }
    if (sql.includes('FROM public.connections')) {
      const rows = options.noMutual ? [forward] : [forward,reverse];
      return {rowCount:rows.length,rows};
    }
    if (sql.includes('INSERT INTO public.r2_action_nonces')) {
      if(options.missingNonceTable)throw Error('relation does not exist');
      return {rowCount:options.replay?0:1,rows:options.replay?[]:[{nonce:params[1]}]};
    }
    if (sql.includes('UPDATE public.message_r2_objects')) {
      return {rowCount:1,rows:[]};
    }
    throw Error('UNEXPECTED_SQL');
  };
  return {calls,withTransaction: async fn=>fn(tx)};
}
function run(db,proof, extra = {}) {
  return executeAuthenticatedR2Action({
    withTransaction:db.withTransaction,operation:'R2_RESERVE',identity,
    peerIdentity:peer,fields,proof,now,
    action:async(tx,ctx)=>{
      assert.equal(ctx.authenticatedIdentity,identity);
      assert.equal(ctx.peerIdentity,peer);
      await tx('UPDATE public.message_r2_objects SET state=state',[]);
      return 'DB_ACTION_OK';
    },...extra
  });
}
test('canonical signed body binds actor, peer, operation and exact fields',()=>{
  const proof=createProof();
  assert.ok(verifyR2ActionProof({
    operation:'R2_RESERVE',identity,peerIdentity:peer,fields,proof,activePublicKey:publicKey,now
  }));
  assert.equal(verifyR2ActionProof({
    operation:'R2_READ',identity,peerIdentity:peer,fields,proof,activePublicKey:publicKey,now
  }),false);
  assert.equal(verifyR2ActionProof({
    operation:'R2_RESERVE',identity,peerIdentity:peer,
    fields:{...fields,messageId:'different'},proof,activePublicKey:publicKey,now
  }),false);
  assert.equal(verifyR2ActionProof({
    operation:'R2_RESERVE',identity,peerIdentity:'ABCDEF0123456789',
    fields,proof,activePublicKey:publicKey,now
  }),false);
});
test('verified P-256 key and bilateral state execute authorized action after nonce insertion',async()=>{
  const db=dbMock();
  const result=await run(db,createProof());
  assert.deepEqual(result,{authorized:true,value:'DB_ACTION_OK'});
  assert.equal(db.calls.length,4);
  assert.match(db.calls[0].sql,/FOR SHARE/);
  assert.match(db.calls[1].sql,/FOR SHARE/);
  assert.match(db.calls[2].sql,/r2_action_nonces/);
});
test('reused nonce is rejected before any action callback',async()=>{
  const db=dbMock({replay:true});
  const result=await run(db,createProof());
  assert.equal(result.code,'R2_PROOF_REPLAY');
  assert.equal(db.calls.length,3);
});
test('absent key binding and other active key fail closed',async()=>{
  for(const opts of [{noBinding:true},{activeKey:otherPair.publicKey.export({type:'spki',format:'der'}).toString('base64')}]) {
    const db=dbMock(opts);
    const result=await run(db,createProof());
    assert.equal(result.code,'INVALID_R2_IDENTITY_PROOF');
    assert.equal(db.calls.length,1);
  }
});
test('unilateral relationship denies R2 transfer before nonce use',async()=>{
  const db=dbMock({noMutual:true});
  const result=await run(db,createProof());
  assert.equal(result.code,'NO_MUTUAL_R2_CONNECTION');
  assert.equal(db.calls.length,2);
});
test('invalid signature, timestamp, nonce, unknown fields and generation fail closed',async()=>{
  const broken=createProof();
  broken.signature=Buffer.from('forged').toString('base64');
  for(const [proof, extra] of [
    [broken,{}],
    [createProof('R2_RESERVE',fields,{timestamp:now-PROOF_WINDOW_MS-1}),{}],
    [{...createProof(),nonce:'not-a-uuid'},{}],
    [createProof(),{fields:{...fields,requireLeasedUpload:false}}],
    [createProof(),{identity:peer}],
    [createProof(),{operation:'NOT_ALLOWED'}]
  ]) {
    const db=dbMock();
    const result=await run(db,proof,extra);
    assert.equal(result.authorized,false);
    assert.equal(db.calls.some(x=>x.sql.includes('UPDATE public.message_r2_objects')),false);
  }
  assert.throws(()=>canonicalR2Action('R2_CONFIRM',identity,peer,{
    messageId:fields.messageId,objectKey,generation:2,
    contentSha256:'a'.repeat(64),contentBytes:10
  },now,crypto.randomUUID()),/Invalid R2 generation/);
});
test('database outage or missing nonce migration never falls back to memory',async()=>{
  for(const opts of [{dbError:true},{missingNonceTable:true}]) {
    const db=dbMock(opts);
    await assert.rejects(run(db,createProof()));
  }
});
test('encrypted descriptor digest is signed in attach contract',()=>{
  const attach={
    messageId:fields.messageId,objectKey,generation:1,
    contentSha256:'a'.repeat(64),contentBytes:123,
    ciphertextDigest:'b'.repeat(64)
  };
  const proof=createProof('R2_ATTACH',attach);
  assert.equal(verifyR2ActionProof({
    operation:'R2_ATTACH',identity,peerIdentity:peer,
    fields:attach,proof,activePublicKey:publicKey,now
  }),true);
  assert.equal(verifyR2ActionProof({
    operation:'R2_ATTACH',identity,peerIdentity:peer,
    fields:{...attach,ciphertextDigest:'c'.repeat(64)},
    proof,activePublicKey:publicKey,now
  }),false);
});
