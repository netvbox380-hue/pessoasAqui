'use strict';

/**
 * PessoasAqui / R2 Stage 30R — signed RESERVE HTTP handler (NOT ROUTED).
 * No Express registration, PUT URL, R2 SDK, process.env, timers or DB on
 * import. For test harnesses and future deliberate rollout ONLY.
 */
const {admitAuthenticatedR2Reserve}=
  require('./r2StagedReserveAdmission');
const {validateOpaqueObjectKey}=require('./r2CiphertextAdapter');
const REQUIRED=['identity','peerIdentity','messageId','objectKey','proof'];
const PROOF=['v','timestamp','nonce','publicKey','signature'];
const ID=/^[A-F0-9]{16}$/;
const UUID=/^[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}$/;

function validReserveBody(b){
  if(!b||typeof b!=='object'||Array.isArray(b)||
    Object.keys(b).length!==REQUIRED.length||
    !REQUIRED.every(k=>Object.prototype.hasOwnProperty.call(b,k))||
    !ID.test(b.identity||'')||!ID.test(b.peerIdentity||'')||
    b.identity===b.peerIdentity||
    typeof b.messageId!=='string'||b.messageId.length<1||
    b.messageId.length>256||/[\u0000-\u001f\u007f]/.test(b.messageId))
    return false;
  try{validateOpaqueObjectKey(b.objectKey);}catch(_){return false;}
  const p=b.proof;
  return Boolean(p&&typeof p==='object'&&!Array.isArray(p)&&
    Object.keys(p).length===PROOF.length&&
    PROOF.every(k=>Object.prototype.hasOwnProperty.call(p,k))&&
    p.v===1&&Number.isSafeInteger(p.timestamp)&&
    UUID.test(p.nonce||'')&&
    typeof p.publicKey==='string'&&p.publicKey.length>0&&p.publicKey.length<=512&&
    typeof p.signature==='string'&&p.signature.length>0&&p.signature.length<=512);
}
function denied(res,status,code){
  return res.status(status).json({success:false,code});
}
function createStagedR2ReserveHandler({
  withTransaction,reserve=admitAuthenticatedR2Reserve
}={}){
  if(typeof withTransaction!=='function'||typeof reserve!=='function')
    throw new TypeError('Trusted R2 reservation route dependencies required');
  return async function stagedR2ReserveHandler(req,res){
    if(req?.method!=='POST')
      return denied(res,405,'R2_METHOD_NOT_ALLOWED');
    if(!validReserveBody(req.body))
      return denied(res,400,'INVALID_R2_RESERVATION_REQUEST');
    let outcome;
    try{
      outcome=await reserve({
        withTransaction,
        identity:req.body.identity,
        peerIdentity:req.body.peerIdentity,
        messageId:req.body.messageId,
        objectKey:req.body.objectKey,
        proof:req.body.proof
      });
    }catch(_){
      return denied(res,503,'R2_RESERVATION_UNAVAILABLE');
    }
    if(outcome?.authorized!==true||outcome?.reserved!==true){
      const status=Number.isSafeInteger(outcome?.statusCode)&&
        outcome.statusCode>=400&&outcome.statusCode<=499?
        outcome.statusCode:403;
      const code=typeof outcome?.code==='string'&&
        /^[A-Z0-9_]{1,80}$/.test(outcome.code)?
        outcome.code:'R2_RESERVATION_DENIED';
      return denied(res,status,code);
    }
    if(outcome.messageId!==req.body.messageId||
      outcome.objectKey!==req.body.objectKey||
      outcome.generation!==1)
      return denied(res,503,'R2_RESERVATION_MISMATCH');
    res.setHeader('Cache-Control','no-store, private');
    return res.status(200).json({
      success:true,reserved:true,
      duplicate:outcome.duplicate===true,
      messageId:outcome.messageId,
      objectKey:outcome.objectKey,
      generation:1,leaseExpiresAt:outcome.leaseExpiresAt
    });
  };
}
module.exports={createStagedR2ReserveHandler,validReserveBody};
