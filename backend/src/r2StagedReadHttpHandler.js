'use strict';

/**
 * PessoasAqui / R2 Stage 30O — authenticated streaming HTTP handler.
 * PREPARE ONLY: not registered in server.js or any production router.
 *
 * This function must only be wired to an authenticated, tightly bounded
 * backend POST route after migration, R2 runtime and rollout approval.
 * Never permit Android to submit a prior admission or decrypted payload.
 */
const {admitAuthenticatedR2Read}=require('./r2ReadAdmission');
const {validateOpaqueObjectKey}=require('./r2CiphertextAdapter');
const ID=/^[A-F0-9]{16}$/;
const MAX_MESSAGE=256;
const REQUIRED=['identity','peerIdentity','messageId','objectKey','proof'];

function validBody(body){
  if(!body||typeof body!=='object'||Array.isArray(body)||
    Object.keys(body).length!==REQUIRED.length||
    !REQUIRED.every(x=>Object.prototype.hasOwnProperty.call(body,x)))
    return false;
  if(typeof body.identity!=='string'||!ID.test(body.identity)||
    typeof body.peerIdentity!=='string'||!ID.test(body.peerIdentity)||
    body.identity===body.peerIdentity||
    typeof body.messageId!=='string'||body.messageId.length<1||
    body.messageId.length>MAX_MESSAGE||
    /[\u0000-\u001f\u007f]/.test(body.messageId))
    return false;
  try{validateOpaqueObjectKey(body.objectKey);}catch(_){return false;}
  const p=body.proof;
  if(!p||typeof p!=='object'||Array.isArray(p)||
    Object.keys(p).length!==5||
    !['v','timestamp','nonce','publicKey','signature'].every(k=>
      Object.prototype.hasOwnProperty.call(p,k))||
    p.v!==1||!Number.isSafeInteger(p.timestamp)||
    typeof p.nonce!=='string'||p.nonce.length>80||
    typeof p.publicKey!=='string'||p.publicKey.length>512||
    typeof p.signature!=='string'||p.signature.length>512)
    return false;
  return true;
}
function deny(res,statusCode,code){
  return res.status(statusCode).json({success:false,code});
}
function createStagedR2ReadHandler({
  withTransaction,runtime,openCiphertext,
  admitRead=admitAuthenticatedR2Read
}={}){
  if(typeof withTransaction!=='function'||typeof admitRead!=='function'||
    !runtime||typeof runtime.stream!=='function'||
    typeof openCiphertext!=='function')
    throw new TypeError('Trusted R2 read handler dependencies required');
  return async function stagedR2ReadHandler(req,res){
    if(req?.method!=='POST')
      return deny(res,405,'R2_METHOD_NOT_ALLOWED');
    if(!validBody(req.body))
      return deny(res,400,'INVALID_R2_READ_REQUEST');
    // Listener must be ready before admitting/charging a read. Rechecked
    // by the gateway after the second authoritative transaction.
    if(runtime.ready!==true)
      return deny(res,503,'R2_REVOCATION_LISTENER_NOT_READY');

    let admission;
    try{
      admission=await admitRead({
        withTransaction,
        identity:req.body.identity,peerIdentity:req.body.peerIdentity,
        messageId:req.body.messageId,objectKey:req.body.objectKey,
        proof:req.body.proof
      });
    }catch(_){
      return deny(res,503,'R2_READ_ADMISSION_UNAVAILABLE');
    }
    if(admission?.authorized!==true||admission?.admitted!==true||
      admission?.value?.allowed!==true){
      const status=Number.isSafeInteger(admission?.statusCode)&&
        admission.statusCode>=400&&admission.statusCode<=499?
        admission.statusCode:403;
      const code=typeof admission?.code==='string'&&
        /^R2_[A-Z0-9_]{1,80}$/.test(admission.code)?
        admission.code:'R2_READ_NOT_AUTHORIZED';
      return deny(res,status,code);
    }

    // Revalidate that the trusted admission has exactly the requested
    // metadata. This is not a substitute for the gateway's fresh PG check.
    const v=admission.value;
    if(v.authenticatedIdentity!==req.body.identity||
      v.peerIdentity!==req.body.peerIdentity||
      v.messageId!==req.body.messageId||v.objectKey!==req.body.objectKey)
      return deny(res,503,'R2_READ_ADMISSION_MISMATCH');
    if(runtime.ready!==true)
      return deny(res,503,'R2_REVOCATION_LISTENER_NOT_READY');

    res.setHeader('Content-Type','application/octet-stream');
    res.setHeader('Cache-Control','no-store, private');
    res.setHeader('X-Content-Type-Options','nosniff');
    try{
      const outcome=await runtime.stream({
        admission,openCiphertext,response:res
      });
      if(outcome?.sent===true)return;
      if(res.headersSent){
        res.destroy();
        return;
      }
      const status=Number.isSafeInteger(outcome?.statusCode)&&
        outcome.statusCode>=400&&outcome.statusCode<=599?
        outcome.statusCode:503;
      return deny(res,status,'R2_STREAM_DENIED');
    }catch(_){
      if(res.headersSent){
        res.destroy();
        return;
      }
      return deny(res,503,'R2_STREAM_UNAVAILABLE');
    }
  };
}

module.exports={createStagedR2ReadHandler};
