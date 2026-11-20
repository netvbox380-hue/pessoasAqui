'use strict';
const test=require('node:test');
const assert=require('node:assert/strict');
const {EventEmitter}=require('node:events');
const {createR2RevocationListener}=require('../src/r2RevocationListener');
const {CHANNEL}=require('../src/r2RevocationOutbox');
const uuid='12345678-1234-4234-8234-123456789abc';
const A='AAAABBBBCCCCDDDD',B='1111222233334444';

function fixture(options={}){
  const clients=[],calls=[],stopped=[];
  const registry={
    activeCount:options.activeCount??1,
    revokeMessage:msg=>{stopped.push(['MESSAGE',msg]);return 1;},
    revokePair:(a,b)=>{stopped.push(['PAIR',a,b]);return 1;}
  };
  function client(){
    const c=new EventEmitter();
    c.closed=false;
    c.query=async(sql,args)=>{
      calls.push({sql,args});
      if(options.queryFails&&sql.includes('FROM public.r2_stream_revocation_events'))
        throw Error('DB_DOWN');
      if(sql.startsWith('LISTEN'))return {rowCount:0,rows:[]};
      if(sql.includes('FROM public.r2_stream_revocation_events'))return {
        rowCount:options.missing?0:1,
        rows:options.missing?[]:[{
          event_kind:'PAIR',message_id:null,identity_a:A,identity_b:B
        }]
      };
      throw Error('Unexpected SQL');
    };
    c.end=async()=>{c.closed=true;};
    clients.push(c);
    return c;
  }
  let fails=0,checks=0;
  const timers=[];
  const opts={
    connect:async()=>client(),registry,
    reconcile:async()=>{checks++;if(options.reconcileFails)throw Error('RECONCILE_DOWN');},
    failClosed:()=>{fails++;},
    setTimer:(fn,delay)=>{const id={fn,delay};timers.push(id);return id;},
    clearTimer:id=>{const ix=timers.indexOf(id);if(ix>=0)timers.splice(ix,1);},
    random:()=>0.5
  };
  return {opts,clients,calls,stopped,timers,registry,
    get fails(){return fails;},get checks(){return checks;}};
}
async function settle(){for(let i=0;i<8;i++)await new Promise(resolve=>setImmediate(resolve));}
test('listener starts fail closed, owns dedicated client and reconciles before ready',async()=>{
  const f=fixture(),l=createR2RevocationListener(f.opts);
  assert.equal(l.ready,false);
  l.start();await settle();
  assert.equal(f.fails,1);
  assert.equal(l.ready,true);
  assert.equal(f.checks,2);
  assert.equal(f.clients.length,1);
  assert.equal(f.calls.filter(c=>c.sql.startsWith('LISTEN')).length,1);
  await l.stop();
  assert.equal(f.clients[0].closed,true);
  assert.equal(l.ready,false);
});
test('verified outbox notification cancels matching registry stream',async()=>{
  const f=fixture(),l=createR2RevocationListener(f.opts);
  l.start();await settle();
  f.clients[0].emit('notification',{channel:CHANNEL,payload:uuid});
  await settle();
  assert.deepEqual(f.stopped,[['PAIR',A,B]]);
  assert.equal(l.pending,0);
  await l.stop();
});
test('notifications with wrong channel never access DB',async()=>{
  const f=fixture(),l=createR2RevocationListener(f.opts);
  l.start();await settle();
  const before=f.calls.length;
  f.clients[0].emit('notification',{channel:'wrong',payload:uuid});
  await settle();
  assert.equal(f.calls.length,before);
  await l.stop();
});
test('no active streams: no event lookup and no costs',async()=>{
  const f=fixture({activeCount:0}),l=createR2RevocationListener(f.opts);
  l.start();await settle();
  const before=f.calls.length;
  f.clients[0].emit('notification',{channel:CHANNEL,payload:uuid});
  await settle();
  assert.equal(f.calls.length,before);
  await l.stop();
});
test('malformed notification fails closed and schedules reconnection',async()=>{
  const f=fixture(),l=createR2RevocationListener(f.opts);
  l.start();await settle();
  f.clients[0].emit('notification',{channel:CHANNEL,payload:'forged'});
  await settle();
  assert.equal(l.ready,false);
  assert.ok(f.fails>=2);
  assert.equal(f.timers.length,1);
  await l.stop();
});
test('missing outbox row fails closed rather than dispatching arbitrary event',async()=>{
  const f=fixture({missing:true}),l=createR2RevocationListener(f.opts);
  l.start();await settle();
  f.clients[0].emit('notification',{channel:CHANNEL,payload:uuid});
  await settle();
  assert.equal(l.ready,false);
  assert.equal(f.stopped.length,0);
  assert.ok(f.fails>=2);
  await l.stop();
});
test('network disconnect closes old client then retries with reconciliation',async()=>{
  const f=fixture(),l=createR2RevocationListener(f.opts);
  l.start();await settle();
  f.clients[0].emit('end');await settle();
  assert.equal(l.ready,false);
  assert.equal(f.timers.length,1);
  const timer=f.timers.shift();timer.fn();await settle();
  assert.equal(l.ready,true);
  assert.equal(f.clients.length,2);
  assert.equal(f.checks,4);
  await l.stop();
});
test('database outage on reconciliation is always fail closed',async()=>{
  const f=fixture({reconcileFails:true}),l=createR2RevocationListener(f.opts);
  l.start();await settle();
  assert.equal(l.ready,false);
  assert.equal(f.timers.length,1);
  assert.ok(f.fails>=2);
  await l.stop();
});
test('invalid listener dependencies are rejected before startup',()=>{
  assert.throws(()=>createR2RevocationListener({connect:()=>({})}),/Invalid/);
});
