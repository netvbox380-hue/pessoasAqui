'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const {
  DEFAULT_MIN_AGE_HOURS, inspectOrphanCandidates, summarizeCandidates
} = require('../src/r2OrphanInspector');

const key = 'pending/12/34/12345678-1234-4234-8234-123456789abc';

test('candidate inspection is exclusively SELECT with DB clock and excludes messages/receipts', async () => {
  const calls = [];
  const query = async (sql, params) => {
    calls.push({ sql, params });
    return { rows: [{
      object_key: key, message_id: 'm1', state: 'RESERVED',
      reserved_at: '2026-11-15T00:00:00Z', uploaded_at: null
    }] };
  };
  const result = await inspectOrphanCandidates({ query });
  assert.equal(result.length, 1);
  assert.equal(result[0].objectKey, key);
  assert.equal(calls.length, 1);
  assert.deepEqual(calls[0].params, [DEFAULT_MIN_AGE_HOURS, 25]);
  assert.match(calls[0].sql, /^SELECT /);
  assert.match(calls[0].sql, /NOT EXISTS \(\s*SELECT 1 FROM public\.e2ee_messages/);
  assert.match(calls[0].sql, /NOT EXISTS \(\s*SELECT 1 FROM public\.message_delivery_receipts/);
  assert.match(calls[0].sql, /mo\.uploaded_at < clock_timestamp\(\)/);
  assert.equal(/\bDELETE\b|\bUPDATE\b|\bINSERT\b/.test(calls[0].sql), false);
});

test('candidate scan allows a bounded batch but not immediate upload cleanup', async () => {
  const query = async () => ({ rows: [] });
  for (const age of [0, 1, 3, 721, -1, 1.5]) {
    await assert.rejects(inspectOrphanCandidates({ query, minAgeHours: age }), RangeError);
  }
  for (const limit of [0, 101, -1, 1.5]) {
    await assert.rejects(inspectOrphanCandidates({ query, limit }), RangeError);
  }
  assert.deepEqual(await inspectOrphanCandidates({ query, limit: 100, minAgeHours: 24 }), []);
});

test('corrupted or malformed R2 object key prevents candidate handoff', async () => {
  const query = async () => ({ rows: [{
    object_key: '../../secret', message_id: 'm1', state: 'UPLOADED',
    reserved_at: new Date(), uploaded_at: new Date()
  }] });
  await assert.rejects(inspectOrphanCandidates({ query }), /Invalid R2 object key/);
});

test('database outages fail closed and do not synthesize candidate list', async () => {
  await assert.rejects(inspectOrphanCandidates({
    query: async () => { throw new Error('POSTGRES_DOWN'); }
  }), /POSTGRES_DOWN/);
});

test('empty and bounded candidate summaries disclose counts only', () => {
  assert.deepEqual(summarizeCandidates([]), { reserved: 0, uploaded: 0, other: 0 });
  const result = summarizeCandidates([
    { state: 'RESERVED', objectKey: key },
    { state: 'UPLOADED', messageId: 'm-secret' },
    { state: 'OTHER' }
  ]);
  assert.deepEqual(result, { reserved: 1, uploaded: 1, other: 1 });
  assert.equal(JSON.stringify(result).includes('m-secret'), false);
  assert.throws(() => summarizeCandidates(null), TypeError);
});
