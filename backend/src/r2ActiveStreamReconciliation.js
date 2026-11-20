'use strict';

/**
 * PessoasAqui Stage 26 — reconcile local active R2 downloads with current DB.
 * STAGED ONLY: no server.js, no PostgreSQL connection, no HTTP or R2 I/O.
 *
 * The registry must be private to the backend process. The object descriptor
 * must originate from authenticated R2_READ admission; never Android JSON.
 * Re-checks EACH active session using the Stage 22 exact-object gate.
 * On ANY incomplete reference, ambiguous result, or DB failure, abort ALL
 * active streams (fail closed). Call before the listener becomes ready and
 * on reconnection, but also enforce a no-first-byte registration fence in
 * the eventual gateway. One-time reconciliation does not itself guarantee
 * revocation of an ongoing stream during a future listener outage.
 */
const {revalidateR2ReadAtStreamStart}=require('./r2StreamStartGate');

const HEX=/^[0-9A-F]{16}$/;
const SHA=/^[a-f0-9]{64}$/;
const MAX_BYTES=25*1024*1024;
function trustedDescriptor(s){
  return s && typeof s==='object' &&
    HEX.test(s.senderIdentity||'') && HEX.test(s.recipientIdentity||'') &&
    s.senderIdentity!==s.recipientIdentity &&
    typeof s.messageId==='string' && s.messageId.length>0 &&
    s.messageId.length<=256 && !/[\u0000-\u001f\u007f]/.test(s.messageId) &&
    typeof s.objectKey==='string' && s.objectKey.length>0 &&
    SHA.test(s.expectedSha256||'') &&
    Number.isSafeInteger(s.expectedBytes) && s.expectedBytes>=1 &&
    s.expectedBytes<=MAX_BYTES &&
    typeof s.token==='string' && s.token.length>0;
}

async function reconcileR2ActiveStreams({registry,withTransaction}){
  if(!registry||typeof registry.listActive!=='function'||
      typeof registry.cancelToken!=='function'||
      typeof registry.abortAll!=='function'||
      typeof withTransaction!=='function'){
    throw new TypeError('Trusted R2 registry and transaction required');
  }
  let scanned=0,stopped=0;
  try{
    // Snapshot bounded by registry concurrency ceiling; a new stream must
    // independently pass the listener.ready and pre-stream gateway fencing.
    const snapshot=registry.listActive();
    if(!Array.isArray(snapshot)||snapshot.length>1024)
      throw Error('R2_RECONCILE_INVALID_SNAPSHOT');
    for(const entry of snapshot){
      if(!trustedDescriptor(entry))throw Error('R2_RECONCILE_INCOMPLETE_DESCRIPTOR');
      scanned++;
      const admission={
        authorized:true,admitted:true,value:{
          allowed:true,
          authenticatedIdentity:entry.recipientIdentity,
          peerIdentity:entry.senderIdentity,
          messageId:entry.messageId,objectKey:entry.objectKey,
          expectedSha256:entry.expectedSha256,
          expectedBytes:entry.expectedBytes
        }
      };
      // Constructed only from a private registry snapshot, never request JSON.
      const result=await revalidateR2ReadAtStreamStart({
        withTransaction,admission
      });
      if(!result || result.allowed!==true){
        if(registry.cancelToken(entry.token))stopped++;
      }
    }
    return Object.freeze({scanned,stopped});
  }catch(err){
    // Abort every locally active transfer before returning/rethrowing.
    // If destroy() throws unexpectedly, surface failure as well.
    try{registry.abortAll();}catch(abortError){
      throw new AggregateError([err,abortError],
        'R2_RECONCILIATION_AND_FAIL_CLOSED_FAILED');
    }
    throw err;
  }
}

module.exports={reconcileR2ActiveStreams};
