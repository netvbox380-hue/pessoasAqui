'use strict';

// 30Y-D2 PREPARE ONLY. Database authorization for the physical winner.
// Invoke inside authenticated R2_READ or a trusted fresh revalidation tx.
// Never use the logical parent key, including on failure.
const {validateOpaqueObjectKey}=require('./r2CiphertextAdapter');
const deny=(code,statusCode=403)=>({allowed:false,code,statusCode});

async function authorizeWinningRecipientR2Read({
  tx,authenticatedIdentity,peerIdentity,messageId,objectKey
}){
  if(typeof tx!=='function')throw new TypeError('Trusted transaction required');
  try{validateOpaqueObjectKey(objectKey);}catch(_){
    return deny('INVALID_R2_READ_REQUEST',400);
  }
  if(typeof authenticatedIdentity!=='string'||typeof peerIdentity!=='string'||
    !/^[A-F0-9]{16}$/.test(authenticatedIdentity)||
    !/^[A-F0-9]{16}$/.test(peerIdentity)||authenticatedIdentity===peerIdentity||
    typeof messageId!=='string'||!messageId||messageId.length>256||
    /[\u0000-\u001f\u007f]/.test(messageId))
    return deny('INVALID_R2_READ_REQUEST',400);
  const result=await tx(
    `SELECT w.object_key,w.content_sha256,w.content_bytes
       FROM public.message_r2_objects o
       JOIN public.message_r2_winning_attempts w ON w.message_id=o.message_id
       JOIN public.message_r2_upload_attempts a
         ON a.object_key=w.object_key AND a.message_id=w.message_id
         AND a.generation=w.generation
       JOIN public.r2_attempt_verification_claims c
         ON c.claim_id=w.claim_id AND c.object_key=w.object_key
         AND c.message_id=w.message_id AND c.generation=w.generation
       JOIN public.e2ee_messages m
         ON m.id=w.message_id AND m.r2_winning_object_key=w.object_key
       WHERE w.object_key=$1 AND w.message_id=$2
         AND o.sender_identity=$3 AND o.recipient_identity=$4
         AND w.sender_identity=$3 AND w.recipient_identity=$4
         AND a.sender_identity=$3 AND a.recipient_identity=$4
         AND m.sender_identity=$3 AND m.recipient_identity=$4
         AND o.state='ATTACHED' AND o.r2_winner_attach_mode IS TRUE
         AND o.attached_at IS NOT NULL AND o.object_key<>w.object_key
         AND a.state='VERIFIED' AND c.state='COMPLETED'
         AND c.completed_at IS NOT NULL AND c.identity_id=$3
         AND c.content_sha256=w.content_sha256 AND c.content_bytes=w.content_bytes
         AND m.delivery_state='PENDING' AND m.is_delivered IS FALSE
         AND m.is_cancelled IS NOT TRUE AND m.expires_at>clock_timestamp()
       FOR SHARE OF o,w,a,c,m`,
    [objectKey,messageId,peerIdentity,authenticatedIdentity]
  );
  if(result.rowCount!==1)return deny('R2_WINNER_NOT_AUTHORIZED');
  const row=result.rows[0];
  const bytes=Number(row.content_bytes);
  if(row.object_key!==objectKey||typeof row.content_sha256!=='string'||
    !/^[a-f0-9]{64}$/.test(row.content_sha256)||
    !Number.isSafeInteger(bytes)||bytes<1||bytes>25*1024*1024)
    return deny('R2_INVALID_STORED_DESCRIPTOR',409);
  const terminal=await tx(
    'SELECT state FROM public.message_delivery_receipts WHERE message_id=$1',
    [messageId]
  );
  if(terminal.rowCount!==0)return deny('R2_MESSAGE_TERMINAL',410);
  return Object.freeze({allowed:true,authenticatedIdentity,peerIdentity,
    messageId,objectKey:row.object_key,expectedSha256:row.content_sha256,
    expectedBytes:bytes});
}
module.exports={authorizeWinningRecipientR2Read};
