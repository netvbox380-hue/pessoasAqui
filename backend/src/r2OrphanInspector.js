'use strict';

/**
 * PessoasAqui / R2 orphan GC phase 1: READ-ONLY candidate inspection.
 * Not imported by server.js; no scheduler, deletion, state transitions
 * or network side effects. NEVER use this as authorization to delete.
 *
 * RESERVED may represent an upload still in-flight even after four hours.
 * UPLOADED may become ATTACHED concurrently. A later GC must obtain DB
 * locks, honor upload leases/fencing, atomically mark an orphan state, and
 * enqueue a dedicated cleanup job. Do NOT call DeleteObject from this module.
 */
const { validateOpaqueObjectKey } = require('./r2CiphertextAdapter');

const DEFAULT_MIN_AGE_HOURS = 4;
const MAX_LIMIT = 100;

function checkArgs(query, limit, minAgeHours) {
  if (typeof query !== 'function') throw new TypeError('query required');
  if (!Number.isSafeInteger(limit) || limit < 1 || limit > MAX_LIMIT) {
    throw new RangeError('Invalid orphan inspection limit');
  }
  if (!Number.isSafeInteger(minAgeHours) ||
      minAgeHours < DEFAULT_MIN_AGE_HOURS || minAgeHours > 720) {
    throw new RangeError('Invalid orphan inspection age');
  }
}

/**
 * PostgreSQL's clock is authoritative. Filter excludes records with an
 * actual e2ee_messages row OR a terminal receipt. The result is only
 * an *observational* snapshot, NOT an exclusive claim.
 */
async function inspectOrphanCandidates({
  query, limit = 25, minAgeHours = DEFAULT_MIN_AGE_HOURS
}) {
  checkArgs(query, limit, minAgeHours);
  const result = await query(
    `SELECT mo.object_key, mo.message_id, mo.state, mo.reserved_at, mo.uploaded_at
       FROM public.message_r2_objects AS mo
      WHERE mo.state IN ('RESERVED','UPLOADED')
        AND mo.reserved_at < clock_timestamp() - ($1::integer * interval '1 hour')
        AND (mo.state <> 'UPLOADED'
          OR mo.uploaded_at < clock_timestamp() - ($1::integer * interval '1 hour'))
        AND NOT EXISTS (
          SELECT 1 FROM public.e2ee_messages AS m WHERE m.id = mo.message_id
        )
        AND NOT EXISTS (
          SELECT 1 FROM public.message_delivery_receipts AS r
           WHERE r.message_id = mo.message_id
        )
      ORDER BY mo.reserved_at, mo.object_key
      LIMIT $2`,
    [minAgeHours, limit]
  );
  if (!result || !Array.isArray(result.rows)) throw new Error('Invalid database result');
  // Validate even observational keys to keep malformed records observable
  // as errors rather than handing invalid keys to a future worker.
  return result.rows.map(row => ({
    objectKey: validateOpaqueObjectKey(row.object_key),
    messageId: row.message_id,
    state: row.state,
    reservedAt: row.reserved_at,
    uploadedAt: row.uploaded_at
  }));
}

/**
 * Used by operator dashboards, never by deletion workflows.
 * No raw object keys, message IDs or identities appear in summary.
 */
function summarizeCandidates(candidates) {
  if (!Array.isArray(candidates)) throw new TypeError('candidates required');
  const totals = { reserved: 0, uploaded: 0, other: 0 };
  for (const candidate of candidates) {
    if (candidate.state === 'RESERVED') totals.reserved += 1;
    else if (candidate.state === 'UPLOADED') totals.uploaded += 1;
    else totals.other += 1;
  }
  return totals;
}

module.exports = {
  DEFAULT_MIN_AGE_HOURS,
  MAX_LIMIT,
  inspectOrphanCandidates,
  summarizeCandidates
};
