'use strict';

/**
 * PessoasAqui Stage 30Y-C2 — physical attempt winner finalization, STAGED.
 *
 * Not routed and MUST NOT be invoked with client-supplied verification/claim.
 * The only exported end-to-end entrypoint performs authenticated admission,
 * retired PUT check, real bounded GET+SHA-256 outside SQL, then finalization.
 *
 * Does NOT mark the LEGACY logical media row UPLOADED, attach messages, or
 * enable READ/GC. Winner pin is a separate immutable table (migration 020).
 *
 * IMPORTANT: expiry+120s is not a physical guarantee that an HTTP PUT
 * started earlier has finished. Cutover remains blocked until R2 in-flight
 * PUT fencing and write-once semantics are independently validated.
 */
const {admitAuthenticatedAttemptConfirm}=
  require('./r2StagedAttemptConfirmAdmission');
const {verifyStoredCiphertext}=require('./r2ObjectIntegrity');
const {sealPutKeyForRetirement,SAFETY_SECONDS}=
  require('./r2PutRetirementFence');
const {validateOpaqueObjectKey}=require('./r2CiphertextAdapter');
const ID=/^[A-F0-9]{16}$/;
const SHA=/^[a-f0-9]{64}$/;
const UUID=/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const MAX=25*1024*1024;
const reject=(code,statusCode=409)=>Object.freeze({
  confirmed:false,selected:false,code,statusCode
});
function validTicket(t){
  if(!t||t.authorized!==true||t.granted!==true||
     !ID.test(t.identity||'')||!ID.test(t.peerIdentity||'')||
     t.identity===t.peerIdentity||typeof t.messageId!=='string'||
     !t.messageId||t.messageId.length>256||
     /[\u0000-\u001f\u007f]/.test(t.messageId)||
     !Number.isSafeInteger(t.attemptGeneration)||
     t.attemptGeneration<1||t.attemptGeneration>5||
     t.protocolGeneration!==1||!SHA.test(t.expectedSha256||'')||
     !Number.isSafeInteger(t.expectedBytes)||t.expectedBytes<1||
     t.expectedBytes>MAX||!UUID.test(t.claimId||''))return false;
  try{validateOpaqueObjectKey(t.attemptObjectKey);}catch(_){return false;}
  return true;
}
async function authorizeAttemptState(tx,t,activePublicKey){
  const key=await tx(
    'SELECT public_key_spki FROM public.identity_key_bindings WHERE identity_id=$1 FOR SHARE',
    [t.identity]
  );
  if(key.rowCount!==1||key.rows[0]?.public_key_spki!==activePublicKey)
    return reject('R2_WINNER_KEY_ROTATED',403);
  const pair=await tx(
    `SELECT from_identity,to_identity,is_mutual FROM public.connections
      WHERE (LOWER(from_identity)=LOWER($1) AND LOWER(to_identity)=LOWER($2))
         OR (LOWER(from_identity)=LOWER($2) AND LOWER(to_identity)=LOWER($1))
      FOR SHARE`,[t.identity,t.peerIdentity]
  );
  if(pair.rowCount!==2||!pair.rows.some(x=>
      x.from_identity?.toUpperCase()===t.identity&&
      x.to_identity?.toUpperCase()===t.peerIdentity&&x.is_mutual===true
    )||!pair.rows.some(x=>
      x.from_identity?.toUpperCase()===t.peerIdentity&&
      x.to_identity?.toUpperCase()===t.identity&&x.is_mutual===true))
    return reject('R2_WINNER_PAIR_REVOKED',403);

  const parent=await tx(
    `SELECT object_key,sender_identity,recipient_identity,state,
            upload_generation,
            (upload_lease_expires_at>clock_timestamp()) AS lease_active
       FROM public.message_r2_objects
      WHERE message_id=$1 FOR UPDATE`,[t.messageId]
  );
  if(parent.rowCount!==1)return reject('R2_WINNER_PARENT_NOT_FOUND',404);
  const m=parent.rows[0];
  if(m.sender_identity?.toUpperCase()!==t.identity||
     m.recipient_identity?.toUpperCase()!==t.peerIdentity||
     m.object_key===t.attemptObjectKey||
     m.state!=='RESERVED'||m.upload_generation!==1||
     m.lease_active!==true)
    return reject('R2_WINNER_PARENT_INACTIVE');

  const attempt=await tx(
    `SELECT object_key,message_id,generation,sender_identity,
            recipient_identity,state
       FROM public.message_r2_upload_attempts
      WHERE object_key=$1 AND message_id=$2 FOR UPDATE`,
    [t.attemptObjectKey,t.messageId]
  );
  if(attempt.rowCount!==1)return reject('R2_WINNER_ATTEMPT_MISSING',404);
  const a=attempt.rows[0];
  if(a.object_key!==t.attemptObjectKey||a.message_id!==t.messageId||
     a.sender_identity?.toUpperCase()!==t.identity||
     a.recipient_identity?.toUpperCase()!==t.peerIdentity||
     a.generation!==t.attemptGeneration||a.state!=='VERIFYING')
    return reject('R2_WINNER_ATTEMPT_INACTIVE');

  const claim=await tx(
    `SELECT claim_id,message_id,identity_id,generation,content_sha256,
            content_bytes,state,
            (lease_expires_at>clock_timestamp()) AS active
       FROM public.r2_attempt_verification_claims
      WHERE object_key=$1 FOR UPDATE`,[t.attemptObjectKey]
  );
  if(claim.rowCount!==1)return reject('R2_WINNER_CLAIM_MISSING',404);
  const c=claim.rows[0];
  if(String(c.claim_id).toLowerCase()!==t.claimId.toLowerCase()||
     c.message_id!==t.messageId||
     c.identity_id?.toUpperCase()!==t.identity||
     c.generation!==t.attemptGeneration||
     c.content_sha256!==t.expectedSha256||
     Number(c.content_bytes)!==t.expectedBytes||
     c.state!=='RUNNING'||c.active!==true)
    return reject('R2_WINNER_CLAIM_INACTIVE');
  const existing=await tx(
    'SELECT object_key FROM public.message_r2_winning_attempts WHERE message_id=$1',
    [t.messageId]
  );
  if(existing.rowCount!==0)return reject('R2_WINNER_ALREADY_SELECTED');
  return null;
}
async function lockRetiredPutWindow(tx,objectKey){
  const gate=await tx(
    `SELECT object_key FROM public.r2_put_capability_windows
      WHERE object_key=$1 AND sealed_at IS NOT NULL
        AND clock_timestamp() >= expires_at +
          ($2::integer*interval '1 second')
      FOR UPDATE`,[objectKey,SAFETY_SECONDS]
  );
  return gate.rowCount===1;
}
async function preflightAttemptWinner({
  withTransaction,ticket,activePublicKey
}){
  if(typeof withTransaction!=='function'||!validTicket(ticket)||
     typeof activePublicKey!=='string'||!activePublicKey)
    throw new TypeError('Trusted attempt preflight required');
  return withTransaction(async tx=>{
    const error=await authorizeAttemptState(tx,ticket,activePublicKey);
    if(error)return error;
    // Durable seal prevents further presign: also used by physical delete GC.
    await sealPutKeyForRetirement({tx,objectKey:ticket.attemptObjectKey});
    if(!await lockRetiredPutWindow(tx,ticket.attemptObjectKey))
      return reject('R2_WINNER_PUT_WINDOW_ACTIVE');
    return Object.freeze({ready:true});
  });
}
/**
 * Internal settlement primitive: verification MUST be returned by the
 * in-process backend GET verifier; never export through HTTP or trust JSON.
 * Both SQL updates and winner INSERT are inside one COMMIT.
 */
async function settleVerifiedAttemptWinner({
  withTransaction,ticket,activePublicKey,verification
}){
  if(typeof withTransaction!=='function'||!validTicket(ticket)||
     typeof activePublicKey!=='string'||!activePublicKey)
    throw new TypeError('Trusted attempt finalization required');
  if(verification?.verified!==true||
     verification.objectKey!==ticket.attemptObjectKey||
     verification.sha256!==ticket.expectedSha256||
     verification.bytes!==ticket.expectedBytes)
    return reject('R2_WINNER_BYTES_NOT_VERIFIED');
  return withTransaction(async tx=>{
    const error=await authorizeAttemptState(tx,ticket,activePublicKey);
    if(error)return error;
    if(!await lockRetiredPutWindow(tx,ticket.attemptObjectKey))
      return reject('R2_WINNER_PUT_WINDOW_ACTIVE');
    const claim=await tx(
      `UPDATE public.r2_attempt_verification_claims
          SET state='COMPLETED',completed_at=clock_timestamp()
        WHERE object_key=$1 AND claim_id=$2
          AND identity_id=$3 AND message_id=$4 AND generation=$5
          AND state='RUNNING' AND lease_expires_at>clock_timestamp()
        RETURNING claim_id`,[
          ticket.attemptObjectKey,ticket.claimId,ticket.identity,
          ticket.messageId,ticket.attemptGeneration
        ]
    );
    if(claim.rowCount!==1)throw Error('R2_WINNER_CLAIM_SETTLEMENT_FAILED');
    const attempt=await tx(
      `UPDATE public.message_r2_upload_attempts
          SET state='VERIFIED'
        WHERE object_key=$1 AND message_id=$2 AND generation=$3
          AND state='VERIFYING'
        RETURNING object_key`,[
          ticket.attemptObjectKey,ticket.messageId,
          ticket.attemptGeneration
        ]
    );
    if(attempt.rowCount!==1)throw Error('R2_WINNER_ATTEMPT_TRANSITION_FAILED');
    const winner=await tx(
      `INSERT INTO public.message_r2_winning_attempts
         (message_id,object_key,generation,sender_identity,
          recipient_identity,claim_id,content_sha256,content_bytes)
       VALUES($1,$2,$3,$4,$5,$6,$7,$8)
       RETURNING message_id`,[
         ticket.messageId,ticket.attemptObjectKey,
         ticket.attemptGeneration,ticket.identity,ticket.peerIdentity,
         ticket.claimId,ticket.expectedSha256,ticket.expectedBytes
       ]
    );
    // Reject and roll back completed claim/attempt if pin cannot be saved.
    if(winner.rowCount!==1)throw Error('R2_WINNER_PIN_INSERT_FAILED');
    return Object.freeze({
      confirmed:true,selected:true,messageId:ticket.messageId,
      attemptObjectKey:ticket.attemptObjectKey,
      attemptGeneration:ticket.attemptGeneration,
      sha256:ticket.expectedSha256,bytes:ticket.expectedBytes
    });
  });
}
async function verifyAndSelectStagedAttemptWinner({
  withTransaction,identity,peerIdentity,messageId,attemptObjectKey,
  generation,contentSha256,contentBytes,proof,client,GetObjectCommand,
  bucket,now=Date.now(),timeoutMs=30000
}={}){
  if(typeof withTransaction!=='function'||!client||
     typeof GetObjectCommand!=='function'||typeof bucket!=='string')
    throw new TypeError('Trusted R2 staging transport required');
  const ticket=await admitAuthenticatedAttemptConfirm({
    withTransaction,identity,peerIdentity,messageId,attemptObjectKey,
    generation,contentSha256,contentBytes,proof,now
  });
  if(!validTicket(ticket))
    return reject(ticket?.code||'R2_WINNER_ADMISSION_DENIED',
      ticket?.statusCode||403);
  const activePublicKey=proof?.publicKey;
  const preflight=await preflightAttemptWinner({
    withTransaction,ticket,activePublicKey
  });
  if(!preflight.ready)return preflight;

  let verified;
  try{
    // Real streaming GET on ciphertext OUTSIDE every SQL transaction.
    // Only this built-in verifier is used; no client 'verified' option.
    verified=await verifyStoredCiphertext({
      client,GetObjectCommand,bucket,objectKey:ticket.attemptObjectKey,
      expectedSha256:ticket.expectedSha256,
      expectedBytes:ticket.expectedBytes,
      maxBytes:MAX,timeoutMs
    });
  }catch(_){
    return reject('R2_WINNER_STORAGE_VERIFICATION_FAILED',503);
  }
  return settleVerifiedAttemptWinner({
    withTransaction,ticket,activePublicKey,verification:verified
  });
}
module.exports={
  preflightAttemptWinner,settleVerifiedAttemptWinner,
  verifyAndSelectStagedAttemptWinner
};
