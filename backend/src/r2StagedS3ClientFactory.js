'use strict';

/**
 * PessoasAqui R2 — Stage 30I (PREPARATORY / NOT ACTIVATED).
 * Builds a tightly bounded AWS SDK v3 S3Client for the trusted backend ONLY.
 *
 * No top-level AWS imports, environment reads, initialization or network I/O.
 * The SDK classes and secrets must be explicitly supplied by backend code;
 * NEVER accept settings from an HTTP/Android payload.
 *
 * This factory is not linked from server.js, never starts GC, and performs no
 * R2 operations until an explicitly invoked backend service calls the adapter.
 */
const https=require('node:https');
const {createStagedR2DeleteAdapter}=require('./r2StagedDeleteAdapter');

const CONNECTION_TIMEOUT_MS=5000;
const REQUEST_TIMEOUT_MS=10000;
const MAX_SOCKETS=4;

function nonemptySecret(v){
  return typeof v==='string'&&v.length>=8&&v.length<=512&&
    !/[\u0000-\u001f\u007f]/.test(v);
}
function exactAccountId(v){
  return typeof v==='string'&&/^[a-f0-9]{32}$/.test(v);
}
function exactBucket(v){
  return typeof v==='string'&&
    /^[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$/.test(v);
}

/**
 * A future controlled rollout may inject:
 *   S3Client, DeleteObjectCommand from @aws-sdk/client-s3
 *   NodeHttpHandler from @smithy/node-http-handler
 *   accountId, bucket, accessKeyId, secretAccessKey from trusted server config
 *
 * Fail closed on absent/malformed configuration. Do not support alternate
 * endpoints, path-style override, custom TLS, or credential fallback.
 */
function createStagedR2S3DeleteClient({
  S3Client,DeleteObjectCommand,NodeHttpHandler,
  accountId,bucket,accessKeyId,secretAccessKey
}={}){
  if(typeof S3Client!=='function'||
     typeof DeleteObjectCommand!=='function'||
     typeof NodeHttpHandler!=='function')
    throw new TypeError('Trusted AWS SDK v3 constructors required');
  if(!exactAccountId(accountId)||!exactBucket(bucket)||
     !nonemptySecret(accessKeyId)||!nonemptySecret(secretAccessKey))
    throw new TypeError('Invalid trusted Cloudflare R2 configuration');

  const endpoint=`https://${accountId}.r2.cloudflarestorage.com`;
  const agent=new https.Agent({
    keepAlive:true,maxSockets:MAX_SOCKETS,
    rejectUnauthorized:true
  });
  let client;
  try{
    const handler=new NodeHttpHandler({
      connectionTimeout:CONNECTION_TIMEOUT_MS,
      requestTimeout:REQUEST_TIMEOUT_MS,
      httpsAgent:agent
    });
    client=new S3Client({
      endpoint,region:'auto',forcePathStyle:true,maxAttempts:1,
      credentials:{accessKeyId,secretAccessKey},
      requestHandler:handler
    });
    const deleteObject=createStagedR2DeleteAdapter({
      client,DeleteObjectCommand,bucket
    });
    return Object.freeze({
      deleteObject,
      // Only call during shutdown of a future explicitly authorized service.
      close:()=>{try{client.destroy?.();}finally{agent.destroy();}}
    });
  }catch(error){
    try{client?.destroy?.();}catch(_){}
    agent.destroy();
    // Never copy SDK error messages which may embed credentials or endpoint.
    throw new Error('R2_STAGED_SDK_CONFIGURATION_FAILED');
  }
}

module.exports={
  createStagedR2S3DeleteClient,
  CONNECTION_TIMEOUT_MS,REQUEST_TIMEOUT_MS,MAX_SOCKETS
};
