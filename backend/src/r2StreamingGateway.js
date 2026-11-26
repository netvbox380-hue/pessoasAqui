'use strict';

/**
 * PessoasAqui / R2 Stage 27 — inert authenticated ciphertext gateway.
 *
 * NEVER expose directly as a route. This function accepts only an admission
 * returned server-side by admitAuthenticatedR2Read(), never Android JSON.
 * All I/O is dependency injected. The listener must be running and ready.
 *
 * Pipeline: require prior signed admission -> register session ->
 * fresh PostgreSQL preflight -> verify still active/ready -> fetch ciphertext
 * through the server (no signed GET URL) -> bounded async stream to HTTP.
 * Data remains E2EE ciphertext. SHA-256 can only be checked at EOF on this
 * streaming path. Android MUST discard partial/unverified downloads and
 * acknowledge DELIVERED only after local verification and persistence.
 *
 * NO server.js hook, R2 SDK, network connection, background daemon or GC.
 */
const crypto=require('node:crypto');
const {once}=require('node:events');
const {revalidateR2ReadAtStreamStart}=require('./r2StreamStartGate');
const MAX_BYTES=25*1024*1024;
const DEFAULT_TIMEOUT_MS=120000;

function validAdmission(a){
  const v=a?.value;
  return a?.authorized===true && a?.admitted===true &&
    v?.allowed===true &&
    /^[A-F0-9]{16}$/.test(v.authenticatedIdentity||'') &&
    /^[A-F0-9]{16}$/.test(v.peerIdentity||'') &&
    v.authenticatedIdentity!==v.peerIdentity &&
    typeof v.messageId==='string' && v.messageId.length>0 &&
    v.messageId.length<=256 && !/[\u0000-\u001f\u007f]/.test(v.messageId) &&
    typeof v.objectKey==='string' &&
    /^[a-f0-9]{64}$/.test(v.expectedSha256||'') &&
    Number.isSafeInteger(v.expectedBytes) && v.expectedBytes>=1 &&
    v.expectedBytes<=MAX_BYTES;
}
function denied(code,statusCode=503){return Object.freeze({sent:false,code,statusCode});}
async function streamAuthenticatedR2Ciphertext({
  admission,registry,listener,withTransaction,openCiphertext,response,
  timeoutMs=DEFAULT_TIMEOUT_MS
}){
  if(!registry||typeof registry.register!=='function'||
      !listener||typeof withTransaction!=='function'||
      typeof openCiphertext!=='function'||
      !response||typeof response.write!=='function'||
      typeof response.end!=='function'||typeof response.destroy!=='function'||
      !Number.isSafeInteger(timeoutMs)||timeoutMs<1000||timeoutMs>120000){
    throw new TypeError('Trusted R2 gateway dependencies required');
  }
  if(!validAdmission(admission))return denied('R2_PRIOR_ADMISSION_REQUIRED',401);
  if(!listener.ready)return denied('R2_REVOCATION_LISTENER_NOT_READY');
  const v=admission.value;
  const abortController=new AbortController();
  let handle,body,timer,monitor,timedOut=false,sentBytes=0;
  timer=setTimeout(()=>{
    timedOut=true;
    abortController.abort('R2_STREAM_DEADLINE');
  },timeoutMs);
  timer.unref?.();
  try{
    // Register BEFORE the final DB check; concurrent revoke can cancel it.
    handle=registry.register({
      reference:{
        senderIdentity:v.peerIdentity,recipientIdentity:v.authenticatedIdentity,
        messageId:v.messageId,objectKey:v.objectKey,
        expectedSha256:v.expectedSha256,expectedBytes:v.expectedBytes,
        ...(v.winnerMode===true?{winnerMode:true,identityKey:v.identityKey}:{})
      },
      abortController,response
    });
    const current=await revalidateR2ReadAtStreamStart({
      withTransaction,admission
    });
    if(current.allowed!==true){
      handle.cancel();
      return denied(current.code,current.statusCode);
    }
    if(!handle.active||abortController.signal.aborted||!listener.ready){
      handle.cancel();
      return denied('R2_STREAM_REVOKED_BEFORE_START');
    }
    // Winner-only ongoing checks: bounded periodic revalidation even if
    // identity rotation or expiry has no corresponding outbox event.
    // Never overlap checks; errors cancel the transfer.
    if(v.winnerMode===true){
      const check=async()=>{
        try{
          if(!handle.active)return;
          const fresh=await revalidateR2ReadAtStreamStart({withTransaction,admission});
          if(!fresh.allowed||!listener.ready){handle.cancel();return;}
          if(handle.active){monitor=setTimeout(check,2000);monitor.unref?.();}
        }catch(_){handle.cancel();}
      };
      monitor=setTimeout(check,2000);monitor.unref?.();
    }
    // The injected adapter must accept signal and return {body,contentLength}.
    // It MUST support abort on SDK send and destroy late-arriving bodies.
    const opened=await openCiphertext({
      objectKey:current.objectKey,expectedBytes:current.expectedBytes,
      abortSignal:abortController.signal
    });
    body=opened?.body;
    if(!body||typeof body[Symbol.asyncIterator]!=='function'||
      typeof body.destroy!=='function'||
      opened.contentLength!==v.expectedBytes){
      body?.destroy?.();
      handle.cancel();
      return denied('R2_CIPHERTEXT_DESCRIPTOR_MISMATCH',409);
    }
    if(!handle.active||abortController.signal.aborted||!listener.ready){
      handle.cancel();
      return denied('R2_STREAM_REVOKED_BEFORE_START');
    }

    const digest=crypto.createHash('sha256');
    for await(const chunk of body){
      if(!handle.active||abortController.signal.aborted||!listener.ready)
        throw Error('R2_STREAM_REVOKED');
      if(!Buffer.isBuffer(chunk)&&!(chunk instanceof Uint8Array))
        throw Error('R2_INVALID_STREAM_CHUNK');
      sentBytes+=chunk.byteLength;
      if(sentBytes>v.expectedBytes||sentBytes>MAX_BYTES)
        throw Error('R2_STREAM_SIZE_EXCEEDED');
      digest.update(chunk);
      // No awaits between final revocation check and synchronous write.
      if(!handle.active||!listener.ready||abortController.signal.aborted)
        throw Error('R2_STREAM_REVOKED');
      if(!response.write(chunk)){
        // Await drain with abort support; cancellation destroys response.
        await once(response,'drain',{signal:abortController.signal});
      }
    }
    if(!handle.active||abortController.signal.aborted||!listener.ready)
      throw Error('R2_STREAM_REVOKED');
    if(sentBytes!==v.expectedBytes||
       digest.digest('hex')!==v.expectedSha256)
      throw Error('R2_CIPHERTEXT_INTEGRITY_FAILED');
    if(v.winnerMode===true){
      const final=await revalidateR2ReadAtStreamStart({withTransaction,admission});
      if(!final.allowed||!handle.active||abortController.signal.aborted||!listener.ready)
        throw Error('R2_STREAM_REVOKED');
    }
    response.end();
    return Object.freeze({sent:true,bytes:sentBytes});
  }catch(error){
    handle?.cancel();
    if(timedOut)return denied('R2_STREAM_TIMEOUT',504);
    return denied('R2_STREAM_ABORTED_OR_INTEGRITY_FAILED');
  }finally{
    clearTimeout(timer);
    clearTimeout(monitor);
    body?.destroy?.();
    handle?.close();
  }
}

module.exports={streamAuthenticatedR2Ciphertext,DEFAULT_TIMEOUT_MS};
