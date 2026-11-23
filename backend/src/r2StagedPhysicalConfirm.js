'use strict';

/**
 * PessoasAqui Stage 30W — staged, not routed physical R2 confirmation.
 *
 * 1. Authenticate R2_CONFIRM and durably claim budget (30V).
 * 2. Retire PUT issuance and require the *last* signed PUT expiry + 120s.
 * 3. Outside all SQL transactions GET ciphertext, check length and SHA-256.
 * 4. In a new short transaction revalidate pair, active key, claim, lease,
 *    put retirement and state; atomically mark UPLOADED + settle COMPLETED.
 *
 * No SDK is instantiated, no route/worker is started, no production imports.
 * A signed capability that remains active can overwrite a pending object:
 * physical verification MUST NOT proceed before its retirement barrier.
 */
const {admitAuthenticatedR2Confirm}=require('./r2StagedConfirmAdmission');
const {verifyStoredCiphertext}=require('./r2ObjectIntegrity');
const {markLeasedMediaUploaded}=require('./r2UploadLeaseRegistry');
const {settleR2Verification}=require('./r2VerificationAdmission');
const {sealPutKeyForRetirement,SAFETY_SECONDS}=
  require('./r2PutRetirementFence');
const {validateOpaqueObjectKey}=require('./r2CiphertextAdapter');

const ID=/^[A-F0-9]{16}$/;
const SHA=/^[a-f0-9]{64}$/;
const UUID=/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const MAX=25*1024*1024;
const refuse=(code,statusCode=409)=>Object.freeze({confirmed:false,code,statusCode});

function validTicket(t){
  if(!t||t.authorized!==true||t.granted!==true||
    !ID.test(t.identity||'')||!ID.test(t.peerIdentity||'')||
    t.identity===t.peerIdentity||
    typeof t.messageId!=='string'||!t.messageId||
    t.messageId.length>256||/[\u0000-\u001f\u007f]/.test(t.messageId)||
    t.generation!==1||!SHA.test(t.expectedSha256||'')||
    !Number.isSafeInteger(t.expectedBytes)||t.expectedBytes<1||
    t.expectedBytes>MAX||!UUID.test(t.claimId||''))return false;
  try{validateOpaqueObjectKey(t.objectKey);}
  catch(_){return false;}
  return true;
}
async function getLiveClaim(tx,ticket){
  const r=await tx(
    `SELECT claim_id,object_key,message_id,identity_id,generation,state,
            (lease_expires_at>clock_timestamp()) AS claim_active
       FROM public.r2_verification_claims
      WHERE object_key=$1 FOR UPDATE`,
    [ticket.objectKey]
  );
  if(r.rowCount!==1)return false;
  const c=r.rows[0];
  return String(c.claim_id).toLowerCase()===ticket.claimId.toLowerCase()&&
    c.object_key===ticket.objectKey&&
    c.message_id===ticket.messageId&&
    c.identity_id?.toUpperCase()===ticket.identity&&
    c.generation===1&&c.state==='RUNNING'&&c.claim_active===true;
}
async function checkPairAndMedia(tx,ticket,activePublicKey){
  const key=await tx(
    `SELECT public_key_spki FROM public.identity_key_bindings
       WHERE identity_id=$1 FOR SHARE`,[ticket.identity]
  );
  if(key.rowCount!==1||key.rows[0].public_key_spki!==activePublicKey)
    return refuse('R2_CONFIRM_ACTIVE_KEY_CHANGED',403);
  const pair=await tx(
    `SELECT from_identity,to_identity,is_mutual FROM public.connections
      WHERE (LOWER(from_identity)=LOWER($1) AND LOWER(to_identity)=LOWER($2))
         OR (LOWER(from_identity)=LOWER($2) AND LOWER(to_identity)=LOWER($1))
      FOR SHARE`,[ticket.identity,ticket.peerIdentity]
  );
  const forward=pair.rows.some(row=>
    row.from_identity?.toUpperCase()===ticket.identity&&
    row.to_identity?.toUpperCase()===ticket.peerIdentity&&
    row.is_mutual===true);
  const reverse=pair.rows.some(row=>
    row.from_identity?.toUpperCase()===ticket.peerIdentity&&
    row.to_identity?.toUpperCase()===ticket.identity&&
    row.is_mutual===true);
  if(pair.rowCount!==2||!forward||!reverse)
    return refuse('R2_CONFIRM_PAIR_REVOKED',403);
  const obj=await tx(
    `SELECT object_key,message_id,sender_identity,recipient_identity,
            state,upload_generation,
            (upload_lease_expires_at>clock_timestamp()) AS lease_active
       FROM public.message_r2_objects
      WHERE object_key=$1 AND message_id=$2 FOR UPDATE`,
    [ticket.objectKey,ticket.messageId]
  );
  if(obj.rowCount!==1)return refuse('R2_CONFIRM_OBJECT_MISSING',404);
  const m=obj.rows[0];
  if(m.sender_identity?.toUpperCase()!==ticket.identity||
     m.recipient_identity?.toUpperCase()!==ticket.peerIdentity||
     m.state!=='RESERVED'||m.upload_generation!==1||
     m.lease_active!==true)
    return refuse('R2_CONFIRM_OBJECT_INACTIVE');
  return null;
}
/**
 * First transaction seals the PUT capability ledger, then checks the
 * authoritative DB clock after the maximum URL expiry + safety margin.
 * This check deliberately causes retry-later for unexpired signed URLs.
 * Never hold locks while waiting or downloading from storage.
 */
async function preflightRetiredR2Put({withTransaction,ticket,activePublicKey}){
  return withTransaction(async tx=>{
    const denied=await checkPairAndMedia(tx,ticket,activePublicKey);
    if(denied)return denied;
    if(!await getLiveClaim(tx,ticket))
      return refuse('R2_CONFIRM_CLAIM_INACTIVE');
    // Seal after validating object owner, pair and claim.
    await sealPutKeyForRetirement({tx,objectKey:ticket.objectKey});
    const gate=await tx(
      `SELECT object_key FROM public.r2_put_capability_windows
        WHERE object_key=$1 AND sealed_at IS NOT NULL
          AND clock_timestamp()>=expires_at+
              ($2::integer*interval '1 second')
        FOR UPDATE`,[ticket.objectKey,SAFETY_SECONDS]
    );
    if(gate.rowCount!==1)
      return refuse('R2_CONFIRM_PUT_WINDOW_ACTIVE',409);
    return Object.freeze({ready:true});
  });
}
/**
 * Only call after a successful verifyStoredCiphertext GET in THIS process.
 * It MUST never be exposed as an HTTP operation accepting a client-supplied
 * 'verified' object, claimId or SHA-256 success flag.
 */
async function finalizeVerifiedR2Upload({
  withTransaction,ticket,activePublicKey,verification
}){
  if(typeof withTransaction!=='function'||!validTicket(ticket)||
    typeof activePublicKey!=='string'||!activePublicKey)
    throw new TypeError('Trusted physical R2 confirmation context required');
  if(verification?.verified!==true||
    verification.objectKey!==ticket.objectKey||
    verification.bytes!==ticket.expectedBytes||
    verification.sha256!==ticket.expectedSha256)
    return refuse('R2_CONFIRM_PHYSICAL_VERIFICATION_FAILED',409);
  return withTransaction(async tx=>{
    const denied=await checkPairAndMedia(tx,ticket,activePublicKey);
    if(denied)return denied;
    if(!await getLiveClaim(tx,ticket))
      return refuse('R2_CONFIRM_CLAIM_INACTIVE');
    // A later reissue must be impossible; this lock serializes with the
    // signing/retirement transaction on the same durable ledger row.
    const gate=await tx(
      `SELECT object_key FROM public.r2_put_capability_windows
        WHERE object_key=$1 AND sealed_at IS NOT NULL
          AND clock_timestamp()>=expires_at+
              ($2::integer*interval '1 second')
        FOR UPDATE`,[ticket.objectKey,SAFETY_SECONDS]
    );
    if(gate.rowCount!==1)return refuse('R2_CONFIRM_PUT_WINDOW_ACTIVE');
    const uploaded=await markLeasedMediaUploaded({
      query:tx,objectKey:ticket.objectKey,messageId:ticket.messageId,
      generation:1,contentSha256:ticket.expectedSha256,
      contentBytes:ticket.expectedBytes
    });
    if(uploaded.uploaded!==true||uploaded.duplicate===true)
      throw Error('R2_CONFIRM_UPLOAD_TRANSITION_FAILED');
    const settled=await settleR2Verification({
      query:tx,claimId:ticket.claimId,objectKey:ticket.objectKey,
      authenticatedIdentity:ticket.identity,successful:true
    });
    // Any settlement failure MUST ROLLBACK the previous UPLOADED UPDATE.
    if(settled.settled!==true)
      throw Error('R2_CONFIRM_SETTLEMENT_FAILED');
    return Object.freeze({
      confirmed:true,messageId:ticket.messageId,objectKey:ticket.objectKey,
      bytes:ticket.expectedBytes,sha256:ticket.expectedSha256
    });
  });
}
/**
 * Staged-only orchestrator. The caller supplies only server-owned trusted
 * dependencies (never callbacks or verification evidence from Android).
 * This is not an Express handler and never emits a delivered-message ACK.
 */
async function verifyAndFinalizeStagedR2Upload({
  withTransaction,identity,peerIdentity,messageId,objectKey,generation,
  contentSha256,contentBytes,proof,client,GetObjectCommand,bucket,
  verifyCiphertext=verifyStoredCiphertext,
  admitConfirm=admitAuthenticatedR2Confirm,
  timeoutMs=30000,now=Date.now()
}={}){
  if(typeof withTransaction!=='function'||typeof verifyCiphertext!=='function'||
     typeof admitConfirm!=='function'||!client||
     typeof GetObjectCommand!=='function'||typeof bucket!=='string')
    throw new TypeError('Trusted R2 verification dependencies required');
  const ticket=await admitConfirm({
    withTransaction,identity,peerIdentity,messageId,objectKey,generation,
    contentSha256,contentBytes,proof,now
  });
  if(!validTicket(ticket))
    return refuse(ticket?.code||'R2_CONFIRM_ADMISSION_DENIED',
      ticket?.statusCode||403);
  const activePublicKey=proof?.publicKey;
  if(typeof activePublicKey!=='string'||!activePublicKey)
    return refuse('R2_CONFIRM_PROOF_KEY_MISSING',401);
  const preflight=await preflightRetiredR2Put({
    withTransaction,ticket,activePublicKey
  });
  if(preflight.ready!==true)return preflight;
  let verification;
  try{
    // Absolutely NO database transaction or PostgreSQL lock held here.
    verification=await verifyCiphertext({
      client,GetObjectCommand,bucket,objectKey:ticket.objectKey,
      expectedSha256:ticket.expectedSha256,
      expectedBytes:ticket.expectedBytes,
      maxBytes:MAX,timeoutMs
    });
  }catch(_){
    return refuse('R2_CONFIRM_STORAGE_VERIFICATION_FAILED',503);
  }
  return finalizeVerifiedR2Upload({
    withTransaction,ticket,activePublicKey,verification
  });
}
module.exports={
  preflightRetiredR2Put,finalizeVerifiedR2Upload,
  verifyAndFinalizeStagedR2Upload
};
