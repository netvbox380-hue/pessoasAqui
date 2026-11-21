'use strict';
const test=require('node:test');
const assert=require('node:assert/strict');
const {EventEmitter}=require('node:events');
const {createStagedR2DeliveryRuntime}=require('../src/r2StagedDeliveryRuntime');
const wait=async()=>{for(let i=0;i<8;i++)await new Promise(r=>setImmediate(r));};
function fixture(){
  const clients=[];
  let connects=0,transactions=0;
  const connectDedicated=async()=>{
    connects++;
    const c=new EventEmitter();
    c.end=async()=>{c.closed=true;};
    c.query=async(sql)=>{if(sql.startsWith('LISTEN'))return {rowCount:0,rows:[]};
      throw Error('UNEXPECTED_LISTENER_SQL');};
    clients.push(c);return c;
  };
  const withTransaction=async()=>{transactions++;throw Error('UNEXPECTED_DB_QUERY');};
  return {connectDedicated,withTransaction,clients,
    get connects(){return connects;},get transactions(){return transactions;}};
}
test('factory stays inert until explicit start and rejects reads while disconnected',async()=>{
  const f=fixture(),runtime=createStagedR2DeliveryRuntime(f);
  assert.equal(runtime.ready,false);
  assert.equal(f.connects,0);
  const out=await runtime.stream({admission:null,response:{
    write(){},end(){},destroy(){}},openCiphertext:async()=>{throw Error('GET_CALLED');}});
  assert.equal(out.code,'R2_PRIOR_ADMISSION_REQUIRED');
  assert.equal(f.connects,0);
  assert.equal(f.transactions,0);
  await runtime.stop();
});
test('start opens one dedicated listener and readiness after reconciliation',async()=>{
  const f=fixture(),runtime=createStagedR2DeliveryRuntime(f);
  runtime.start();await wait();
  assert.equal(f.connects,1);
  assert.equal(f.clients.length,1);
  assert.equal(runtime.ready,true);
  assert.equal(f.transactions,0); // no active streams: no SQL from reconcile
  await runtime.stop();
  assert.equal(runtime.ready,false);
  assert.equal(f.clients[0].closed,true);
});
test('listener error revokes readiness and aborts every active stream',async()=>{
  const f=fixture();
  const timers=[];
  const runtime=createStagedR2DeliveryRuntime({
    ...f,listenerOptions:{
      setTimer:fn=>{const timer={fn};timers.push(timer);return timer;},
      clearTimer:()=>{}
    }
  });
  runtime.start();await wait();
  assert.equal(runtime.ready,true);
  const response=new EventEmitter();
  response.destroyed=false;response.writableEnded=false;
  response.destroy=function(){this.destroyed=true;this.emit('close');};
  const controller=new AbortController();
  runtime.registry.register({
    reference:{senderIdentity:'AAAABBBBCCCCDDDD',
      recipientIdentity:'1111222233334444',messageId:'test'},
    abortController:controller,response
  });
  assert.equal(runtime.registry.activeCount,1);
  f.clients[0].emit('end');await wait();
  assert.equal(runtime.ready,false);
  assert.equal(controller.signal.aborted,true);
  assert.equal(response.destroyed,true);
  assert.equal(runtime.registry.activeCount,0);
  assert.equal(timers.length,1);
  await runtime.stop();
});
test('missing trusted PG dependencies fail before opening connections',()=>{
  assert.throws(()=>createStagedR2DeliveryRuntime({
    connectDedicated:()=>{},withTransaction:null
  }),/Dedicated PG/);
});
