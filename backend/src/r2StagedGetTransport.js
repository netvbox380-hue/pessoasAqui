'use strict';

/**
 * PessoasAqui R2 Stage 30P — staged GET transport to streaming gateway.
 * No server.js import, SDK constructor, credentials or network on import.
 * Backend must inject its trusted AWS SDK v3 client with approved R2 endpoint.
 *
 * This bridge is strictly transport-only. It neither authorizes a recipient
 * nor accepts an HTTP-request-supplied SDK client. Invoke only after signed
 * R2_READ admission and a fresh database stream-start validation by gateway.
 */
const {fetchCiphertext,validateOpaqueObjectKey}=
  require('./r2CiphertextAdapter');

const MAX_BYTES=25*1024*1024;
function validBucket(v){
  return typeof v==='string'&&
    /^[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$/.test(v);
}
function aborted(){
  const e=new Error('R2_GET_ABORTED');
  e.code='R2_GET_ABORTED';
  return e;
}
function createStagedR2GetTransport({client,GetObjectCommand,bucket}={}){
  if(!client||typeof client.send!=='function'||
      typeof GetObjectCommand!=='function'||!validBucket(bucket))
    throw new TypeError('Trusted R2 GET transport dependencies required');
  return async function openCiphertext({objectKey,expectedBytes,abortSignal}={}){
    validateOpaqueObjectKey(objectKey);
    if(!Number.isSafeInteger(expectedBytes)||expectedBytes<1||
        expectedBytes>MAX_BYTES)
      throw new TypeError('Invalid trusted R2 expectedBytes');
    if(!abortSignal||typeof abortSignal.aborted!=='boolean'||
        typeof abortSignal.addEventListener!=='function'||
        typeof abortSignal.removeEventListener!=='function')
      throw new TypeError('Trusted AbortSignal required');
    if(abortSignal.aborted)throw aborted();

    // fetchCiphertext validates the S3 length before returning the stream;
    // the gateway additionally checks exact length + SHA256 while streaming.
    const result=await fetchCiphertext({
      client,GetObjectCommand,bucket,objectKey,
      maxBytes:expectedBytes,abortSignal
    });
    const body=result?.body;
    if(!body||typeof body[Symbol.asyncIterator]!=='function'||
        typeof body.destroy!=='function'||
        typeof body.once!=='function'||
        typeof body.off!=='function'){
      body?.destroy?.();
      throw new TypeError('R2 GET must return a cancellable Node.js stream');
    }
    if(result.contentLength!==expectedBytes){
      body.destroy();
      throw new RangeError('R2 GET descriptor length mismatch');
    }
    if(abortSignal.aborted){body.destroy();throw aborted();}

    // Keep cancellation wired through the ENTIRE download, not only the
    // initial SDK request. All listeners detach when stream ends/closes.
    let attached=true;
    // Destroy without an error argument: a stream cancelled before the
    // gateway starts async iteration may have no 'error' listener yet.
    // The shared abortSignal is authoritative for cancellation status.
    const cancel=()=>body.destroy();
    const detach=()=>{
      if(!attached)return;
      attached=false;
      abortSignal.removeEventListener('abort',cancel);
      body.off('close',detach);
      body.off('end',detach);
      body.off('error',detach);
    };
    body.once('close',detach);
    body.once('end',detach);
    body.once('error',detach);
    abortSignal.addEventListener('abort',cancel,{once:true});
    if(abortSignal.aborted)cancel();
    return Object.freeze({body,contentLength:result.contentLength});
  };
}

module.exports={createStagedR2GetTransport,MAX_BYTES};
