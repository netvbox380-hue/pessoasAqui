'use strict';

/**
 * PessoasAqui / Etapa 30X — allocation of fresh immutable physical upload
 * attempt keys. STAGED ONLY: no server.js import, no SDK, no network, no
 * migration execution or R2 I/O on import.
 *
 * A physical attempt is NOT an upload, PUT capability or verified media.
 * Do NOT use this result to mark UPLOADED/ATTACHED or to issue a URL yet.
 * Integration with PUT/CONFIRM/GC requires a separate protocol review.
 */
const {makeOpaqueObjectKey}=require('./r2CiphertextAdapter');
const ID=/^[A-F0-9]{16}$/;
const MAX_ATTEMPTS=5;
function deny(code,statusCode=409){
  return Object.freeze({allocated:false,code,statusCode});
}
async function allocateImmutableR2Attempt({
  withTransaction,messageId,senderIdentity,recipientIdentity,
  randomUUID
}={}){
  if(typeof withTransaction!=='function')
    throw new TypeError('Trusted PostgreSQL transaction required');
  if(typeof messageId!=='string'||!messageId||messageId.length>256||
    /[\u0000-\u001f\u007f]/.test(messageId)||
    !ID.test(senderIdentity||'')||!ID.test(recipientIdentity||'')||
    senderIdentity===recipientIdentity)
    return deny('R2_INVALID_ATTEMPT_REQUEST',400);

  // UUID generation is exclusively backend-controlled. The injection point
  // exists for unit tests; never pass client JSON or untrusted callbacks.
  const freshKey=makeOpaqueObjectKey(randomUUID);
  return withTransaction(async tx=>{
    // Same logical-row lock serializes all instances allocating generations.
    const parent=await tx(
      `SELECT object_key,message_id,sender_identity,recipient_identity,
              state,upload_generation,
              (upload_lease_expires_at>clock_timestamp()) AS lease_active
         FROM public.message_r2_objects WHERE message_id=$1 FOR UPDATE`,
      [messageId]
    );
    if(parent?.rowCount!==1)
      return deny('R2_LOGICAL_MESSAGE_NOT_RESERVED',404);
    const m=parent.rows[0];
    if(m.sender_identity?.toUpperCase()!==senderIdentity||
       m.recipient_identity?.toUpperCase()!==recipientIdentity)
      return deny('R2_ATTEMPT_NOT_OWNER',403);
    if(m.state!=='RESERVED'||m.lease_active!==true||
       m.upload_generation!==1)
      return deny('R2_LOGICAL_RESERVATION_INACTIVE');
    if(freshKey===m.object_key)
      return deny('R2_ATTEMPT_KEY_REUSE');

    const n=await tx(
      `SELECT COALESCE(MAX(generation),0)+1 AS next_generation
         FROM public.message_r2_upload_attempts
        WHERE message_id=$1`,[messageId]
    );
    if(n?.rowCount!==1)
      throw Error('R2_ATTEMPT_GENERATION_UNAVAILABLE');
    const generation=Number(n.rows[0].next_generation);
    if(!Number.isSafeInteger(generation)||generation<1||
       generation>MAX_ATTEMPTS)
      return deny('R2_ATTEMPT_LIMIT_REACHED',429);
    const inserted=await tx(
      `INSERT INTO public.message_r2_upload_attempts
         (object_key,message_id,generation,sender_identity,recipient_identity)
       VALUES($1,$2,$3,$4,$5)
       RETURNING object_key,message_id,generation,created_at`,
      [freshKey,messageId,generation,senderIdentity,recipientIdentity]
    );
    if(inserted?.rowCount!==1)
      throw Error('R2_ATTEMPT_ALLOCATION_FAILED');
    const row=inserted.rows[0];
    if(row.object_key!==freshKey||row.message_id!==messageId||
       Number(row.generation)!==generation)
      throw Error('R2_ATTEMPT_ALLOCATION_MISMATCH');
    return Object.freeze({
      allocated:true,objectKey:freshKey,messageId,generation,
      createdAt:row.created_at
    });
  });
}
module.exports={allocateImmutableR2Attempt,MAX_ATTEMPTS};
