'use strict';

/**
 * PessoasAqui Stage 30Y-B — signed CONFIRM admission for a PHYSICAL key.
 *
 * PREPARE ONLY: does not GET R2, confirm bytes, choose a winner, mark
 * UPLOADED, attach a message, or emit HTTP ACK. No production server import.
 * Requires staged migrations 018 and 019, plus verification daily budget 010.
 *
 * The client signs canonical R2_CONFIRM with generation=1 (existing proof
 * protocol generation) while the physical attempt generation 1..5 comes
 * exclusively from the locked database row. Do not mix these domains.
 */
const crypto=require('node:crypto');
const {executeAuthenticatedR2Action}=require('./r2ActionAuth');
const {validateOpaqueObjectKey}=require('./r2CiphertextAdapter');
const {MAX_DAILY_VERIFICATIONS,MAX_DAILY_VERIFIED_BYTES,
  VERIFICATION_CLAIM_SECONDS}=require('./r2VerificationAdmission');

const ID=/^[A-F0-9]{16}$/;
const SHA=/^[a-f0-9]{64}$/;
const MESSAGE=/^[^\u0000-\u001f\u007f]{1,256}$/;
const MAX_BYTES=25*1024*1024;
function denied(code,statusCode=409){
  return Object.freeze({authorized:false,granted:false,code,statusCode});
}
async function admitAuthenticatedAttemptConfirm({
  withTransaction,identity,peerIdentity,messageId,attemptObjectKey,
  generation,contentSha256,contentBytes,proof,now=Date.now()
}={}){
  if(typeof withTransaction!=='function')
    throw new TypeError('Trusted PostgreSQL transaction required');
  if(!ID.test(identity||'')||!ID.test(peerIdentity||'')||
    identity===peerIdentity||!MESSAGE.test(messageId||'')||
    generation!==1||!SHA.test(contentSha256||'')||
    !Number.isSafeInteger(contentBytes)||contentBytes<1||
    contentBytes>MAX_BYTES)
    return denied('INVALID_R2_ATTEMPT_CONFIRM_REQUEST',400);
  try{validateOpaqueObjectKey(attemptObjectKey);}
  catch(_){return denied('INVALID_R2_ATTEMPT_CONFIRM_REQUEST',400);}

  const verified=await executeAuthenticatedR2Action({
    withTransaction,operation:'R2_CONFIRM',
    identity,peerIdentity,
    fields:{messageId,objectKey:attemptObjectKey,
      generation,contentSha256,contentBytes},
    proof,now,
    action:async(tx,ctx)=>{
      // Lock parent before attempt, aligned with allocation and PUT.
      const logical=await tx(
        `SELECT object_key,sender_identity,recipient_identity,state,
                upload_generation,
                (upload_lease_expires_at>clock_timestamp()) AS lease_active
           FROM public.message_r2_objects
          WHERE message_id=$1 FOR UPDATE`,[messageId]
      );
      if(logical?.rowCount!==1)return denied('R2_ATTEMPT_PARENT_MISSING',404);
      const parent=logical.rows[0];
      if(parent.sender_identity?.toUpperCase()!==ctx.authenticatedIdentity||
        parent.recipient_identity?.toUpperCase()!==ctx.peerIdentity)
        return denied('R2_ATTEMPT_PARENT_NOT_OWNER',403);
      if(parent.state!=='RESERVED'||parent.upload_generation!==1||
        parent.lease_active!==true)
        return denied('R2_ATTEMPT_PARENT_INACTIVE');
      if(parent.object_key===attemptObjectKey)
        return denied('R2_ATTEMPT_LEGACY_KEY_FORBIDDEN');

      const found=await tx(
        `SELECT object_key,message_id,generation,sender_identity,
                recipient_identity,state
           FROM public.message_r2_upload_attempts
          WHERE object_key=$1 AND message_id=$2 FOR UPDATE`,
        [attemptObjectKey,messageId]
      );
      if(found?.rowCount!==1)return denied('R2_ATTEMPT_NOT_FOUND',404);
      const attempt=found.rows[0];
      if(attempt.object_key!==attemptObjectKey||
         attempt.message_id!==messageId||
         attempt.sender_identity?.toUpperCase()!==ctx.authenticatedIdentity||
         attempt.recipient_identity?.toUpperCase()!==ctx.peerIdentity)
        return denied('R2_ATTEMPT_NOT_OWNER',403);
      if(!Number.isSafeInteger(attempt.generation)||
         attempt.generation<1||attempt.generation>5||
         !['PUT_ISSUED','VERIFYING'].includes(attempt.state))
        return denied('R2_ATTEMPT_NOT_CONFIRMABLE');
      // A claim is per-PHYSICAL key, not the logical object's object_key.
      const existing=await tx(
        `SELECT claim_id,state,content_sha256,content_bytes,
                (lease_expires_at>clock_timestamp()) AS active
           FROM public.r2_attempt_verification_claims
          WHERE object_key=$1 FOR UPDATE`,[attemptObjectKey]
      );
      if(existing.rowCount>1)throw Error('R2_ATTEMPT_CLAIM_DUPLICATED');
      const claim=existing.rows[0];
      if(claim){
        if(claim.state==='COMPLETED')
          return denied('R2_ATTEMPT_ALREADY_VERIFIED');
        if(claim.state==='RUNNING'&&claim.active===true)
          return denied('R2_ATTEMPT_CONFIRM_IN_PROGRESS');
        if(claim.content_sha256!==contentSha256||
           Number(claim.content_bytes)!==contentBytes)
          return denied('R2_ATTEMPT_DESCRIPTOR_CONFLICT');
      }
      // Each admitted verification read is charged, including failed GETs.
      await tx(
        `INSERT INTO public.r2_verification_daily_budget(identity_id,day_utc)
         VALUES($1,(clock_timestamp() AT TIME ZONE 'UTC')::date)
         ON CONFLICT DO NOTHING`,[ctx.authenticatedIdentity]
      );
      const budget=await tx(
        `SELECT attempts,reserved_bytes FROM public.r2_verification_daily_budget
          WHERE identity_id=$1
            AND day_utc=(clock_timestamp() AT TIME ZONE 'UTC')::date
          FOR UPDATE`,[ctx.authenticatedIdentity]
      );
      if(budget.rowCount!==1)throw Error('R2_ATTEMPT_BUDGET_UNAVAILABLE');
      const count=Number(budget.rows[0].attempts);
      const bytes=Number(budget.rows[0].reserved_bytes);
      if(!Number.isSafeInteger(count)||!Number.isSafeInteger(bytes)||
         count<0||bytes<0)throw Error('R2_ATTEMPT_BUDGET_INVALID');
      if(count>=MAX_DAILY_VERIFICATIONS||
         bytes+contentBytes>MAX_DAILY_VERIFIED_BYTES)
        return denied('R2_DAILY_VERIFY_QUOTA_EXCEEDED',429);

      const claimId=crypto.randomUUID();
      const written=await tx(
        `INSERT INTO public.r2_attempt_verification_claims
           (object_key,message_id,identity_id,generation,claim_id,
            content_sha256,content_bytes,state,lease_expires_at)
         VALUES($1,$2,$3,$4,$5,$6,$7,'RUNNING',
           clock_timestamp()+($8::integer*interval '1 second'))
         ON CONFLICT (object_key) DO UPDATE SET
           claim_id=EXCLUDED.claim_id,state='RUNNING',
           started_at=clock_timestamp(),
           lease_expires_at=EXCLUDED.lease_expires_at,completed_at=NULL
         WHERE public.r2_attempt_verification_claims.state='FAILED'
            OR (public.r2_attempt_verification_claims.state='RUNNING'
                AND public.r2_attempt_verification_claims.lease_expires_at<clock_timestamp())
         RETURNING claim_id,lease_expires_at`,
        [attemptObjectKey,messageId,ctx.authenticatedIdentity,
          attempt.generation,claimId,contentSha256,contentBytes,
          VERIFICATION_CLAIM_SECONDS]
      );
      if(written?.rowCount!==1)
        return denied('R2_ATTEMPT_CONFIRM_IN_PROGRESS');

      if(attempt.state==='PUT_ISSUED'){
        const state=await tx(
          `UPDATE public.message_r2_upload_attempts
              SET state='VERIFYING'
            WHERE object_key=$1 AND message_id=$2
              AND generation=$3 AND state='PUT_ISSUED'
            RETURNING object_key`,
          [attemptObjectKey,messageId,attempt.generation]
        );
        if(state.rowCount!==1)
          throw Error('R2_ATTEMPT_VERIFY_STATE_CONFLICT');
      }
      const charged=await tx(
        `UPDATE public.r2_verification_daily_budget
            SET attempts=attempts+1,reserved_bytes=reserved_bytes+$2
          WHERE identity_id=$1
            AND day_utc=(clock_timestamp() AT TIME ZONE 'UTC')::date
          RETURNING attempts,reserved_bytes`,
        [ctx.authenticatedIdentity,contentBytes]
      );
      if(charged.rowCount!==1)throw Error('R2_ATTEMPT_VERIFY_QUOTA_WRITE_FAILED');
      return Object.freeze({
        granted:true,claimId,attemptGeneration:attempt.generation,
        expiresAt:written.rows[0].lease_expires_at
      });
    }
  });
  if(verified?.authorized!==true)
    return denied(verified?.code||'R2_ATTEMPT_CONFIRM_NOT_AUTHORIZED',
      verified?.statusCode||403);
  if(verified.value?.granted!==true)return verified.value;
  return Object.freeze({
    authorized:true,granted:true,identity,peerIdentity,messageId,
    attemptObjectKey,attemptGeneration:verified.value.attemptGeneration,
    protocolGeneration:1,expectedSha256:contentSha256,
    expectedBytes:contentBytes,claimId:verified.value.claimId,
    expiresAt:verified.value.expiresAt
  });
}
module.exports={admitAuthenticatedAttemptConfirm};
