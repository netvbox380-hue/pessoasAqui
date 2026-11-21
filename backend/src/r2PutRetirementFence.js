'use strict';

/**
 * PessoasAqui Etapa 28 — durable create-only PUT capability retirement fence.
 * STAGED ONLY. No server routes, production database, R2 SDK or DeleteObject.
 *
 * SECURITY:
 * - The signing callback must be an in-process SigV4 signer (no network I/O).
 * - The trusted reservation must be validated by the backend first.
 * - Issue the URL and persist its validity window in ONE PostgreSQL
 *   transaction. Never send the URL to Android before COMMIT succeeds.
 * - The SAME object-key ledger is monotonically sealed before enqueueing any
 *   deletion, and may NEVER be removed. A sealed key cannot be signed again.
 * - The deletion gate checks server clock AFTER the latest URL expiry with
 *   a fixed 2-minute safety margin, and refuses unknown/unsealed keys.
 *
 * Existing URLs issued before this ledger is deployed are NOT tracked.
 * They require explicit legacy migration/quarantine; no fallback.
 */
const {issueCreateOnlyPutCapability,MAX_PRESIGNED_PUT_SECONDS} =
  require('./r2CreateOnlyPut');
const {validateOpaqueObjectKey} = require('./r2CiphertextAdapter');
const SAFETY_SECONDS=120;
const SIGNING_CLOCK_SKEW_SECONDS=60;

function parsedExpiry(url) {
  const u=new URL(url);
  const date=u.searchParams.get('X-Amz-Date');
  const duration=Number(u.searchParams.get('X-Amz-Expires'));
  if(!date||!/^[0-9]{8}T[0-9]{6}Z$/.test(date)||
      !Number.isInteger(duration)||duration<1||
      duration>MAX_PRESIGNED_PUT_SECONDS)
    throw new TypeError('Invalid signed PUT issuance time');
  const y=Number(date.slice(0,4)),m=Number(date.slice(4,6)),
    d=Number(date.slice(6,8)),h=Number(date.slice(9,11)),
    min=Number(date.slice(11,13)),sec=Number(date.slice(13,15));
  const issued=Date.UTC(y,m-1,d,h,min,sec);
  const iso=new Date(issued).toISOString().replace(/[-:]/g,'').replace('.000','');
  if(!Number.isFinite(issued)||iso!==date)
    throw new TypeError('Invalid signed PUT date');
  return {issued,expires:issued+duration*1000};
}

async function issuePutWithDurableRetirementFence({
  withTransaction,signPutUrl,bucket,objectKey,authorizedReservation
}){
  if(typeof withTransaction!=='function')
    throw new TypeError('PostgreSQL transaction required');
  validateOpaqueObjectKey(objectKey);
  if(typeof signPutUrl!=='function')throw new TypeError('Local signer required');
  return withTransaction(async tx=>{
    // If the key has already been sealed, never sign another URL for it.
    // The row lock serializes issue against seal.
    const rows=await tx(
      `SELECT sealed_at FROM public.r2_put_capability_windows
        WHERE object_key=$1 FOR UPDATE`,[objectKey]
    );
    if(rows.rowCount>1||rows.rows[0]?.sealed_at)
      throw Error('R2_PUT_KEY_RETIRED');

    const cap=await issueCreateOnlyPutCapability({
      signPutUrl,bucket,objectKey,authorizedReservation
    });
    const time=parsedExpiry(cap.url);
    // Only permit signing timestamps close to the authoritative DB clock.
    const db=await tx('SELECT clock_timestamp() AS now',[]);
    const now=new Date(db.rows[0].now).getTime();
    if(!Number.isFinite(now)||
        Math.abs(time.issued-now)>SIGNING_CLOCK_SKEW_SECONDS*1000)
      throw Error('R2_PUT_SIGNED_TIME_OUT_OF_RANGE');
    const recorded=await tx(
      `INSERT INTO public.r2_put_capability_windows
        (object_key,expires_at)
       VALUES ($1,$2::timestamptz)
       ON CONFLICT (object_key) DO UPDATE
         SET expires_at=GREATEST(
               public.r2_put_capability_windows.expires_at,EXCLUDED.expires_at),
             changed_at=clock_timestamp()
       WHERE public.r2_put_capability_windows.sealed_at IS NULL
       RETURNING object_key`,
      [objectKey,new Date(time.expires).toISOString()]
    );
    if(recorded.rowCount!==1)throw Error('R2_PUT_KEY_RETIRED');
    // The URL can only leave this callback after withTransaction commits.
    return cap;
  });
}

async function sealPutKeyForRetirement({tx,objectKey}){
  if(typeof tx!=='function')throw new TypeError('Transaction callback required');
  validateOpaqueObjectKey(objectKey);
  const sealed=await tx(
    `UPDATE public.r2_put_capability_windows
        SET sealed_at=COALESCE(sealed_at,clock_timestamp()),
            changed_at=clock_timestamp()
      WHERE object_key=$1
      RETURNING sealed_at,expires_at`,[objectKey]
  );
  // Missing ledger => an untracked legacy URL might still exist.
  if(sealed.rowCount!==1)throw Error('R2_PUT_WINDOW_UNKNOWN');
  return Object.freeze({sealed:true});
}

async function authorizePhysicalR2Delete({tx,objectKey}){
  if(typeof tx!=='function')throw new TypeError('Transaction callback required');
  validateOpaqueObjectKey(objectKey);
  const rows=await tx(
    `SELECT object_key
       FROM public.r2_put_capability_windows
      WHERE object_key=$1
        AND sealed_at IS NOT NULL
        AND clock_timestamp() >=
            expires_at + ($2::integer * interval '1 second')
      FOR UPDATE`,
    [objectKey,SAFETY_SECONDS]
  );
  // Retired ledger rows must remain forever for this key; no R2 DELETE yet.
  return Object.freeze({
    allowed:rows.rowCount===1,
    code:rows.rowCount===1?'R2_PUT_RETIREMENT_ELAPSED':'R2_PUT_DELETE_BLOCKED'
  });
}

module.exports={
  SAFETY_SECONDS,SIGNING_CLOCK_SKEW_SECONDS,
  parsedExpiry,issuePutWithDurableRetirementFence,
  sealPutKeyForRetirement,authorizePhysicalR2Delete
};
