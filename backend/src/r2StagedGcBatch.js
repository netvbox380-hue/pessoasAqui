'use strict';

/**
 * PessoasAqui / R2 Stage 30G: explicitly invoked GC batch orchestration.
 * PREPARATORY ONLY — NOT imported by server.js; NO scheduler/timer/credentials.
 *
 * This function can invoke an INJECTED storage deletion callback. A production
 * integration must bind it exclusively to a trusted, timeout-bounded S3/R2
 * client after an explicit rollout authorization and another audit. It must
 * never be exposed as an HTTP endpoint or passed Android-supplied callbacks.
 *
 * PostgreSQL locks are held ONLY inside the transactional discovery, claim,
 * finalize and retry callbacks, never across storage I/O. Lease is 90 seconds;
 * the injected delete must have a shorter enforced network deadline.
 */
const crypto=require('node:crypto');
const {discoverQueuedR2DeleteCandidates}=require('./r2DeleteCandidateDiscovery');
const {claimQueuedR2Delete}=require('./r2DeleteClaimLedger');
const {finalizeTrustedR2Delete,scheduleFailedR2Delete}=
  require('./r2DeleteOutcomeCoordinator');

const MAX_DELETE_PER_BATCH=8;
const MAX_DELETE_TIMEOUT_MS=30000;
function strictResult(result){
  return result && result.deleted===true &&
    typeof result.objectKey==='string';
}

/**
 * deleteObject is a server-only callback:
 *   ({objectKey,signal}) => Promise<{deleted:true,objectKey}>
 * Its implementation MUST respect AbortSignal, impose socket/request
 * timeouts, and only resolve after an authenticated SDK DeleteObject success.
 */
async function processStagedR2GcBatch({
  withTransaction,deleteObject,limit=4,deleteTimeoutMs=15000,
  makeClaimToken=()=>crypto.randomUUID(),
  makeJitterSeconds=()=>crypto.randomInt(0,31)
}={}){
  if(typeof withTransaction!=='function'||typeof deleteObject!=='function'||
    !Number.isSafeInteger(limit)||limit<1||limit>MAX_DELETE_PER_BATCH||
    !Number.isSafeInteger(deleteTimeoutMs)||deleteTimeoutMs<100||
    deleteTimeoutMs>MAX_DELETE_TIMEOUT_MS||
    typeof makeClaimToken!=='function'||typeof makeJitterSeconds!=='function')
    throw new TypeError('Trusted and bounded GC batch dependencies required');
  const discovered=await discoverQueuedR2DeleteCandidates({withTransaction,limit});
  const outcomes=[];
  for(const candidate of discovered){
    const claim=await claimQueuedR2Delete({
      withTransaction,...candidate,claimToken:makeClaimToken()
    });
    if(!claim.claimed){
      outcomes.push(Object.freeze({objectKey:candidate.objectKey,
        state:'NOT_CLAIMED',code:claim.code}));
      continue;
    }

    const controller=new AbortController();
    let timer;
    let timedOut=false;
    let storageSucceeded=false;
    let outcome;
    try{
      // This timer is a per-operation timeout, NOT a polling worker/scheduler.
      const deadline=new Promise((_,reject)=>{
        timer=setTimeout(()=>{
          timedOut=true;
          controller.abort();
          reject(Object.assign(new Error('R2_DELETE_TIMEOUT'),{
            code:'R2_DELETE_TIMEOUT'
          }));
        },deleteTimeoutMs);
      });
      const result=await Promise.race([
        Promise.resolve().then(()=>deleteObject({
          objectKey:claim.objectKey,signal:controller.signal
        })),deadline
      ]);
      if(timedOut||!strictResult(result)||result.objectKey!==claim.objectKey)
        throw Error('R2_DELETE_UNVERIFIED_RESULT');
      storageSucceeded=true;
      const finalized=await finalizeTrustedR2Delete({
        withTransaction,
        objectKey:claim.objectKey,messageId:claim.messageId,
        claimToken:claim.claimToken,generation:claim.generation,
        trustedStorageDeleteCompleted:true
      });
      outcome=finalized.committed?
        {objectKey:claim.objectKey,state:'FINALIZED'}:
        {objectKey:claim.objectKey,state:'FINALIZE_DENIED',code:finalized.code};
    }catch(error){
      // Once storage confirmed success, a DB failure must NOT be treated as
      // a storage failure. Instead allow the claim to expire and retry the
      // immutable key idempotently on a later explicitly invoked batch.
      if(storageSucceeded)throw error;
      const safeCode=timedOut?'R2_DELETE_TIMEOUT':'R2_DELETE_FAILED';
      const jitter=makeJitterSeconds();
      if(!Number.isSafeInteger(jitter)||jitter<0||jitter>30)
        throw new TypeError('Invalid trusted GC jitter');
      const retry=await scheduleFailedR2Delete({
        withTransaction,objectKey:claim.objectKey,messageId:claim.messageId,
        claimToken:claim.claimToken,generation:claim.generation,
        errorCode:safeCode,jitterSeconds:jitter
      });
      outcome={objectKey:claim.objectKey,
        state:retry.committed?'RETRY_SCHEDULED':'RETRY_DENIED',
        code:retry.code||safeCode};
    }finally{
      if(timer)clearTimeout(timer);
      controller.abort();
    }
    outcomes.push(Object.freeze(outcome));
  }
  return Object.freeze(outcomes);
}

module.exports={processStagedR2GcBatch,MAX_DELETE_PER_BATCH,MAX_DELETE_TIMEOUT_MS};
