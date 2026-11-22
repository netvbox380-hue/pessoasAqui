'use strict';

/**
 * PessoasAqui — Etapa 30R: authenticated R2_RESERVE admission (STAGED ONLY).
 *
 * Uses the existing ECDSA P-256 / SHA-256 action proof and authoritative
 * PostgreSQL bilateral-connection check. The nonce insertion and immutable
 * 60-minute reservation and quota charge occur in ONE transaction. No SDK, URL, HTTP route,
 * credentials, network or server.js import.
 *
 * The objectKey is a fresh opaque proposal, cryptographically signed by
 * the active Android identity and bound to the exact pair/message. Unique
 * ownership and lease are decided by the authoritative PostgreSQL INSERT.
 * Future rollout must separately review key-generation provenance and the
 * presigned PUT retirement fence before exposing any storage capability.
 */
const {executeAuthenticatedR2Action}=require('./r2ActionAuth');
const {reserveLeasedMediaObject}=require('./r2UploadLeaseRegistry');
const {validateOpaqueObjectKey}=require('./r2CiphertextAdapter');
const {chargeNewR2Reservation,QUOTA_CODE}=require('./r2ReserveDailyBudget');

const ID=/^[A-F0-9]{16}$/;
function validMessageId(value){
  return typeof value==='string'&&value.length>0&&value.length<=256&&
    !/[\u0000-\u001f\u007f]/.test(value);
}
function denied(statusCode,code){
  return Object.freeze({authorized:false,reserved:false,statusCode,code});
}

/**
 * PRECONDITION: objectKey is cryptographically bound by the P-256 proof to
 * sender, peer and messageId; the DB creates the durable ownership record.
 * This module intentionally does not issue an upload URL or PUT capability.
 *
 * withTransaction must be a real BEGIN/COMMIT/ROLLBACK PostgreSQL runner;
 * action(...) receives the same transaction-bound SQL callback. No nested
 * transaction and no RAM fallback.
 */
async function admitAuthenticatedR2Reserve({
  withTransaction,identity,peerIdentity,messageId,objectKey,proof,
  now=Date.now()
}={}){
  if(typeof withTransaction!=='function')
    throw new TypeError('Trusted PostgreSQL transaction required');
  if(typeof identity!=='string'||!ID.test(identity)||
    typeof peerIdentity!=='string'||!ID.test(peerIdentity)||
    identity===peerIdentity||!validMessageId(messageId))
    return denied(400,'INVALID_R2_RESERVATION_REQUEST');
  try{validateOpaqueObjectKey(objectKey);}
  catch(_){return denied(400,'INVALID_R2_RESERVATION_REQUEST');}

  let verified;
  try{
    verified=await executeAuthenticatedR2Action({
      withTransaction,operation:'R2_RESERVE',
      identity,peerIdentity,
      fields:{messageId,objectKey},proof,now,
      action:async(tx,context)=>{
        const reservation=await reserveLeasedMediaObject({
          query:tx,objectKey,messageId,
          senderIdentity:context.authenticatedIdentity,
          recipientIdentity:context.peerIdentity
        });
        // Charge ONLY new reservations; idempotent duplicates do not renew
        // the lease and do not consume the daily quota again.
        if(reservation?.reserved===true&&reservation.duplicate!==true){
          // Throw on exhaustion to roll back BOTH the reservation and nonce.
          await chargeNewR2Reservation({
            tx,authenticatedIdentity:context.authenticatedIdentity
          });
        }
        return reservation;
      }
    });
  }catch(error){
    if(error?.code===QUOTA_CODE)
      return denied(429,QUOTA_CODE);
    throw error;
  }

  if(verified?.authorized!==true){
    return Object.freeze({
      authorized:false,reserved:false,
      statusCode:verified?.statusCode||403,
      code:verified?.code||'R2_RESERVE_NOT_AUTHORIZED'
    });
  }
  const reservation=verified.value;
  if(reservation?.reserved!==true){
    const code=typeof reservation?.code==='string'&&
      /^[A-Z0-9_]{1,80}$/.test(reservation.code)?
      reservation.code:'R2_RESERVATION_REFUSED';
    return denied(409,code);
  }
  if(reservation.generation!==1)
    throw Error('R2_RESERVATION_INVALID_GENERATION');
  return Object.freeze({
    authorized:true,reserved:true,
    duplicate:reservation.duplicate===true,
    messageId,objectKey,generation:1,
    leaseExpiresAt:reservation.leaseExpiresAt
  });
}

module.exports={admitAuthenticatedR2Reserve};
