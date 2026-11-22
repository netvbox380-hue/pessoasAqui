'use strict';

/**
 * PessoasAqui / Stage 30C: bounded GC discovery (STAGED ONLY).
 *
 * Scan candidate DELETE_QUEUED media without doing R2 I/O. Uses a short
 * PostgreSQL transaction with FOR UPDATE OF media SKIP LOCKED so two
 * batch scanners do not block each other while discovering candidates.
 * Stage 30D filters terminal receipts, retired PUT windows, and active or
 * completed claims to avoid repeatedly selecting ineligible rows.
 * Stage 30E uses the outbox retry deadline already present since migration 004,
 * preventing repeat discovery of jobs in scheduled cooldown.
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
               AND q.state IN ('PENDING','RETRY')
               AND q.next_attempt_at <= clock_timestamp()
          )
          -- Filter terminality before locking candidates. Claim will
          -- independently recheck this under the authoritative locks.
          AND EXISTS (
            SELECT 1 FROM public.message_delivery_receipts r
             WHERE r.message_id=o.message_id
               AND r.state IN ('DELIVERED','EXPIRED')
          )
          -- Legacy/unsealed/live PUT capabilities are quarantined.
          AND EXISTS (
            SELECT 1 FROM public.r2_put_capability_windows p
             WHERE p.object_key=o.object_key
               AND p.sealed_at IS NOT NULL
               AND clock_timestamp() >=
                 p.expires_at + (120 * interval '1 second')
          )
          -- Completed claims, active leases, and retry-exhausted
          -- claims cannot be rediscovered in a tight polling loop.
          AND NOT EXISTS (
            SELECT 1 FROM public.r2_delete_claims c
             WHERE c.object_key=o.object_key
               AND (c.completed_at IS NOT NULL
                 OR c.lease_until >= clock_timestamp()
                 OR c.attempts >= 20)
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
