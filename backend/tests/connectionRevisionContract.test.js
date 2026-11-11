'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const {
  revisionsEnabled, pairIdentityKey, stateFromDirections, advancePairRevision
} = require('../src/connectionRevisions');

test('revision feature is strictly opt-in and cannot turn on accidentally', () => {
  assert.equal(revisionsEnabled({}), false);
  assert.equal(revisionsEnabled({ CONNECTION_REVISIONS_ENABLED: 'false' }), false);
  assert.equal(revisionsEnabled({ CONNECTION_REVISIONS_ENABLED: 'true' }), true);
});

test('canonical pairs, direction states and revocation agree regardless of caller order', () => {
  assert.equal(pairIdentityKey('FFFF', 'AAAA'), 'aaaa<->ffff');
  assert.equal(pairIdentityKey('aaaa', 'ffff'), 'aaaa<->ffff');
  assert.equal(stateFromDirections('FFFF', 'AAAA', true, false), 'MARKED_HIGH');
  assert.equal(stateFromDirections('FFFF', 'AAAA', false, true), 'MARKED_LOW');
  assert.equal(stateFromDirections('FFFF', 'AAAA', true, true), 'MUTUAL');
  assert.equal(stateFromDirections('FFFF', 'AAAA', false, false), 'NONE');
});

test('revision upsert and event are composed on same transaction callback', async () => {
  const queries = [];
  const tx = async (sql, args) => {
    queries.push({sql, args});
    if (sql.includes('RETURNING revision')) return {rows: [{revision: '42'}]};
    return {rowCount: 1, rows: []};
  };
  const revision = await advancePairRevision(tx, 'FFFF', 'AAAA', 'NONE', 'UNMARK');
  assert.equal(revision, 42);
  assert.equal(queries.length, 2);
  assert.match(queries[0].sql, /ON CONFLICT \(pair_key\) DO UPDATE/);
  assert.match(queries[0].sql, /revision = connection_revisions\.revision \+ 1/);
  assert.equal(queries[0].args[0], 'aaaa<->ffff');
  assert.equal(queries[0].args[1], 'AAAA');
  assert.equal(queries[0].args[2], 'FFFF');
  assert.match(queries[1].sql, /INSERT INTO connection_events/);
  assert.deepEqual(queries[1].args, ['aaaa<->ffff', 'FFFF', 'AAAA', 'UNMARK', 'NONE', 42]);
});

test('invalid data and unsafe revisions fail closed', async () => {
  const unused = async () => { throw new Error('unexpected database call'); };
  await assert.rejects(() => advancePairRevision(unused, 'A', 'A', 'MUTUAL', 'MUTUAL'));
  await assert.rejects(() => advancePairRevision(unused, 'A', 'B', 'FAKE', 'MARK'));
  const invalidTx = async () => ({ rows: [{revision: '9007199254740993'}] });
  await assert.rejects(() => advancePairRevision(invalidTx, 'A', 'B', 'MUTUAL', 'MUTUAL'));
});
