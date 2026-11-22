'use strict';
const test=require('node:test');
const assert=require('node:assert/strict');
const {createStagedR2GcScheduler}=
  require('../src/r2StagedGcScheduler');

function harness({runBatch=async()=>[],canRun=async()=>true,
  pollIntervalMs=1000,onError=()=>{},onResult=()=>{}}={}){
  const jobs=new Map();let serial=0;
  const scheduler=createStagedR2GcScheduler({
    runBatch,canRun,pollIntervalMs,onError,onResult,
    setTimer:(cb,ms)=>{const id=++serial;jobs.set(id,{cb,ms});return id;},
    clearTimer:id=>jobs.delete(id)
  });
  return {
    scheduler,jobs,
    async fire(){
      const entry=jobs.entries().next().value;
      assert.ok(entry,'Expected scheduled poll');
      const [id,{cb}]=entry;
      jobs.delete(id);
      cb();
      // Drain microtasks until the internal promises settle.
      for(let i=0;i<30;i++)await Promise.resolve();
    }
  };
}
test('new scheduler is inert; start schedules delayed first poll only',async()=>{
  let calls=0;
  const h=harness({runBatch:async()=>{calls++;return [];}});
  assert.equal(h.scheduler.running,false);
  assert.equal(h.jobs.size,0);
  assert.equal(calls,0);
  assert.equal(h.scheduler.start(),true);
  assert.equal(calls,0);
  assert.equal(h.jobs.size,1);
  assert.equal([...h.jobs.values()][0].ms,1000);
  await h.fire();
  assert.equal(calls,1);
  assert.equal(h.jobs.size,1);
  await h.scheduler.stop();
  assert.equal(h.jobs.size,0);
});
test('readiness false is fail-closed and does not invoke a batch',async()=>{
  let called=0;
  const h=harness({canRun:async()=>false,runBatch:async()=>{called++;}});
  h.scheduler.start();await h.fire();
  assert.equal(called,0);
  assert.equal([...h.jobs.values()][0].ms,1000);
  await h.scheduler.stop();
});
test('single flight: no concurrent polling or duplicate start',async()=>{
  let unlock,called=0;
  const h=harness({runBatch:()=>{called++;return new Promise(resolve=>{unlock=resolve;});}});
  h.scheduler.start();
  assert.equal(h.scheduler.start(),false);
  await h.fire();
  assert.equal(h.scheduler.busy,true);
  assert.equal(h.jobs.size,0);
  assert.equal(called,1);
  unlock([]);
  for(let i=0;i<30;i++)await Promise.resolve();
  assert.equal(h.scheduler.busy,false);
  assert.equal(h.jobs.size,1);
  await h.scheduler.stop();
});
test('stop drains inflight work and prevents further scheduling',async()=>{
  let unlock;
  const h=harness({runBatch:()=>new Promise(resolve=>{unlock=resolve;})});
  h.scheduler.start();
  await h.fire();
  let drained=false;
  const stop=h.scheduler.stop().then(()=>{drained=true;});
  await Promise.resolve();
  assert.equal(drained,false);
  unlock([]);
  await stop;
  assert.equal(drained,true);
  assert.equal(h.scheduler.running,false);
  assert.equal(h.jobs.size,0);
});
test('exception produces sanitized error and bounded exponential polling backoff',async()=>{
  let observed;
  const h=harness({
    runBatch:async()=>{throw Error('do not log R2 credentials');},
    onError:code=>{observed=code;}
  });
  h.scheduler.start();
  await h.fire();
  assert.equal(observed,'R2_GC_BATCH_FAILED');
  assert.equal([...h.jobs.values()][0].ms,2000);
  await h.fire();
  assert.equal([...h.jobs.values()][0].ms,4000);
  await h.scheduler.stop();
});
test('readiness failures back off; never permit runBatch while readiness is unknown',async()=>{
  let batchCalls=0;
  const h=harness({
    canRun:async()=>{throw Error('PG_DOWN');},
    runBatch:async()=>{batchCalls++;}
  });
  h.scheduler.start();await h.fire();
  assert.equal(batchCalls,0);
  assert.equal([...h.jobs.values()][0].ms,2000);
  await h.scheduler.stop();
});
test('stop cancels scheduled work and start after clean stop is possible',async()=>{
  let called=0;
  const h=harness({runBatch:async()=>{called++;}});
  h.scheduler.start();
  assert.equal(await h.scheduler.stop(),true);
  assert.equal(await h.scheduler.stop(),false);
  assert.equal(h.jobs.size,0);
  h.scheduler.start();await h.fire();
  assert.equal(called,1);
  await h.scheduler.stop();
});
test('configuration rejects unbounded poll intervals or unknown dependencies',()=>{
  for(const pollIntervalMs of [0,999,300001,1.5]){
    assert.throws(()=>createStagedR2GcScheduler({
      runBatch:async()=>[],canRun:async()=>true,pollIntervalMs
    }),/Trusted staged GC/);
  }
  assert.throws(()=>createStagedR2GcScheduler({
    runBatch:null,canRun:async()=>true
  }),/Trusted staged GC/);
});
