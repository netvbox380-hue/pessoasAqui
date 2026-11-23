'use strict';

/**
 * PessoasAqui Stage 30V — ECDSA P-256 R2_CONFIRM verification claim.
 * PREPARE ONLY. No GET, SDK, network, HTTP endpoint or server.js import.
 *
 * The signed descriptor alone is NOT proof that ciphertext was uploaded.
 * This atomically records a bounded, distributed verification claim and
 * reserves an egress budget; a later independent GET+SHA-256 is mandatory
 * OUTSIDE the database transaction before any UPLOADED transition.
 */
const {executeAuthenticatedR2Action}=require('./r2ActionAuth');
const {claimR2Verification}=require('./r2VerificationAdmission');
const {validateOpaqueObjectKey}=require('./r2CiphertextAdapter');
const ID=/^[A-F0-9]{16}$/;
const SHA=/^[a-f0-9]{64}$/;
const MAX=25*1024*1024;
function denied(code,statusCode){
  return Object.freeze({authorized:false,granted:false,code,statusCode});
}
async function admitAuthenticatedR2Confirm({
  withTransaction,identity,peerIdentity,messageId,objectKey,generation,
  contentSha256,contentBytes,proof,now=Date.now()
}={}){
  if(typeof withTransaction!=='function')
    throw new TypeError('PostgreSQL transaction required');
  if(!ID.test(identity||'')||!ID.test(peerIdentity||'')||
    identity===peerIdentity||typeof messageId!=='string'||
    !messageId||messageId.length>256||
    /[\u0000-\u001f\u007f]/.test(messageId)||
    generation!==1||!SHA.test(contentSha256||'')||
    !Number.isSafeInteger(contentBytes)||contentBytes<1||contentBytes>MAX)
    return denied('INVALID_R2_CONFIRM_REQUEST',400);
  try{validateOpaqueObjectKey(objectKey);}
  catch(_){return denied('INVALID_R2_CONFIRM_REQUEST',400);}
  const verified=await executeAuthenticatedR2Action({
    withTransaction,operation:'R2_CONFIRM',
    identity,peerIdentity,
    fields:{messageId,objectKey,generation,contentSha256,contentBytes},
    proof,now,
    action:(tx,ctx)=>claimR2Verification({
      // IMPORTANT: reuse EXACT existing authentication transaction.
      // A nested BEGIN/COMMIT would permit orphaned claims and nonce loss.
      withTransaction:async callback=>callback(tx),
      authenticatedIdentity:ctx.authenticatedIdentity,
      recipientIdentity:ctx.peerIdentity,
      objectKey,messageId,expectedBytes:contentBytes
    })
  });
  if(verified?.authorized!==true){
    return denied(verified?.code||'R2_CONFIRM_NOT_AUTHORIZED',
      verified?.statusCode||403);
  }
  if(verified.value?.granted!==true){
    const result=verified.value||{};
    return denied(result.code||'R2_CONFIRM_CLAIM_DENIED',
      result.statusCode||409);
  }
  const claim=verified.value;
  if(claim.authenticatedIdentity!==identity||
    claim.recipientIdentity!==peerIdentity||
    claim.objectKey!==objectKey||claim.messageId!==messageId||
    claim.expectedBytes!==contentBytes||claim.generation!==1||
    typeof claim.claimId!=='string')
    throw Error('R2_CONFIRM_CLAIM_BINDING_MISMATCH');
  // This is an INTERNAL verification ticket. HTTP handlers must not return
  // claimId to Android or trust a claimId supplied in client JSON.
  return Object.freeze({
    authorized:true,granted:true,claimId:claim.claimId,
    identity,peerIdentity,messageId,objectKey,generation,
    expectedSha256:contentSha256,expectedBytes:contentBytes,
    expiresAt:claim.expiresAt
  });
}
module.exports={admitAuthenticatedR2Confirm};
