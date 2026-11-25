'use strict';

/**
 * PessoasAqui 30Y-D1 — authenticated E2EE attachment to ONE immutable
 * verified physical winner. STAGED ONLY; not imported by server.js.
 *
 * Requires migration 022. Never uses parent.object_key for storage access.
 * No GET/PUT, no delivery ACK, no plaintext or E2EE key material.
 */
const crypto=require('node:crypto');
const {executeAuthenticatedR2Action}=require('./r2ActionAuth');
const {validateOpaqueObjectKey}=require('./r2CiphertextAdapter');
const ID=/^[A-F0-9]{16}$/;
const MESSAGE=/^[^\u0000-\u001f\u007f]{1,256}$/;
const SHA=/^[a-f0-9]{64}$/;
const MAX=25*1024*1024;
const refuse=(code,statusCode=409)=>Object.freeze({
  success:false,attached:false,code,statusCode
});
function validateInput(x){
  if(!ID.test(x.identity||'')||!ID.test(x.peerIdentity||'')||
     x.identity===x.peerIdentity||!MESSAGE.test(x.messageId||'')||
     x.generation!==1||!SHA.test(x.contentSha256||'')||
     !SHA.test(x.ciphertextDigest||'')||
     !Number.isSafeInteger(x.contentBytes)||x.contentBytes<1||
     x.contentBytes>MAX||typeof x.senderAlias!=='string'||
     Buffer.byteLength(x.senderAlias,'utf8')>256||
     typeof x.ciphertextPayload!=='string'||
     !x.ciphertextPayload.startsWith('ENC_ECDH:')||
     Buffer.byteLength(x.ciphertextPayload,'utf8')<16||
     Buffer.byteLength(x.ciphertextPayload,'utf8')>65536||
     typeof x.ivNonce!=='string'||!x.ivNonce||
     x.ivNonce.length>128||/[\u0000-\u001f\u007f]/.test(x.ivNonce))
    return false;
  try{validateOpaqueObjectKey(x.objectKey);}catch(_){return false;}
  return crypto.createHash('sha256').update(x.ciphertextPayload,'utf8')
    .digest('hex')===x.ciphertextDigest;
}
async function attachAuthenticatedWinningR2Media({
  withTransaction,identity,peerIdentity,messageId,objectKey,generation,
  contentSha256,contentBytes,ciphertextDigest,ciphertextPayload,
  ivNonce,senderAlias,proof,now=Date.now()
}={}){
  if(typeof withTransaction!=='function')
    throw new TypeError('Trusted PostgreSQL transaction required');
  const x={identity,peerIdentity,messageId,objectKey,generation,
    contentSha256,contentBytes,ciphertextDigest,ciphertextPayload,
    ivNonce,senderAlias};
  if(!validateInput(x))return refuse('INVALID_R2_WINNER_ATTACH_REQUEST',400);
  const outcome=await executeAuthenticatedR2Action({
    withTransaction,operation:'R2_ATTACH',identity,peerIdentity,
    fields:{messageId,objectKey,generation,contentSha256,
      contentBytes,ciphertextDigest},proof,now,
    action:async(tx,ctx)=>{
      // Auth gate already holds key binding and 2 mutual connection rows
      // FOR SHARE. Lock parent before verifying winner and message.
      const parent=await tx(
        `SELECT object_key,sender_identity,recipient_identity,state,
                upload_generation,attached_at,
                (upload_lease_expires_at>clock_timestamp()) AS lease_active
           FROM public.message_r2_objects
          WHERE message_id=$1 FOR UPDATE`,[messageId]
      );
      if(parent.rowCount!==1)return refuse('R2_ATTACH_PARENT_MISSING',404);
      const m=parent.rows[0];
      if(m.sender_identity?.toUpperCase()!==ctx.authenticatedIdentity||
         m.recipient_identity?.toUpperCase()!==ctx.peerIdentity)
        return refuse('R2_ATTACH_PARENT_NOT_OWNER',403);
      if(m.object_key===objectKey||m.upload_generation!==1||
        !['RESERVED','ATTACHED'].includes(m.state))
        return refuse('R2_ATTACH_PARENT_INVALID');
      // Only the SELECTED physical winner, never an arbitrary attempt key.
      const w=await tx(
        `SELECT w.object_key,w.message_id,w.sender_identity,
                w.recipient_identity,w.generation,w.claim_id,
                w.content_sha256,w.content_bytes,
                a.state AS attempt_state,
                c.state AS claim_state,c.completed_at
           FROM public.message_r2_winning_attempts w
           JOIN public.message_r2_upload_attempts a
             ON a.object_key=w.object_key AND a.message_id=w.message_id
           JOIN public.r2_attempt_verification_claims c
             ON c.claim_id=w.claim_id AND c.object_key=w.object_key
          WHERE w.message_id=$1 AND w.object_key=$2
          FOR SHARE OF w,a,c`,[messageId,objectKey]
      );
      if(w.rowCount!==1)return refuse('R2_ATTACH_NOT_WINNER',403);
      const v=w.rows[0];
      if(v.sender_identity!==ctx.authenticatedIdentity||
         v.recipient_identity!==ctx.peerIdentity||
         v.generation<1||v.generation>5||
         v.content_sha256!==contentSha256||
         Number(v.content_bytes)!==contentBytes||
         v.attempt_state!=='VERIFIED'||v.claim_state!=='COMPLETED'||
         !v.completed_at)
        return refuse('R2_ATTACH_WINNER_NOT_VERIFIED');

      // Terminal receipt must be checked even for an exact idempotent retry.
      const terminal=await tx(
        'SELECT state FROM public.message_delivery_receipts WHERE message_id=$1',
        [messageId]
      );
      if(terminal.rowCount!==0)return refuse('MESSAGE_ALREADY_TERMINAL',410);
      const prev=await tx(
        `SELECT sender_identity,recipient_identity,sender_alias,
                ciphertext_payload,iv_nonce,delivery_state,is_cancelled,
                is_delivered,r2_winning_object_key
           FROM public.e2ee_messages
          WHERE id=$1 FOR UPDATE`,[messageId]
      );
      if(prev.rowCount>1)throw Error('R2_ATTACH_DUPLICATE_MESSAGE_ROW');
      const old=prev.rows[0];
      const exact=old&&old.sender_identity?.toUpperCase()===ctx.authenticatedIdentity&&
        old.recipient_identity?.toUpperCase()===ctx.peerIdentity&&
        old.sender_alias===senderAlias&&
        old.ciphertext_payload===ciphertextPayload&&
        (old.iv_nonce||'')===ivNonce&&
        old.delivery_state==='PENDING'&&old.is_cancelled!==true&&
        old.is_delivered===false&&
        old.r2_winning_object_key===objectKey;
      if(old&&!exact)return refuse('R2_ATTACH_MESSAGE_COLLISION');
      if(m.state==='ATTACHED'){
        if(exact)return Object.freeze({
          success:true,attached:true,duplicate:true,messageId,objectKey
        });
        return refuse('R2_ATTACH_PARENT_STATE_CONFLICT');
      }
      if(m.lease_active!==true)return refuse('R2_ATTACH_LEASE_EXPIRED');
      if(old)return refuse('R2_ATTACH_ORPHANED_MESSAGE');
      const inserted=await tx(
        `INSERT INTO public.e2ee_messages
          (id,sender_identity,recipient_identity,sender_alias,
           ciphertext_payload,iv_nonce,is_delivered,
           accepted_at,expires_at,delivery_state,r2_winning_object_key)
         SELECT $1,$2,$3,$4,$5,$6,FALSE,
                ts,ts+interval '48 hours','PENDING',$7
           FROM (SELECT clock_timestamp() AS ts) c
         ON CONFLICT (id) DO NOTHING RETURNING id`,
        [messageId,ctx.authenticatedIdentity,ctx.peerIdentity,
          senderAlias,ciphertextPayload,ivNonce,objectKey]
      );
      if(inserted.rowCount!==1)throw Error('R2_ATTACH_MESSAGE_INSERT_CONFLICT');
      const updated=await tx(
        `UPDATE public.message_r2_objects
            SET state='ATTACHED',attached_at=clock_timestamp(),
                last_changed_at=clock_timestamp()
          WHERE message_id=$1 AND state='RESERVED'
            AND upload_lease_expires_at>clock_timestamp()
          RETURNING message_id`,[messageId]
      );
      if(updated.rowCount!==1)throw Error('R2_ATTACH_PARENT_TRANSITION_FAILED');
      return Object.freeze({
        success:true,attached:true,duplicate:false,messageId,objectKey
      });
    }
  });
  if(outcome?.authorized!==true)
    return refuse(outcome?.code||'R2_ATTACH_NOT_AUTHORIZED',
      outcome?.statusCode||403);
  return outcome.value;
}
module.exports={attachAuthenticatedWinningR2Media};
