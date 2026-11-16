'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const { canonicalR2Action } = require('../src/r2ActionAuth');
const { admitAuthenticatedR2Read, MAX_DAILY_READS, MAX_DAILY_READ_BYTES } =
  require('../src/r2ReadAdmission');

const kp=crypto.generateKeyPairSync('ec',{namedCurve:'prime256v1'});
const publicKey=kp.publicKey.export({format:'der',type:'spki'}).toString('base64');
const actor=crypto.createHash('sha256').update(Buffer.from(publicKey,'base64'))
  .digest('hex').slice(0,16).toUpperCase();
const peer='FEDCBA9876543210';
const messageId='read-msg';
const objectKey='pending/12/34/12345678-1234-4234-8234-123456789abc';
const now=1794830000000;
function proof(){
  const timestamp=now,nonce=crypto.randomUUID();
  const data=canonicalR2Action('R2_READ',actor,peer,{messageId,objectKey},timestamp,nonce);
  const signer=crypto.createSign('SHA256');signer.update(data);signer.end();
  return {v:1,timestamp,nonce,publicKey,
    signature:signer.sign(kp.privateKey).toString('base64')};
}
function mock(opts={}){
  const calls=[];
  const tx=async(sql,args)=>{
    calls.push({sql,args});
    if(opts.down)throw Error('POSTGRES_DOWN');
    if(sql.includes('FROM public.identity_key_bindings'))return {
      rowCount:1,rows:[{public_key_spki:publicKey}]
    };
    if(sql.includes('FROM public.connections'))return {
      rowCount:opts.unilateral?1:2,
      rows:opts.unilateral?[
        {from_identity:actor,to_identity:peer,is_mutual:true}
      ]:[
        {from_identity:actor,to_identity:peer,is_mutual:true},
        {from_identity:peer,to_identity:actor,is_mutual:true}
      ]
    };
    if(sql.includes('INSERT INTO public.r2_action_nonces'))return {
      rowCount:opts.replay?0:1,rows:opts.replay?[]:[{nonce:args[1]}]
    };
    if(sql.includes('FROM public.message_r2_objects'))return {
      rowCount:opts.unknown?0:1,
      rows:opts.unknown?[]:[{
        state:opts.state||'ATTACHED',content_sha256:'a'.repeat(64),
        content_bytes:opts.bytes??1024,delivery_state:'PENDING',
        is_cancelled:opts.cancelled||false,is_active:opts.expired!==true
      }]
    };
    if(sql.includes('FROM public.message_delivery_receipts'))return {
      rowCount:opts.terminal?1:0,rows:opts.terminal?[{state:'DELIVERED'}]:[]
    };
    if(sql.includes('INSERT INTO public.r2_read_daily_budget'))return {rowCount:1,rows:[]};
    if(sql.includes('UPDATE public.r2_read_daily_budget'))return {
      rowCount:opts.quotaReached?0:1,rows:opts.quotaReached?[]:[{attempts:1,reserved_bytes:1024}]
    };
    throw Error('Unexpected SQL');
  };
  return {calls,withTransaction:async action=>action(tx)};
}
function run(m,overrides={}){
  return admitAuthenticatedR2Read({
    withTransaction:m.withTransaction,identity:actor,peerIdentity:peer,
    messageId,objectKey,proof:proof(),now,...overrides
  });
}
test('ECDSA authenticated recipient and exact object is admitted and charged once',async()=>{
  const m=mock(),r=await run(m);
  assert.equal(r.authorized,true);
  assert.equal(r.admitted,true);
  assert.equal(r.value.authenticatedIdentity,actor);
  assert.equal(r.value.expectedBytes,1024);
  assert.equal('url' in r.value,false);
  assert.equal('token' in r.value,false);
  assert.equal(m.calls.filter(x=>x.sql.includes('UPDATE public.r2_read_daily_budget')).length,1);
  assert.match(m.calls.at(-1).sql,/reserved_bytes <= \$4 - \$2/);
});
test('invalid proof and replay reject before object or quota query',async()=>{
  const m=mock(),p=proof();p.signature='not-valid';
  const bad=await run(m,{proof:p});
  assert.equal(bad.authorized,false);
  assert.equal(m.calls.some(x=>x.sql.includes('FROM public.message_r2_objects')),false);
  const replay=mock({replay:true});
  assert.equal((await run(replay)).code,'R2_PROOF_REPLAY');
  assert.equal(replay.calls.some(x=>x.sql.includes('UPDATE public.r2_read_daily_budget')),false);
});
test('unilateral connections reject before object authorization',async()=>{
  const m=mock({unilateral:true}),r=await run(m);
  assert.equal(r.code,'NO_MUTUAL_R2_CONNECTION');
  assert.equal(m.calls.some(x=>x.sql.includes('FROM public.message_r2_objects')),false);
});
test('expired cancelled unattached terminal or unknown object never charges quota',async()=>{
  for(const opts of [{expired:true},{cancelled:true},{state:'UPLOADED'},{terminal:true},{unknown:true}]){
    const m=mock(opts),r=await run(m);
    assert.equal(r.authorized,false);
    assert.equal(m.calls.some(x=>x.sql.includes('UPDATE public.r2_read_daily_budget')),false);
  }
});
test('exhausted quota rejects while retaining DB-owned nonce anti-replay',async()=>{
  const m=mock({quotaReached:true}),r=await run(m);
  assert.equal(r.authorized,false);
  assert.equal(r.code,'R2_READ_QUOTA_EXCEEDED');
  assert.equal(m.calls.filter(x=>x.sql.includes('UPDATE public.r2_read_daily_budget')).length,1);
});
test('SQL quotas use row-atomic threshold and UTC PostgreSQL clock',async()=>{
  const m=mock();await run(m);
  const insert=m.calls.find(x=>x.sql.includes('INSERT INTO public.r2_read_daily_budget'));
  const update=m.calls.find(x=>x.sql.includes('UPDATE public.r2_read_daily_budget'));
  assert.match(insert.sql,/clock_timestamp\(\) AT TIME ZONE 'UTC'/);
  assert.match(update.sql,/attempts < \$3/);
  assert.deepEqual(update.args.slice(2),[MAX_DAILY_READS,MAX_DAILY_READ_BYTES]);
});
test('database outage propagates without untrusted RAM fallback',async()=>{
  await assert.rejects(run(mock({down:true})),/POSTGRES_DOWN/);
});
test('no DB callback or malformed input fails closed',async()=>{
  await assert.rejects(admitAuthenticatedR2Read({
    withTransaction:null,identity:actor,peerIdentity:peer,messageId,objectKey,proof:proof()
  }),/transaction required/);
  const m=mock(),r=await run(m,{objectKey:'../invalid'});
  assert.equal(r.authorized,false);
  assert.equal(m.calls.length,0);
});
