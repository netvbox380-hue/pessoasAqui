'use strict';

/**
 * PessoasAqui R2 transport (staged, disconnected).
 *
 * No import in server.js, no external credentials loaded, no network calls
 * unless an authenticated backend caller explicitly injects an S3 client.
 *
 * The ciphertext MUST already be E2EE-encrypted on Android. The backend and
 * this module do not encrypt, decrypt, parse or log the message content.
 * Never place R2 credentials on Android or in frontend bundles.
 *
 * In a future activation the adapter should be passed an AWS SDK v3 S3Client,
 * or compatible client, and command constructors. No SDK dependency is added
 * to the existing deployment in this stage.
 */
const crypto = require('node:crypto');

const KEY_PREFIX = 'pending/';
const DEFAULT_MAX_CIPHERTEXT_BYTES = 25 * 1024 * 1024;
const MIN_CIPHERTEXT_BYTES = 1;

function requireBucket(value) {
  if (typeof value !== 'string' || !/^[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$/.test(value)) {
    throw new TypeError('Invalid R2 bucket');
  }
  return value;
}

function validateOpaqueObjectKey(key) {
  if (typeof key !== 'string' ||
      !/^pending\/[0-9a-f]{2}\/[0-9a-f]{2}\/[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(key)) {
    throw new TypeError('Invalid R2 object key');
  }
  return key;
}

function makeOpaqueObjectKey(randomUUID = crypto.randomUUID) {
  if (typeof randomUUID !== 'function') throw new TypeError('randomUUID required');
  const id = randomUUID();
  if (typeof id !== 'string' || !/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(id)) {
    throw new TypeError('Invalid UUID v4');
  }
  return validateOpaqueObjectKey(`${KEY_PREFIX}${id.slice(0, 2)}/${id.slice(2, 4)}/${id}`);
}

function ensureCiphertext(ciphertext, maxBytes) {
  if (!Buffer.isBuffer(ciphertext) ||
      !Number.isSafeInteger(maxBytes) || maxBytes < MIN_CIPHERTEXT_BYTES ||
      ciphertext.byteLength < MIN_CIPHERTEXT_BYTES ||
      ciphertext.byteLength > maxBytes) {
    throw new TypeError('Invalid encrypted payload size');
  }
  return ciphertext;
}

function sha256Hex(value) {
  return crypto.createHash('sha256').update(value).digest('hex');
}

/**
 * uploadCiphertext NEVER claims a message is queued. That can only occur
 * after the DB transaction referencing this object commits. If DB commit
 * fails, caller MUST enqueue orphan cleanup; R2 lifecycle is secondary.
 */
async function uploadCiphertext({
  client, PutObjectCommand, bucket, ciphertext, maxBytes = DEFAULT_MAX_CIPHERTEXT_BYTES,
  randomUUID
}) {
  if (!client || typeof client.send !== 'function' || typeof PutObjectCommand !== 'function') {
    throw new TypeError('R2 S3 client/command required');
  }
  requireBucket(bucket);
  ensureCiphertext(ciphertext, maxBytes);
  const objectKey = makeOpaqueObjectKey(randomUUID || crypto.randomUUID);
  const sha256 = sha256Hex(ciphertext);
  // Do not add user-controlled metadata such as identity or file name.
  await client.send(new PutObjectCommand({
    Bucket: bucket,
    Key: objectKey,
    Body: ciphertext,
    ContentType: 'application/octet-stream',
    ContentLength: ciphertext.byteLength
  }));
  return Object.freeze({ objectKey, bytes: ciphertext.byteLength, sha256 });
}

/**
 * deleteObject is idempotent in the S3 API, but the durable outbox must
 * retry errors and finalize completion separately.
 */
async function deleteCiphertext({ client, DeleteObjectCommand, bucket, objectKey }) {
  if (!client || typeof client.send !== 'function' || typeof DeleteObjectCommand !== 'function') {
    throw new TypeError('R2 S3 client/command required');
  }
  requireBucket(bucket);
  validateOpaqueObjectKey(objectKey);
  await client.send(new DeleteObjectCommand({ Bucket: bucket, Key: objectKey }));
  return { deleted: true, objectKey };
}

/**
 * Download via backend proxy — the object is opaque ciphertext.
 * The caller must authorize relationship and recipient first, and verify
 * message state and expiry from PostgreSQL immediately before serving.
 * Streaming, bounded response size and cancellation remain prerequisites.
 */
async function fetchCiphertext({
  client, GetObjectCommand, bucket, objectKey, maxBytes = DEFAULT_MAX_CIPHERTEXT_BYTES,
  abortSignal
}) {
  if (!client || typeof client.send !== 'function' || typeof GetObjectCommand !== 'function') {
    throw new TypeError('R2 S3 client/command required');
  }
  requireBucket(bucket);
  validateOpaqueObjectKey(objectKey);
  if (!Number.isSafeInteger(maxBytes) || maxBytes < 1) throw new TypeError('Invalid maxBytes');
  // SDK v3 supports AbortSignal as client.send(command, { abortSignal }).
  // The caller owns the overall request deadline and should also configure
  // SDK connection/socket timeouts before activating R2.
  if (abortSignal?.aborted) throw Object.assign(new Error('R2 GET aborted'), { code: 'R2_GET_ABORTED' });
  const result = await client.send(
    new GetObjectCommand({ Bucket: bucket, Key: objectKey }),
    abortSignal ? { abortSignal } : undefined
  );
  // Some S3 client mocks/adapters may ignore abort: close late-arriving
  // bodies rather than leaking an unread socket after the global timeout.
  if (abortSignal?.aborted) {
    result?.Body?.destroy?.();
    throw Object.assign(new Error('R2 GET aborted'), { code: 'R2_GET_ABORTED' });
  }
  const announced = Number(result && result.ContentLength);
  if (!Number.isSafeInteger(announced) || announced < 1 || announced > maxBytes) {
    // The caller must destroy/cancel the response stream when applicable.
    if (result && result.Body && typeof result.Body.destroy === 'function') result.Body.destroy();
    throw new RangeError('Encrypted object exceeds configured bound');
  }
  // Stream is not consumed here. Caller must verify digest/length while
  // streaming; this adapter does NOT claim receipt/persistence.
  return { body: result.Body, contentLength: announced, objectKey };
}

module.exports = {
  DEFAULT_MAX_CIPHERTEXT_BYTES,
  makeOpaqueObjectKey,
  validateOpaqueObjectKey,
  sha256Hex,
  uploadCiphertext,
  fetchCiphertext,
  deleteCiphertext
};
