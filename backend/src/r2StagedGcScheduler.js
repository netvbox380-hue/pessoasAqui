'use strict';

/**
 * PessoasAqui Stage 30J — GC scheduling lifecycle (STAGED / INERT).
 *
 * No imports from server.js. No SDK import, env reads, sockets or timers
 * until start() is called explicitly by trusted backend code.
 *
 * This is NOT a production-ready worker. No multi-node global quota,
 * shutdown cancellation propagated into batch, readiness attestation,
 * deployment wiring or credential installation. start() requires a
 * separately injected explicit readiness/authorization gate.
 */
const MIN_POLL_MS=1000;
const MAX_POLL_MS=300000;
const MAX_FAILURE_DELAY_MS=300000;

function createStagedR2GcScheduler({
  runBatch,canRun,pollIntervalMs=30000,
  maxFailureDelayMs=MAX_FAILURE_DELAY_MS,
  onResult=()=>{},onError=()=>{},
  setTimer=setTimeout,clearTimer=clearTimeout
}={}){
  if(typeof runBatch!=='function'||typeof canRun!=='function'||
     typeof onResult!=='function'||typeof onError!=='function'||
     typeof setTimer!=='function'||typeof clearTimer!=='function'||
     !Number.isSafeInteger(pollIntervalMs)||pollIntervalMs<MIN_POLL_MS||
     pollIntervalMs>MAX_POLL_MS||
     !Number.isSafeInteger(maxFailureDelayMs)||
     maxFailureDelayMs<pollIntervalMs||maxFailureDelayMs>MAX_FAILURE_DELAY_MS)
    throw new TypeError('Trusted staged GC scheduler configuration required');

  let running=false;
  let timer=null;
  let inflight=null;
  let errors=0;
  let epoch=0;

  function cancelTimer(){
    if(timer!==null){clearTimer(timer);timer=null;}
  }
  function schedule(ms,expectedEpoch){
    if(!running||epoch!==expectedEpoch)return;
    cancelTimer();
    timer=setTimer(()=>{
      timer=null;
      // All errors handled inside tick; no unhandled promise rejection.
      void tick(expectedEpoch);
    },ms);
    timer?.unref?.();
  }
  async function tick(expectedEpoch){
    if(!running||epoch!==expectedEpoch||inflight)return;
    const work=(async()=>{
      try{
        // canRun must assert DB readiness, rollout authorization and kill switch.
        // A false result must not open a DB transaction or invoke R2.
        const allowed=await canRun();
        if(!running||epoch!==expectedEpoch)return;
        if(allowed!==true){
          errors=0;
          return;
        }
        const result=await runBatch();
        if(!running||epoch!==expectedEpoch)return;
        errors=0;
        try{onResult(result);}catch(_){}
      }catch(error){
        if(!running||epoch!==expectedEpoch)return;
        errors=Math.min(errors+1,12);
        // Never log a raw SDK/DB error or secrets from here.
        try{onError('R2_GC_BATCH_FAILED');}catch(_){}
      }
    })();
    inflight=work;
    try{await work;}finally{
      if(inflight===work)inflight=null;
      if(running&&epoch===expectedEpoch){
        const delay=errors?
          Math.min(maxFailureDelayMs,pollIntervalMs*2**Math.min(errors,12)):
          pollIntervalMs;
        schedule(delay,expectedEpoch);
      }
    }
  }
  function start(){
    if(running)return false;
    if(inflight)throw Error('R2_GC_PREVIOUS_BATCH_DRAIN_REQUIRED');
    running=true;
    errors=0;
    epoch++;
    // No immediate I/O. First tick respects a bounded delay.
    schedule(pollIntervalMs,epoch);
    return true;
  }
  async function stop(){
    const active=running||timer!==null||inflight!==null;
    running=false;
    epoch++;
    cancelTimer();
    // Drain waits until the active batch returns. Batch is responsible for
    // its own network timeout. No new batches are scheduled while stopping.
    const job=inflight;
    if(job)await job;
    return Boolean(active);
  }
  return Object.freeze({
    start,stop,
    get running(){return running;},
    get busy(){return inflight!==null;}
  });
}

module.exports={
  createStagedR2GcScheduler,MIN_POLL_MS,MAX_POLL_MS
};
