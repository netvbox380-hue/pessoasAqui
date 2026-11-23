'use strict';

/**
 * PessoasAqui Stage 30Y-A — signed PUT capability for an immutable physical
 * attempt (migration 018). PREPARE ONLY; not imported by server.js.
 *
 * Distinct R2_ISSUE_PUT P-256 proof signs messageId + PHYSICAL attempt key.
 * One PostgreSQL transaction checks mutual pair, active SPKI, nonce,
 * logical reservation, immutable attempt owner/state/generation and durable
 * PUT retirement window. URL never escapes until transaction commits.
 *
 * No SDK/client/env/HTTP. The injected signer is TRUSTED SERVER CODE ONLY.
 * This does NOT connect ATTACH/READ/GC and does NOT settle confirmation.
 */
const {executeAuthenticatedR2Action}=require('./r2ActionAuth');
const {issuePutWithDurableRetirementFence}=require('./r2PutRetirementFence');
const {validateOpaqueObjectKey}=require('./r2CiphertextAdapter');
const ID=/^[A-F0-9]{16}$/;
const MESSAGE=/^[^\u0000-\u001f\u007f]{1,256}$/;
const BUCKET=/^[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$/;
const denied=(code,statusCode=409)=>Object.freeze({
  authorized:false,issued:false,code,statusCode
});
async function issueAuthenticatedAttemptPut({
  withTransaction,identity,peerIdentity,messageId,attemptObjectKey,
  proof,bucket,signPutUrl,now=Date.now()
}={}){
  if(typeof withTransaction!=='function'||typeof signPutUrl!=='function'||
    typeof bucket!=='string'||!BUCKET.test(bucket))
    throw new TypeError('Trusted R2 attempt PUT dependencies required');
  if(!ID.test(identity||'')||!ID.test(peerIdentity||'')||
     identity===peerIdentity||!MESSAGE.test(messageId||''))
    return denied('INVALID_R2_ATTEMPT_PUT_REQUEST',400);
  try{validateOpaqueObjectKey(attemptObjectKey);}
  catch(_){return denied('INVALID_R2_ATTEMPT_PUT_REQUEST',400);}

  const result=await executeAuthenticatedR2Action({
    withTransaction,
    operation:'R2_ISSUE_PUT',
    identity,peerIdentity,
    fields:{messageId,objectKey:attemptObjectKey},
    proof,now,
    action:async(tx,ctx)=>{
      // Lock the parent BEFORE the attempt, matching allocation lock order.
      const logical=await tx(
        `SELECT object_key,message_id,sender_identity,recipient_identity,
                state,upload_generation,
                (upload_lease_expires_at>clock_timestamp()) AS lease_active
           FROM public.message_r2_objects
          WHERE message_id=$1 FOR UPDATE`,[messageId]
      );
      if(logical?.rowCount!==1)
        return denied('R2_ATTEMPT_PARENT_MISSING',404);
      const p=logical.rows[0];
      if(p.sender_identity?.toUpperCase()!==ctx.authenticatedIdentity||
         p.recipient_identity?.toUpperCase()!==ctx.peerIdentity)
        return denied('R2_ATTEMPT_PARENT_NOT_OWNER',403);
      if(p.state!=='RESERVED'||p.upload_generation!==1||
         p.lease_active!==true)
        return denied('R2_ATTEMPT_PARENT_INACTIVE');
      if(p.object_key===attemptObjectKey)
        return denied('R2_ATTEMPT_LEGACY_KEY_REUSE');
      const attempt=await tx(
        `SELECT object_key,message_id,generation,
                sender_identity,recipient_identity,state
           FROM public.message_r2_upload_attempts
          WHERE object_key=$1 AND message_id=$2 FOR UPDATE`,
        [attemptObjectKey,messageId]
      );
      if(attempt?.rowCount!==1)
        return denied('R2_ATTEMPT_MISSING',404);
      const a=attempt.rows[0];
      if(a.object_key!==attemptObjectKey||a.message_id!==messageId||
         a.sender_identity?.toUpperCase()!==ctx.authenticatedIdentity||
         a.recipient_identity?.toUpperCase()!==ctx.peerIdentity)
        return denied('R2_ATTEMPT_NOT_OWNER',403);
      if(!Number.isSafeInteger(a.generation)||a.generation<1||
         a.generation>5||!['ALLOCATED','PUT_ISSUED'].includes(a.state))
        return denied('R2_ATTEMPT_NOT_ISSUABLE');
      const capability=await issuePutWithDurableRetirementFence({
        withTransaction:async callback=>callback(tx),
        signPutUrl,bucket,objectKey:attemptObjectKey,
        authorizedReservation:{
          reserved:true,generation:1,objectKey:attemptObjectKey,bucket
        }
      });
      if(a.state==='ALLOCATED'){
        const changed=await tx(
          `UPDATE public.message_r2_upload_attempts
             SET state='PUT_ISSUED'
           WHERE object_key=$1 AND message_id=$2
             AND generation=$3 AND state='ALLOCATED'
           RETURNING object_key`,
          [attemptObjectKey,messageId,a.generation]
        );
        if(changed?.rowCount!==1)
          throw Error('R2_ATTEMPT_ISSUANCE_STATE_CONFLICT');
      }
      return Object.freeze({
        issued:true,attemptGeneration:a.generation,
        attemptObjectKey,capability
      });
    }
  });
  if(result?.authorized!==true)
    return denied(result?.code||'R2_ATTEMPT_PUT_UNAUTHORIZED',
      result?.statusCode||403);
  if(result.value?.issued!==true)return result.value;
  // Real PostgreSQL transaction runner must have successfully COMMITTED.
  return Object.freeze({
    authorized:true,issued:true,messageId,
    attemptObjectKey:result.value.attemptObjectKey,
    attemptGeneration:result.value.attemptGeneration,
    capability:result.value.capability
  });
}
module.exports={issueAuthenticatedAttemptPut};
