'use strict';

/**
 * PessoasAqui R2 Stage 29 — DB-only deletion admission; STAGED, NOT ACTIVE.
 *
 * This only checks whether a previously queued DeleteObject operation would
 * be eligible. No S3 client, DeleteObject, server route, timer or scheduler.
 * This is NOT a worker and not a durable execution lease: DO NOT execute
 * physical DELETE from its return value in production. The final worker
 * needs a durable claim, fencing, retry/backoff and post-I/O finalization.
 *
 * Required order in a short PostgreSQL transaction:
 *   1. lock media row
 *   2. lock queued outbox row
 *   3. check terminal receipt
 *   4. check sealed ledger and expired presigned PUT capability.
 *
 * Android ACK must be authenticated and follow VERIFIED LOCAL PERSISTENCE.
 */
const {validateOpaqueObjectKey}=require('./r2CiphertextAdapter');
const {authorizePhysicalR2Delete}=require('./r2PutRetirementFence');

function validMessageId(id){
  return typeof id==='string'&&id.length>0&&id.length<=256&&
    !/[\u0000-\u001f\u007f]/.test(id);
}
function reject(code){return Object.freeze({allowed:false,code});}

async function admitQueuedR2Deletion({withTransaction,objectKey,messageId}){
  if(typeof withTransaction!=='function')
    throw new TypeError('PostgreSQL transaction required');
  validateOpaqueObjectKey(objectKey);
  if(!validMessageId(messageId))throw new TypeError('Valid R2 message required');
  return withTransaction(async tx=>{
    const objects=await tx(
      `SELECT object_key,message_id,state
         FROM public.message_r2_objects
        WHERE object_key=$1 AND message_id=$2
        FOR UPDATE`,[objectKey,messageId]
    );
    if(objects.rowCount!==1||objects.rows[0].state!=='DELETE_QUEUED')
      return reject('R2_MEDIA_NOT_DELETE_QUEUED');

    const jobs=await tx(
      `SELECT object_key,message_id
         FROM public.message_r2_delete_outbox
        WHERE object_key=$1 AND message_id=$2
        FOR UPDATE`,[objectKey,messageId]
    );
    if(jobs.rowCount!==1)return reject('R2_DELETE_OUTBOX_MISSING');

    const receipts=await tx(
      `SELECT state
         FROM public.message_delivery_receipts
        WHERE message_id=$1`,[messageId]
    );
    if(receipts.rowCount!==1||
      !['DELIVERED','EXPIRED'].includes(receipts.rows[0].state))
      return reject('R2_TERMINAL_RECEIPT_MISSING');

    const put=await authorizePhysicalR2Delete({tx,objectKey});
    if(!put.allowed)return reject(put.code);
    return Object.freeze({
      allowed:true,code:'R2_QUEUED_DELETE_ELIGIBLE',objectKey,messageId
    });
  });
}

module.exports={admitQueuedR2Deletion};
