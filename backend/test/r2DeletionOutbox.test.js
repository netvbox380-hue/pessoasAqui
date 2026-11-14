'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const {
  retrySeconds, sanitizedErrorCode, claimDeletionBatch, markDeletionDone,
  rescheduleDeletion, processDeletionBatch
} = require('../src/r2DeletionOutbox');

test('retry schedule increases exponentially and is bounded to one hour', () => {
  assert.equal(retrySeconds(1), 5);
  assert.equal(retrySeconds(2), 10);
  assert.equal(retrySeconds(3), 20);
  assert.equal(retrySeconds(40), 3600);
  assert.throws(() => retrySeconds(0), /Invalid attempt/);
});

test('errors are sanitized and do not persist signed URLs', () => {
  assert.equal(sanitizedErrorCode({ name: 'ServiceUnavailable' }), 'ServiceUnavailable');
  assert.equal(sanitizedErrorCode({ name: 'token=https://secret' }), 'R2_OPERATION_FAILED');
  assert.equal(sanitizedErrorCode({ name: '' }), 'R2_OPERATION_FAILED');
  assert.equal(sanitizedErrorCode(null), 'R2_OPERATION_FAILED');
});

test('claim uses skip locked, due-time filter and lease', async () => {
  const calls = [];
  const jobs = [{ object_key: 'pending/ab/cd/valid', message_id: 'msg-1', attempts: 1 }];
  const result = await claimDeletionBatch({
    query: async (sql, args) => { calls.push({ sql, args }); return { rows: jobs }; },
    batchSize: 5,
    leaseSeconds: 120
  });
  assert.deepEqual(result, jobs);
  assert.deepEqual(calls[0].args, [5, 120]);
  assert.match(calls[0].sql, /FOR UPDATE SKIP LOCKED/);
  assert.match(calls[0].sql, /next_attempt_at <= clock_timestamp\(\)/);
  assert.match(calls[0].sql, /attempts = attempts \+ 1/);
});

test('invalid lease and batch size fail before database access', async () => {
  const query = () => { throw Error('should not connect'); };
  await assert.rejects(claimDeletionBatch({ query, batchSize: 0 }), /batch size/);
  await assert.rejects(claimDeletionBatch({ query, batchSize: 51 }), /batch size/);
  await assert.rejects(claimDeletionBatch({ query, leaseSeconds: 2 }), /lease/);
});

test('completion and retry are attempt-fenced', async () => {
  const calls = [];
  const query = async (sql, args) => {
    calls.push({ sql, args });
    return { rowCount: args[1] === 3 ? 1 : 0 };
  };
  assert.equal(await markDeletionDone({ query, objectKey: 'x', attempts: 3 }), true);
  assert.equal(await markDeletionDone({ query, objectKey: 'x', attempts: 2 }), false);
  assert.equal(await rescheduleDeletion({
    query, objectKey: 'x', attempts: 3, error: { name: 'TimeoutError', message: 'https://signed-secret' }
  }), true);
  assert.deepEqual(calls[2].args, ['x', 3, 20, 'TimeoutError']);
  assert.match(calls[0].sql, /attempts=\$2/);
  assert.match(calls[2].sql, /attempts=\$2/);
});

test('successful delete acknowledges only after R2 confirms completion', async () => {
  const trace = [];
  const query = async (sql) => {
    if (sql.includes('WITH claimed AS')) {
      trace.push('claim');
      return { rows: [{ object_key: 'pending/12/34/12345678-1234-4234-8234-123456789abc', message_id: 'm', attempts: 1 }] };
    }
    trace.push('done');
    return { rowCount: 1 };
  };
  const out = await processDeletionBatch({
    query, deleteObject: async key => { assert.equal(key, 'pending/12/34/12345678-1234-4234-8234-123456789abc'); trace.push('R2'); }
  });
  assert.deepEqual(trace, ['claim', 'R2', 'done']);
  assert.deepEqual(out, { claimed: 1, done: 1, retry: 0, stale: 0 });
});

test('R2 failure requeues job instead of marking DONE', async () => {
  const trace = [];
  const query = async sql => {
    if (sql.includes('WITH claimed AS')) {
      trace.push('claim');
      return { rows: [{ object_key: 'pending/12/34/12345678-1234-4234-8234-123456789abc', message_id: 'm', attempts: 2 }] };
    }
    assert.match(sql, /SET state='RETRY'/);
    trace.push('retry');
    return { rowCount: 1 };
  };
  const out = await processDeletionBatch({
    query, deleteObject: async () => { trace.push('error'); throw new Error('network'); }
  });
  assert.deepEqual(trace, ['claim', 'error', 'retry']);
  assert.equal(out.done, 0);
  assert.equal(out.retry, 1);
});

test('stale deletion acknowledgement cannot overwrite another worker attempt', async () => {
  const query = async sql => {
    if (sql.includes('WITH claimed AS')) {
      return { rows: [{ object_key: 'pending/12/34/12345678-1234-4234-8234-123456789abc', message_id: 'm', attempts: 1 }] };
    }
    return { rowCount: 0 };
  };
  const out = await processDeletionBatch({ query, deleteObject: async () => {} });
  assert.deepEqual(out, { claimed: 1, done: 0, retry: 0, stale: 1 });
});

test('database failures are surfaced, not swallowed as successful deletion', async () => {
  await assert.rejects(processDeletionBatch({
    query: async () => { throw new Error('DB_DOWN'); },
    deleteObject: async () => {}
  }), /DB_DOWN/);
});

test('empty batch causes no remote calls', async () => {
  let calls = 0;
  const out = await processDeletionBatch({
    query: async () => ({ rows: [] }),
    deleteObject: async () => { calls += 1; }
  });
  assert.equal(calls, 0);
  assert.deepEqual(out, { claimed: 0, done: 0, retry: 0, stale: 0 });
});


test('corrupted outbox key cannot trigger arbitrary R2 deletion', async () => {
  let r2DeleteCalled = false;
  const query = async sql => {
    if (sql.includes('WITH claimed AS')) {
      return { rows: [{ object_key: '../production-backup', message_id: 'm', attempts: 1 }] };
    }
    assert.match(sql, /SET state='RETRY'/);
    return { rowCount: 1 };
  };
  const outcome = await processDeletionBatch({
    query,
    deleteObject: async () => { r2DeleteCalled = true; }
  });
  assert.equal(r2DeleteCalled, false);
  assert.equal(outcome.retry, 1);
  assert.equal(outcome.done, 0);
});
