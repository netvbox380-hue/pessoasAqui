'use strict';

/**
 * PessoasAqui / Cloudflare R2 — INACTIVE staged deletion outbox worker.
 *
 * No production imports, timers, AWS SDK, credentials or network connections.
 * This module is activated ONLY after the schema/worker and media delivery
 * handshake are approved and tested together.
 *
 * Atomic DB claim → DeleteObject → conditional acknowledgement.
 * Crash after DeleteObject but before DB ack is safe because S3 DeleteObject
 * is idempotent. A lease makes claims recoverable if a worker crashes.
 *
 * IMPORTANT: Only a committed receipt of DELIVERED, EXPIRED or CANCELLED
 * may enqueue an object for deletion; callers must not enqueue PENDING media.
 */
const DEFAULT_LEASE_SECONDS = 120;
const MAX_BATCH_SIZE = 50;
const MAX_RETRY_SECONDS = 3600;
const BASE_RETRY_SECONDS = 5;

function assertQuery(query) {
  if (typeof query !== 'function') throw new TypeError('database query function required');
}

function validateBatchSize(batchSize) {
  if (!Number.isSafeInteger(batchSize) || batchSize < 1 || batchSize > MAX_BATCH_SIZE) {
    throw new RangeError('Invalid outbox batch size');
  }
}

function retrySeconds(attempts) {
  if (!Number.isSafeInteger(attempts) || attempts < 1) throw new RangeError('Invalid attempt');
  return Math.min(MAX_RETRY_SECONDS, BASE_RETRY_SECONDS * Math.pow(2, Math.min(attempts - 1, 10)));
}

function sanitizedErrorCode(error) {
  // NEVER store the raw error string; R2 errors might contain URLs or tokens.
  const code = typeof error?.name === 'string' ? error.name : '';
  return /^[A-Za-z][A-Za-z0-9_]{0,63}$/.test(code) ? code : 'R2_OPERATION_FAILED';
}

/**
 * One SQL statement claims due work using SKIP LOCKED. next_attempt_at is
 * the durable lease deadline; crashed workers can be reclaimed after it.
 * attempts is a fencing token for the claim/complete/retry operations.
 *
 * A true R2 integration must ensure all object keys belong to the private
 * bucket and were created by r2CiphertextAdapter.
 */
async function claimDeletionBatch({ query, batchSize = 20, leaseSeconds = DEFAULT_LEASE_SECONDS }) {
  assertQuery(query);
  validateBatchSize(batchSize);
  if (!Number.isSafeInteger(leaseSeconds) || leaseSeconds < 30 || leaseSeconds > 3600) {
    throw new RangeError('Invalid outbox lease');
  }
  const result = await query(
    `WITH claimed AS (
       SELECT object_key
         FROM public.message_r2_delete_outbox
        WHERE state IN ('PENDING','RETRY')
          AND next_attempt_at <= clock_timestamp()
        ORDER BY next_attempt_at, object_key
        FOR UPDATE SKIP LOCKED
        LIMIT $1
     )
     UPDATE public.message_r2_delete_outbox AS o
        SET attempts = attempts + 1,
            next_attempt_at = clock_timestamp() + ($2::integer * interval '1 second')
       FROM claimed
      WHERE o.object_key = claimed.object_key
     RETURNING o.object_key, o.message_id, o.attempts`,
    [batchSize, leaseSeconds]
  );
  return result.rows;
}

/**
 * Complete only the attempt that owns the latest lease. No retries of DONE.
 */
async function markDeletionDone({ query, objectKey, attempts }) {
  assertQuery(query);
  const result = await query(
    `UPDATE public.message_r2_delete_outbox
        SET state = 'DONE', completed_at = clock_timestamp(),
            last_error_code = NULL
      WHERE object_key=$1 AND attempts=$2
        AND state IN ('PENDING','RETRY')
      RETURNING object_key`,
    [objectKey, attempts]
  );
  return result.rowCount === 1;
}

async function rescheduleDeletion({ query, objectKey, attempts, error }) {
  assertQuery(query);
  const seconds = retrySeconds(attempts);
  const result = await query(
    `UPDATE public.message_r2_delete_outbox
        SET state='RETRY',
            next_attempt_at=clock_timestamp() + ($3::integer * interval '1 second'),
            last_error_code=$4
      WHERE object_key=$1 AND attempts=$2
        AND state IN ('PENDING','RETRY')
      RETURNING object_key`,
    [objectKey, attempts, seconds, sanitizedErrorCode(error)]
  );
  return result.rowCount === 1;
}

/**
 * One bounded worker pass. Inject a deletion function that uses exactly the
 * bucket's configured S3 endpoint; it must propagate failures without logging.
 * A thrown deletion error reschedules the individual job, never acknowledges
 * its completion. Database errors propagate and abort the pass (fail closed).
 */
async function processDeletionBatch({ query, deleteObject, batchSize = 20, leaseSeconds }) {
  assertQuery(query);
  if (typeof deleteObject !== 'function') throw new TypeError('deleteObject required');
  const jobs = await claimDeletionBatch({ query, batchSize, leaseSeconds });
  const result = { claimed: jobs.length, done: 0, retry: 0, stale: 0 };
  for (const job of jobs) {
    try {
      // Network call only after the durable claim. Never log object identifiers.
      await deleteObject(job.object_key);
    } catch (error) {
      if (await rescheduleDeletion({
        query, objectKey: job.object_key, attempts: job.attempts, error
      })) {
        result.retry += 1;
      } else {
        result.stale += 1;
      }
      continue;
    }
    if (await markDeletionDone({
      query, objectKey: job.object_key, attempts: job.attempts
    })) {
      result.done += 1;
    } else {
      result.stale += 1;
    }
  }
  return result;
}

module.exports = {
  DEFAULT_LEASE_SECONDS,
  MAX_BATCH_SIZE,
  retrySeconds,
  sanitizedErrorCode,
  claimDeletionBatch,
  markDeletionDone,
  rescheduleDeletion,
  processDeletionBatch
};
