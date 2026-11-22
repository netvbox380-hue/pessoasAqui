'use strict';

/**
 * PessoasAqui R2 Stage 30F: post-I/O DB outcome transitions (STAGED ONLY).
 *
 * No R2 SDK calls, no scheduler, no HTTP route, no server.js import.
 * Callers MUST pass evidence from a trusted server-side DeleteObject adapter.
 * A boolean supplied by an HTTP client is NOT such evidence.
 *
 * IMPORTANT: PG and R2 do not share a transaction. DeleteObject success may
 * commit at R2 before this DB transaction starts. A failed DB transaction
 * must be retried via idempotent DeleteObject for the same immutable key.
 * The lease token/generation fence rejects stale worker finalizations.
 *
 * Lock order: media -> delete outbox -> read receipt -> PUT ledger ->
 * claim. During successful finalization, outbox DONE MUST precede media
 * DELETED, because migration 007 media transition trigger requires it.
 */
const crypto=require('node:crypto');
const {validateOpaqueObjectKey}=require('./r2CiphertextAdapter');
const {authorizePhysicalR2Delete}=require('./r2PutRetirementFence');

const MAX_DELAY_SECONDS=3600;
const MAX_ATTEMPTS=20;
function requireValid({withTransaction,objectKey,messageId,claimToken,generation}){
  if(typeof withTransaction!=='function')throw new TypeError('PostgreSQL transaction required');
  validateOpaqueObjectKey(objectKey);
  if(typeof messageId!=='string'||messageId.length===0||messageId.length>256||
    /[\u0000-\u001f\u007f]/.test(messageId)||
    typeof claimToken!=='string'||
    !/^[a-f0-9]{8}-[a-f0-9]{4}-[1-8][a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/i.test(claimToken)||
    !Number.isSafeInteger(generation)||generation<1)
    throw new TypeError('Trusted exact R2 deletion claim required');
}
function deny(code){return Object.freeze({committed:false,code});}
async function withActiveClaim(tx,{objectKey,messageId,claimToken,generation}){
  const media=await tx(
    `SELECT state FROM public.message_r2_objects
      WHERE object_key=$1 AND message_id=$2 FOR UPDATE`,[objectKey,messageId]);
  if(media.rowCount!==1||media.rows[0].state!=='DELETE_QUEUED')
    return {error:'R2_GC_MEDIA_NOT_QUEUED'};
  const job=await tx(
    `SELECT state FROM public.message_r2_delete_outbox
      WHERE object_key=$1 AND message_id=$2 FOR UPDATE`,[objectKey,messageId]);
  if(job.rowCount!==1||!['PENDING','RETRY'].includes(job.rows[0].state))
    return {error:'R2_GC_JOB_NOT_PENDING'};
  const receipt=await tx(
    `SELECT state FROM public.message_delivery_receipts
      WHERE message_id=$1`,[messageId]);
  if(receipt.rowCount!==1||
     !['DELIVERED','EXPIRED'].includes(receipt.rows[0].state))
    return {error:'R2_GC_TERMINAL_RECEIPT_REQUIRED'};
  const put=await authorizePhysicalR2Delete({tx,objectKey});
  if(!put.allowed)return {error:put.code};
  const claim=await tx(
    `SELECT claim_token,generation,attempts,lease_until,completed_at
       FROM public.r2_delete_claims
      WHERE object_key=$1 AND message_id=$2 FOR UPDATE`,
    [objectKey,messageId]);
  const row=claim.rows[0];
  if(claim.rowCount!==1||row.completed_at!==null||
     String(row.claim_token).toLowerCase()!==claimToken.toLowerCase()||
     Number(row.generation)!==generation)
    return {error:'R2_GC_STALE_CLAIM'};
  const time=await tx('SELECT clock_timestamp() AS now',[]);
  if(new Date(row.lease_until).getTime()<new Date(time.rows[0].now).getTime())
    return {error:'R2_GC_LEASE_EXPIRED'};
  return {attempts:Number(row.attempts)};
}

async function finalizeTrustedR2Delete({
  withTransaction,objectKey,messageId,claimToken,generation,
  trustedStorageDeleteCompleted
}){
  requireValid({withTransaction,objectKey,messageId,claimToken,generation});
  if(trustedStorageDeleteCompleted!==true)
    throw new TypeError('Trusted storage deletion confirmation required');
  return withTransaction(async tx=>{
    const claim=await withActiveClaim(tx,{objectKey,messageId,claimToken,generation});
    if(claim.error)return deny(claim.error);
    const job=await tx(
      `UPDATE public.message_r2_delete_outbox
          SET state='DONE',completed_at=clock_timestamp(),last_error_code=NULL
        WHERE object_key=$1 AND message_id=$2 AND state IN ('PENDING','RETRY')
        RETURNING object_key`,[objectKey,messageId]);
    if(job.rowCount!==1)throw Error('R2_GC_OUTBOX_FINALIZE_CONFLICT');
    // Existing migration 007 trigger verifies outbox DONE before DELETED.
    const media=await tx(
      `UPDATE public.message_r2_objects
          SET state='DELETED',last_changed_at=clock_timestamp()
        WHERE object_key=$1 AND message_id=$2 AND state='DELETE_QUEUED'
        RETURNING object_key`,[objectKey,messageId]);
    if(media.rowCount!==1)throw Error('R2_GC_MEDIA_FINALIZE_CONFLICT');
    const completed=await tx(
      `UPDATE public.r2_delete_claims
          SET completed_at=clock_timestamp(),changed_at=clock_timestamp()
        WHERE object_key=$1 AND message_id=$2
          AND claim_token=$3::uuid AND generation=$4::bigint
          AND completed_at IS NULL AND lease_until >= clock_timestamp()
        RETURNING object_key`,[objectKey,messageId,claimToken,generation]);
    if(completed.rowCount!==1)throw Error('R2_GC_CLAIM_FINALIZE_CONFLICT');
    return Object.freeze({committed:true,objectKey,messageId,state:'DELETED'});
  });
}

/**
 * Schedule retry after a trusted backend storage failure. No secrets or full
 * error strings go into the DB. Retry is delayed beyond the CURRENT lease
 * to prevent a fast loop after a crash. No physical DeleteObject is called.
 */
async function scheduleFailedR2Delete({
  withTransaction,objectKey,messageId,claimToken,generation,
  errorCode='R2_DELETE_FAILED',jitterSeconds=0
}){
  requireValid({withTransaction,objectKey,messageId,claimToken,generation});
  if(typeof errorCode!=='string'||!/^R2_[A-Z0-9_]{1,80}$/.test(errorCode)||
     !Number.isSafeInteger(jitterSeconds)||jitterSeconds<0||jitterSeconds>30)
    throw new TypeError('Invalid trusted GC failure metadata');
  return withTransaction(async tx=>{
    const claim=await withActiveClaim(tx,{objectKey,messageId,claimToken,generation});
    if(claim.error)return deny(claim.error);
    if(!Number.isSafeInteger(claim.attempts)||claim.attempts<1||
       claim.attempts>MAX_ATTEMPTS)throw Error('R2_GC_INVALID_RETRY_COUNT');
    const delay=Math.min(MAX_DELAY_SECONDS,30*2**Math.min(7,claim.attempts-1))+
      jitterSeconds;
    const scheduled=await tx(
      `UPDATE public.message_r2_delete_outbox
          SET state='RETRY',attempts=attempts+1,
              next_attempt_at=GREATEST(
                clock_timestamp(),(
                  SELECT lease_until FROM public.r2_delete_claims
                   WHERE object_key=$1 AND message_id=$2
              ))+($3::integer*interval '1 second'),
              last_error_code=$4
        WHERE object_key=$1 AND message_id=$2
          AND state IN ('PENDING','RETRY')
        RETURNING next_attempt_at`,
      [objectKey,messageId,delay,errorCode]);
    if(scheduled.rowCount!==1)throw Error('R2_GC_RETRY_SCHEDULE_CONFLICT');
    return Object.freeze({committed:true,retry:true,delaySeconds:delay,
      nextAttemptAt:scheduled.rows[0].next_attempt_at});
  });
}

module.exports={finalizeTrustedR2Delete,scheduleFailedR2Delete,MAX_DELAY_SECONDS};
