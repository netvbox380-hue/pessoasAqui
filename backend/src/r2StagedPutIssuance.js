'use strict';

/**
 * PessoasAqui Stage 30T — authenticated create-only PUT issuance (STAGED).
 *
 * NO server.js route. NO credentials, AWS SDK or network on import.
 * A fresh P-256 R2_ISSUE_PUT proof authenticates sender/peer/message/key.
 * The same PostgreSQL transaction holds bilateral FOR SHARE, nonce insertion,
 * object FOR UPDATE, DB lease and durable PUT retirement ledger.
 *
 * Signer MUST be local SigV4 (no network) and only injected by trusted backend.
 * Do not expose a URL before the wrapping transaction has COMMITTED.
 *
 * R2_ISSUE_PUT is cryptographically distinct from R2_RESERVE and requires
 * a separate Android signature with a fresh nonce. Old RESERVE signatures
 * are invalid for PUT issuance. A signed PUT URL is a bearer capability.
 */
const {executeAuthenticatedR2Action}=require('./r2ActionAuth');
const {issuePutWithDurableRetirementFence}=
  require('./r2PutRetirementFence');
const {validateOpaqueObjectKey}=require('./r2CiphertextAdapter');
const ID=/^[A-F0-9]{16}$/;

const ACCEPTABLE_MESSAGES=/^[^\u0000-\u001f\u007f]{1,256}$/;
const deny=(code,statusCode=403)=>Object.freeze({
  authorized:false,issued:false,code,statusCode
});
function safeResult(result){
  const accepted=['INVALID_R2_ACTION','INVALID_R2_IDENTITY_PROOF',
    'NO_MUTUAL_R2_CONNECTION','R2_PROOF_REPLAY'];
  return deny(accepted.includes(result?.code)?result.code:
    'R2_PUT_NOT_AUTHORIZED',
  Number.isSafeInteger(result?.statusCode)&&
    result.statusCode>=400&&result.statusCode<=499?
    result.statusCode:403);
}
async function issueAuthenticatedR2Put({
  withTransaction,identity,peerIdentity,messageId,objectKey,proof,
  bucket,signPutUrl,now=Date.now()
}={}){
  if(typeof withTransaction!=='function'||typeof signPutUrl!=='function'||
    typeof bucket!=='string'||!(/^[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$/).test(bucket))
    throw new TypeError('Trusted R2 PUT transaction, signer and bucket required');
  if(!ID.test(identity||'')||!ID.test(peerIdentity||'')||
    identity===peerIdentity||typeof messageId!=='string'||
    !ACCEPTABLE_MESSAGES.test(messageId))
    return deny('INVALID_R2_PUT_REQUEST',400);
  try{validateOpaqueObjectKey(objectKey);}
  catch(_){return deny('INVALID_R2_PUT_REQUEST',400);}

  const verified=await executeAuthenticatedR2Action({
    withTransaction,operation:'R2_ISSUE_PUT',
    identity,peerIdentity,fields:{messageId,objectKey},proof,now,
    action:async(tx,context)=>{
      // Row lock prevents concurrent finalization/expiry state mutations.
      const rows=await tx(
        `SELECT object_key,message_id,sender_identity,recipient_identity,
                state,upload_generation,
                (upload_lease_expires_at>clock_timestamp()) AS lease_active
           FROM public.message_r2_objects
          WHERE object_key=$1 AND message_id=$2 FOR UPDATE`,
        [objectKey,messageId]
      );
      if(rows?.rowCount!==1)return deny('R2_PUT_RESERVATION_NOT_FOUND',404);
      const media=rows.rows[0];
      if(media.sender_identity?.toUpperCase()!==context.authenticatedIdentity||
         media.recipient_identity?.toUpperCase()!==context.peerIdentity)
        return deny('R2_PUT_NOT_OWNER');
      if(media.state!=='RESERVED'||media.upload_generation!==1||
         media.lease_active!==true)
        return deny('R2_PUT_LEASE_OR_STATE_INVALID',409);
      // All SQL in issuePutWithDurableRetirementFence shares the SAME tx.
      // The wrapper must not call BEGIN/COMMIT or open another PG client.
      const capability=await issuePutWithDurableRetirementFence({
        withTransaction:async callback=>callback(tx),
        signPutUrl,bucket,objectKey,
        authorizedReservation:{
          reserved:true,generation:1,objectKey,bucket
        }
      });
      return Object.freeze({issued:true,capability});
    }
  });
  if(verified?.authorized!==true)return safeResult(verified);
  if(verified.value?.issued!==true)
    return Object.freeze({authorized:false,...verified.value,issued:false});
  return Object.freeze({
    authorized:true,issued:true,
    method:'PUT',objectKey,
    // The caller MUST send ONLY after the DB transaction returned COMMIT.
    capability:verified.value.capability
  });
}
module.exports={issueAuthenticatedR2Put};
