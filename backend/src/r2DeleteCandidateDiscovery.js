'use strict';

/**
 * PessoasAqui / Stage 30C: bounded GC discovery (STAGED ONLY).
 *
 * Scan candidate DELETE_QUEUED media without doing R2 I/O. Uses a short
 * PostgreSQL transaction with FOR UPDATE OF media SKIP LOCKED so two
 * batch scanners do not block each other while discovering candidates.
 * Row locks are released at COMMIT; this does NOT grant an execution lease.
 *
 * Each returned candidate MUST separately pass claimQueuedR2Delete() (30B)
 * in its own authoritative transaction before any future storage operation.
 * The final physical worker, post-I/O completion, retry scheduling, and
 * storage controls are NOT implemented or authorized here.
 */
const MAX_BATCH=32;
const {validateOpaqueObjectKey}=require('./r2CiphertextAdapter');

function validMessageId(v){
  return typeof v==='string' && v.length>0 && v.length<=256 &&
    !/[\u0000-\u001f\u007f]/.test(v);
}

async function discoverQueuedR2DeleteCandidates({withTransaction,limit=16}){
  if(typeof withTransaction!=='function' ||
     !Number.isSafeInteger(limit)||limit<1||limit>MAX_BATCH)
    throw new TypeError('Trusted DB transaction and bounded batch size required');
  return withTransaction(async tx=>{
    const found=await tx(
      `SELECT o.object_key,o.message_id
         FROM public.message_r2_objects AS o
        WHERE o.state='DELETE_QUEUED'
          AND EXISTS (
            SELECT 1 FROM public.message_r2_delete_outbox q
             WHERE q.object_key=o.object_key
               AND q.message_id=o.message_id
          )
        ORDER BY o.last_changed_at ASC,o.object_key ASC
        LIMIT $1::integer
        FOR UPDATE OF o SKIP LOCKED`,
      [limit]);
    if(!found||!Array.isArray(found.rows)||
      found.rowCount!==found.rows.length||found.rows.length>limit)
      throw Error('R2_GC_INVALID_CANDIDATE_RESULT');
    const seen=new Set();
    const out=found.rows.map(row=>{
      validateOpaqueObjectKey(row.object_key);
      if(!validMessageId(row.message_id)||seen.has(row.object_key))
        throw Error('R2_GC_INVALID_CANDIDATE');
      seen.add(row.object_key);
      return Object.freeze({
        objectKey:row.object_key,messageId:row.message_id
      });
    });
    return Object.freeze(out);
  });
}

module.exports={discoverQueuedR2DeleteCandidates,MAX_BATCH};
