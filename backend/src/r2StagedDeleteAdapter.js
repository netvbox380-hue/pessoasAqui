'use strict';

/**
 * PessoasAqui — R2 Stage 30H: verified S3 DeleteObject adapter.
 * PREPARE ONLY. No cloud SDK import, credentials, endpoints, worker or start.
 *
 * Trusted backend supplies an AWS SDK v3 S3Client and DeleteObjectCommand.
 * The client must be configured separately with an HTTPS Cloudflare R2
 * account endpoint, path-style bucket policy, fixed credentials, bounded
 * SDK socket/request/connection timeouts and retries DISABLED / bounded.
 *
 * A future production composition MUST own that configuration; this module
 * does not instantiate an S3Client and must not be exposed via HTTP.
 */
const {validateOpaqueObjectKey}=require('./r2CiphertextAdapter');

function requireBucket(v){
  if(typeof v!=='string'||
    !/^[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$/.test(v))
    throw new TypeError('Invalid trusted R2 bucket');
  return v;
}
function abortError(){
  const error=new Error('R2_DELETE_ABORTED');
  error.code='R2_DELETE_ABORTED';
  return error;
}

/**
 * Factory returns a callback compatible with processStagedR2GcBatch:
 * ({objectKey,signal}) => Promise<{deleted:true,objectKey}>.
 *
 * This operation has no independent claim checks. ONLY use from a trusted
 * processStagedR2GcBatch invocation after persisted claim admission.
 * The output is never built from Android-controlled metadata.
 */
function createStagedR2DeleteAdapter({client,DeleteObjectCommand,bucket}={}){
  if(!client||typeof client.send!=='function'||
    typeof DeleteObjectCommand!=='function')
    throw new TypeError('Trusted R2 S3Client and DeleteObjectCommand required');
  requireBucket(bucket);
  return async function deleteObject({objectKey,signal}={}){
    validateOpaqueObjectKey(objectKey);
    if(!signal||typeof signal.aborted!=='boolean'||
      typeof signal.addEventListener!=='function')
      throw new TypeError('An AbortSignal is required');
    if(signal.aborted)throw abortError();
    const command=new DeleteObjectCommand({
      Bucket:bucket,Key:objectKey
    });
    const result=await client.send(command,{abortSignal:signal});
    // A nonconforming mock, transparent proxy, or custom SDK may ignore abort.
    // Never mark DB delete complete after cancellation.
    if(signal.aborted)throw abortError();
    // The R2 S3 API normally returns 204 for a successful DeleteObject,
    // including an already-absent key. Do not interpret undefined or 5xx
    // results as success. Exact account/endpoint remains a rollout concern.
    if(result?.$metadata?.httpStatusCode!==204){
      const error=new Error('R2_DELETE_UNVERIFIED_RESPONSE');
      error.code='R2_DELETE_UNVERIFIED_RESPONSE';
      throw error;
    }
    return Object.freeze({deleted:true,objectKey});
  };
}

module.exports={createStagedR2DeleteAdapter};
