'use strict';
const test=require('node:test');
const assert=require('node:assert/strict');
const {EventEmitter}=require('node:events');
const {createR2ActiveStreamRegistry}=require('../src/r2ActiveStreamRegistry');
const {reconcileR2ActiveStreams}=require('../src/r2ActiveStreamReconciliation');

const sender='AAAABBBBCCCCDDDD',recipient='1111222233334444';
const objectKey='pending/12/34/12345678-1234-4234-8234-123456789abc';
const sha='a'.repeat(64);
function stream(overrides={}){
  const response=new EventEmitter();
  response.destroyed=false;response.writableEnded=false;
  response.destroy=function(){this.destroyed=true;this.emit('close');};
  const abortController=new AbortController();
  const reference={senderIdentity:sender,recipientIdentity:recipient,
    messageId:'message-1',objectKey,expectedSha256:sha,expectedBytes:1024,
    ...overrides};
  return {reference,response,abortController};
}
function db(options={}){
  const calls=[];
  const tx=async(sql)=>{
    calls.push(sql);
    if(options.down)throw Error('PG_DOWN');
    if(sql.includes('FROM public.connections')){
      const rows=[{from_identity:sender,to_identity:recipient,is_mutual:true},
        {from_identity:recipient,to_identity:sender,is_mutual:!options.revoked}];
      return {rowCount:options.unilateral?1:2,
        rows:options.unilateral?rows.slice(0,1):rows};
    }
    if(sql.includes('FROM public.message_r2_objects')){
      return {rowCount:options.missing?0:1,
        rows:options.missing?[]:[{
          state:'ATTACHED',content_sha256:options.modifiedSha||sha,
          content_bytes:1024,delivery_state:'PENDING',
          is_cancelled:!!options.cancelled,is_active:!options.expired
        }]};
    }
    if(sql.includes('FROM public.message_delivery_receipts'))
      return {rowCount:options.terminal?1:0,rows:options.terminal?[{state:'DELIVERED'}]:[]};
    throw Error('UNEXPECTED_QUERY');
  };
  return {calls,withTransaction:async cb=>cb(tx)};
}
test('valid active stream remains open after authoritative recheck',async()=>{
  const r=createR2ActiveStreamRegistry(),s=stream();
  r.register(s);
  const d=db(),out=await reconcileR2ActiveStreams({registry:r,withTransaction:d.withTransaction});
  assert.deepEqual(out,{scanned:1,stopped:0});
  assert.equal(r.activeCount,1);
  assert.equal(s.response.destroyed,false);
  assert.equal(d.calls.length,3);
  r.abortAll();
});
test('revoked bilateral connection aborts registered download',async()=>{
  const r=createR2ActiveStreamRegistry(),s=stream();r.register(s);
  const out=await reconcileR2ActiveStreams({registry:r,withTransaction:db({revoked:true}).withTransaction});
  assert.deepEqual(out,{scanned:1,stopped:1});
  assert.equal(r.activeCount,0);assert.equal(s.abortController.signal.aborted,true);
  assert.equal(s.response.destroyed,true);
});
test('cancelled expired terminal and missing objects are all rejected',async()=>{
  for(const options of [{cancelled:true},{expired:true},{terminal:true},
    {missing:true},{modifiedSha:'b'.repeat(64)},{unilateral:true}]){
    const r=createR2ActiveStreamRegistry(),s=stream();r.register(s);
    const out=await reconcileR2ActiveStreams({registry:r,withTransaction:db(options).withTransaction});
    assert.equal(out.stopped,1);
    assert.equal(s.response.destroyed,true);
  }
});
test('database outage fails closed and aborts ALL other sessions',async()=>{
  const r=createR2ActiveStreamRegistry({maxActive:4,maxPerRecipient:4});
  const s1=stream(),s2=stream({messageId:'message-2'});
  r.register(s1);r.register(s2);
  await assert.rejects(reconcileR2ActiveStreams({registry:r,
    withTransaction:db({down:true}).withTransaction}),/PG_DOWN/);
  assert.equal(r.activeCount,0);
  assert.equal(s1.response.destroyed,true);
  assert.equal(s2.response.destroyed,true);
});
test('legacy reference missing cryptographic descriptor aborts all streams',async()=>{
  const r=createR2ActiveStreamRegistry({maxActive:4,maxPerRecipient:4});
  const legacy=stream({objectKey:undefined,expectedSha256:undefined,expectedBytes:undefined});
  const other=stream({messageId:'other'});
  r.register(legacy);r.register(other);
  await assert.rejects(reconcileR2ActiveStreams({registry:r,
    withTransaction:db().withTransaction}),/INCOMPLETE_DESCRIPTOR/);
  assert.equal(r.activeCount,0);
  assert.equal(legacy.response.destroyed,true);
  assert.equal(other.response.destroyed,true);
});
test('abortAll is bounded, idempotent and detaches completed sessions',()=>{
  const r=createR2ActiveStreamRegistry({maxActive:4,maxPerRecipient:4});
  const s1=stream(),s2=stream({messageId:'other'});
  const h1=r.register(s1),h2=r.register(s2);
  assert.equal(r.listActive().length,2);
  const snapshot=r.listActive();
  assert.equal(snapshot[0].expectedSha256,sha);
  assert.equal('response' in snapshot[0],false);
  assert.equal('abortController' in snapshot[0],false);
  assert.equal(Object.isFrozen(snapshot),true);
  assert.equal(r.abortAll(),2);
  assert.equal(r.abortAll(),0);
  assert.equal(h1.active,false);assert.equal(h2.active,false);
  assert.equal(r.activeCount,0);
});
test('reconciliation of no active streams incurs zero PostgreSQL queries',async()=>{
  const r=createR2ActiveStreamRegistry(),d=db({down:true});
  const out=await reconcileR2ActiveStreams({registry:r,withTransaction:d.withTransaction});
  assert.deepEqual(out,{scanned:0,stopped:0});
  assert.equal(d.calls.length,0);
});
test('reconciliation rejects missing trusted backend dependencies',async()=>{
  await assert.rejects(reconcileR2ActiveStreams({
    registry:{},withTransaction:async()=>{}
  }),/Trusted R2 registry/);
  await assert.rejects(reconcileR2ActiveStreams({
    registry:createR2ActiveStreamRegistry(),withTransaction:null
  }),/Trusted R2 registry/);
});
