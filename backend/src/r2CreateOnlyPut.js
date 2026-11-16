'use strict';

/**
 * PessoasAqui / R2 — create-only presigned PUT contract (STAGED).
 *
 * Fail closed if a future SigV4 presigner omits the signed If-None-Match
 * header. Do NOT expose this helper directly to HTTP clients.
 * Authentication, bilateral approval, DB reservation, lease and quotas
 * must be completed by a server-side trusted caller before signing.
 */
const { validateOpaqueObjectKey } = require('./r2CiphertextAdapter');

const MAX_PRESIGNED_PUT_SECONDS = 15 * 60;

function validBucket(bucket) {
  return typeof bucket === 'string' &&
    /^[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$/.test(bucket);
}

/**
 * The URL is a bearer capability and must never be logged, cached, or sent
 * to any identity other than the authorized sender.
 *
 * Require IF-NONE-MATCH and CONTENT-TYPE in X-Amz-SignedHeaders so malicious
 * Android clients cannot drop or modify the create-only precondition.
 * The eventual AWS SDK presigner must explicitly keep both headers signed.
 */
function validatePresignedCreateOnlyPut({ url, bucket, objectKey }) {
  if (typeof url !== 'string' || url.length > 8192 ||
      !validBucket(bucket)) throw new TypeError('Invalid R2 presigned PUT');
  validateOpaqueObjectKey(objectKey);
  let parsed;
  try { parsed = new URL(url); }
  catch (_) { throw new TypeError('Invalid R2 presigned PUT'); }
  if (parsed.protocol !== 'https:' || parsed.username || parsed.password ||
      parsed.port || parsed.hash ||
      !/^[a-f0-9]{32}\.r2\.cloudflarestorage\.com$/.test(parsed.hostname) ||
      parsed.pathname !== '/' + bucket + '/' + objectKey) {
    throw new TypeError('Invalid R2 presigned destination');
  }
  const q = parsed.searchParams;
  const unique = ['X-Amz-Algorithm','X-Amz-Expires','X-Amz-SignedHeaders',
    'X-Amz-Signature','X-Amz-Credential','X-Amz-Date'];
  if (unique.some(name => q.getAll(name).length !== 1) ||
      q.get('X-Amz-Algorithm') !== 'AWS4-HMAC-SHA256' ||
      !/^[0-9a-f]{64}$/i.test(q.get('X-Amz-Signature') || '')) {
    throw new TypeError('Invalid R2 presigned signature');
  }
  const expiresValue = q.get('X-Amz-Expires');
  if (!/^[1-9][0-9]{0,3}$/.test(expiresValue || '')) {
    throw new TypeError('Invalid R2 presigned expiry');
  }
  const expiresIn = Number(expiresValue);
  if (expiresIn > MAX_PRESIGNED_PUT_SECONDS) {
    throw new TypeError('R2 presigned expiry exceeds 15 minutes');
  }
  const signed = (q.get('X-Amz-SignedHeaders') || '').split(';');
  if (new Set(signed).size !== signed.length ||
      !['host','if-none-match','content-type'].every(h => signed.includes(h))) {
    throw new TypeError('R2 create-only precondition must be signed');
  }
  // Header name/value are fixed by the server, not taken from Android.
  return Object.freeze({
    method: 'PUT',
    url,
    expiresIn,
    requiredHeaders: Object.freeze({
      'If-None-Match': '*',
      'Content-Type': 'application/octet-stream'
    })
  });
}

/**
 * The injected signer must build a SigV4 PUT URL for the reserved opaque key
 * with signed headers If-None-Match:* and Content-Type:octet-stream.
 * This function performs no R2 I/O, no database read, and no signing itself.
 */
async function issueCreateOnlyPutCapability({
  signPutUrl, bucket, objectKey, authorizedReservation
}) {
  if (typeof signPutUrl !== 'function' || !authorizedReservation ||
      authorizedReservation.reserved !== true ||
      authorizedReservation.generation !== 1 ||
      authorizedReservation.objectKey !== objectKey ||
      authorizedReservation.bucket !== bucket) {
    throw new TypeError('R2 signed PUT requires server-verified reservation');
  }
  const url = await signPutUrl(Object.freeze({
    method: 'PUT', bucket, objectKey,
    expiresIn: MAX_PRESIGNED_PUT_SECONDS,
    headers: Object.freeze({
      'If-None-Match': '*',
      'Content-Type': 'application/octet-stream'
    })
  }));
  return validatePresignedCreateOnlyPut({url,bucket,objectKey});
}

module.exports = {
  MAX_PRESIGNED_PUT_SECONDS,
  validatePresignedCreateOnlyPut,
  issueCreateOnlyPutCapability
};
