'use strict';

/**
 * PessoasAqui / Stage 30B — durable lease and fencing for future R2 GC.
 * STAGED ONLY. Nothing invokes DeleteObject; server.js imports nothing.
 *
 * The caller supplies a REAL PostgreSQL BEGIN/COMMIT/ROLLBACK transaction.
 * A claim only authorizes a future trusted worker to consider a DELETE.
 * It is NOT proof the R2 object was deleted, nor an authorization to call
 * S3 without additional production integration/review.
 *
 * Lock order: media -> outbox -> receipt read -> PUT ledger -> GC claim.
 * Do not hold these locks during network I/O. The claim token is never
 * exposed to Android. Finalization requires the exact token + generation.
 */
const crypto=require('node:crypto');
const {validateOpaqueObjectKey}=require('./r2CiphertextAdapter');
const {authorizePhysicalR2Delete}=require('./r2PutRetirementFence');
const MAX_ATTEMPTS=20;
const LEASE_SECONDS=90;

function validMessageId(v){
  return typeof v==='string'&&v.length>0&&v.length<=256&&
    !/[\u0000-\u001f\u007f]/.test(v);
}
function validUuid(v){return typeof v==='string'&&
  /^[a-f0-9]{8}-[a-f0-9]{4}-[1-8][a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/i.test(v);}
function denied(code){return Object.freeze({claimed:false,code});}

async function claimQueuedR2Delete({
  withTransaction,objectKey,messageId,claimToken=crypto.randomUUID()
}){
  if(typeof withTransaction!=='function')throw new TypeError('Transaction required');
  validateOpaqueObjectKey(objectKey);
  if(!validMessageId(messageId)||!validUuid(claimToken))
    throw new TypeError('Invalid trusted GC claim');
  return withTransaction(async tx=>{
    const media=await tx(
      `SELECT object_key,message_id,state FROM public.message_r2_objects
        WHERE object_key=$1 AND message_id=$2 FOR UPDATE`,
      [objectKey,messageId]);
    if(media.rowCount!==1||media.rows[0].state!=='DELETE_QUEUED')
      return denied('R2_GC_MEDIA_NOT_TERMINAL');
    const outbox=await tx(
      `SELECT object_key,message_id FROM public.message_r2_delete_outbox
        WHERE object_key=$1 AND message_id=$2 FOR UPDATE`,
      [objectKey,messageId]);
    if(outbox.rowCount!==1)return denied('R2_GC_OUTBOX_MISSING');
    const receipt=await tx(
      `SELECT state FROM public.message_delivery_receipts
        WHERE message_id=$1`,[messageId]);
    if(receipt.rowCount!==1||
      !['DELIVERED','EXPIRED'].includes(receipt.rows[0].state))
      return denied('R2_GC_RECEIPT_INVALID');
    const put=await authorizePhysicalR2Delete({tx,objectKey});
    if(!put.allowed)return denied(put.code);

    const claimed=await tx(
      `INSERT INTO public.r2_delete_claims
          (object_key,message_id,claim_token,generation,lease_until,attempts)
        VALUES ($1,$2,$3::uuid,1,
                clock_timestamp()+($4::integer*interval '1 second'),1)
        ON CONFLICT (object_key) DO UPDATE SET
          claim_token=EXCLUDED.claim_token,
          generation=public.r2_delete_claims.generation+1,
          attempts=public.r2_delete_claims.attempts+1,
          lease_until=clock_timestamp()+($4::integer*interval '1 second'),
          changed_at=clock_timestamp()
        WHERE public.r2_delete_claims.completed_at IS NULL
          AND public.r2_delete_claims.message_id=EXCLUDED.message_id
          AND public.r2_delete_claims.lease_until < clock_timestamp()
          AND public.r2_delete_claims.attempts < $5::integer
        RETURNING generation,lease_until,attempts`,
      [objectKey,messageId,claimToken,LEASE_SECONDS,MAX_ATTEMPTS]);
    if(claimed.rowCount!==1)return denied('R2_GC_ALREADY_CLAIMED_OR_RETRY_EXHAUSTED');
    return Object.freeze({
      claimed:true,objectKey,messageId,claimToken,
      generation:Number(claimed.rows[0].generation),
      leaseUntil:claimed.rows[0].lease_until,
      attempts:Number(claimed.rows[0].attempts)
    });
  });
}

/**
 * Record trusted worker confirmation in the claims ledger. This alone does
 * NOT mark media DELETED or outbox complete. Those terminal transitions need
 * a separately audited, atomic protocol in a subsequent integration pass.
 *
 * Caller must present evidence of a successful / idempotent S3 deletion from
 * trusted backend code. This boolean is an API contract, NOT cryptographic
 * proof; NEVER map it from user-controlled JSON.
 */
async function recordDeleteClaimCompleted({
  withTransaction,objectKey,messageId,claimToken,generation,
  trustedStorageDeleteCompleted
}){
  if(typeof withTransaction!=='function')throw new TypeError('Transaction required');
  validateOpaqueObjectKey(objectKey);
  if(!validMessageId(messageId)||!validUuid(claimToken)||
     !Number.isSafeInteger(generation)||generation<1||
     trustedStorageDeleteCompleted!==true)
    throw new TypeError('Trusted successful delete result required');
  return withTransaction(async tx=>{
    const updated=await tx(
      `UPDATE public.r2_delete_claims
          SET completed_at=clock_timestamp(),changed_at=clock_timestamp()
        WHERE object_key=$1 AND message_id=$2
          AND claim_token=$3::uuid AND generation=$4::bigint
          AND completed_at IS NULL
          AND lease_until >= clock_timestamp()
        RETURNING object_key`,
      [objectKey,messageId,claimToken,generation]);
    return Object.freeze({recorded:updated.rowCount===1});
  });
}

module.exports={
  LEASE_SECONDS,MAX_ATTEMPTS,
  claimQueuedR2Delete,recordDeleteClaimCompleted
};
