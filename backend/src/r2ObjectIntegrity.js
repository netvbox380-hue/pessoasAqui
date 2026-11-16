'use strict';

/**
 * STAGED ONLY. Independently verifies REAL R2 ciphertext bytes via a bounded
 * GET stream, rather than trusting Android, ETag, HEAD or S3 metadata.
 *
 * Caller must already have an authenticated and authorized DB-bound request.
 * This module does not perform SQL, sign URLs, authorize object ownership, or
 * expose plaintext. Do not invoke from untrusted/unbounded HTTP routes.
 */
const crypto = require('node:crypto');
const { fetchCiphertext, DEFAULT_MAX_CIPHERTEXT_BYTES } = require('./r2CiphertextAdapter');

const DEFAULT_READ_TIMEOUT_MS = 30_000;

class R2IntegrityError extends Error {
  constructor(code) {
    super(code);
    this.name = 'R2IntegrityError';
    this.code = code;
  }
}

function validateExpectedDescriptor(expectedSha256, expectedBytes, maxBytes) {
  if (typeof expectedSha256 !== 'string' ||
      !/^[a-f0-9]{64}$/.test(expectedSha256) ||
      !Number.isSafeInteger(expectedBytes) || expectedBytes < 1 ||
      !Number.isSafeInteger(maxBytes) || maxBytes < 1 ||
      maxBytes > DEFAULT_MAX_CIPHERTEXT_BYTES || expectedBytes > maxBytes) {
    throw new TypeError('Invalid R2 integrity descriptor');
  }
}

/**
 * A successful result confirms existence, length and SHA-256 of the bytes
 * retrieved on THIS GET only. A later overwrite or deletion is not ruled
 * out; immutable per-attempt keys, no overwrite credentials and post-commit
 * controls are mandatory before activation.
 *
 * Network I/O must run OUTSIDE of the nonce-consuming DB transaction.
 * The later DB mutation must re-check ownership, state, generation and lease.
 */
async function verifyStoredCiphertext({
  client, GetObjectCommand, bucket, objectKey,
  expectedSha256, expectedBytes,
  maxBytes = DEFAULT_MAX_CIPHERTEXT_BYTES,
  timeoutMs = DEFAULT_READ_TIMEOUT_MS
}) {
  validateExpectedDescriptor(expectedSha256, expectedBytes, maxBytes);
  if (!Number.isSafeInteger(timeoutMs) || timeoutMs < 100 || timeoutMs > 120_000) {
    throw new TypeError('Invalid R2 verification timeout');
  }
  let body;
  let timeout;
  let timedOut = false;
  try {
    const object = await fetchCiphertext({
      client, GetObjectCommand, bucket, objectKey, maxBytes
    });
    body = object.body;
    if (!body || typeof body[Symbol.asyncIterator] !== 'function' ||
        typeof body.destroy !== 'function') {
      throw new R2IntegrityError('R2_STREAM_UNSUPPORTED');
    }
    if (object.contentLength !== expectedBytes) {
      throw new R2IntegrityError('R2_SIZE_MISMATCH');
    }
    const digest = crypto.createHash('sha256');
    let actualBytes = 0;
    timeout = setTimeout(() => {
      timedOut = true;
      body.destroy(new R2IntegrityError('R2_READ_TIMEOUT'));
    }, timeoutMs);
    timeout.unref?.();
    for await (const chunk of body) {
      if (!(Buffer.isBuffer(chunk) || chunk instanceof Uint8Array)) {
        throw new R2IntegrityError('R2_INVALID_STREAM_CHUNK');
      }
      actualBytes += chunk.byteLength;
      if (actualBytes > expectedBytes || actualBytes > maxBytes) {
        throw new R2IntegrityError('R2_SIZE_MISMATCH');
      }
      digest.update(chunk);
    }
    if (timedOut) throw new R2IntegrityError('R2_READ_TIMEOUT');
    if (actualBytes !== expectedBytes) {
      throw new R2IntegrityError('R2_SIZE_MISMATCH');
    }
    const computed = digest.digest();
    const expected = Buffer.from(expectedSha256, 'hex');
    if (!crypto.timingSafeEqual(computed, expected)) {
      throw new R2IntegrityError('R2_HASH_MISMATCH');
    }
    return Object.freeze({ verified: true, bytes: actualBytes,
      sha256: computed.toString('hex'), objectKey });
  } catch (error) {
    if (timedOut && error.code !== 'R2_READ_TIMEOUT') {
      throw new R2IntegrityError('R2_READ_TIMEOUT');
    }
    throw error;
  } finally {
    if (timeout) clearTimeout(timeout);
    if (body && !body.destroyed && typeof body.destroy === 'function') {
      body.destroy();
    }
  }
}

module.exports = { verifyStoredCiphertext, R2IntegrityError,
  DEFAULT_READ_TIMEOUT_MS, validateExpectedDescriptor };
