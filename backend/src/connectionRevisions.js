'use strict';

// Durable relationship revisions (Mode B only). All writes must run inside the
// SAME PostgreSQL transaction and pair advisory lock as the legacy connections
// mutation. Never auto-create or silently fall back when the schema is absent.
const REVISION_FLAG = 'CONNECTION_REVISIONS_ENABLED';

function revisionsEnabled(env = process.env) {
  return env[REVISION_FLAG] === 'true';
}

function pairIdentityKey(a, b) {
  return [String(a).toLowerCase(), String(b).toLowerCase()].sort().join('<->');
}

function stateFromDirections(from, to, fromMarked, toMarked) {
  if (fromMarked && toMarked) return 'MUTUAL';
  if (!fromMarked && !toMarked) return 'NONE';
  const fromIsLow = String(from).toLowerCase() < String(to).toLowerCase();
  return fromMarked
    ? (fromIsLow ? 'MARKED_LOW' : 'MARKED_HIGH')
    : (fromIsLow ? 'MARKED_HIGH' : 'MARKED_LOW');
}

async function advancePairRevision(txQuery, from, to, currentState, action) {
  if (!['NONE', 'MARKED_LOW', 'MARKED_HIGH', 'MUTUAL'].includes(currentState)) {
    throw new Error('Invalid pair revision state');
  }
  if (!['MARK', 'MUTUAL', 'UNMARK'].includes(action)) {
    throw new Error('Invalid pair revision action');
  }
  const lowerFrom = String(from).toLowerCase();
  const lowerTo = String(to).toLowerCase();
  if (!lowerFrom || !lowerTo || lowerFrom === lowerTo) {
    throw new Error('Invalid revision pair identities');
  }
  const low = lowerFrom < lowerTo ? from : to;
  const high = lowerFrom < lowerTo ? to : from;
  const key = pairIdentityKey(from, to);
  const result = await txQuery(
    `INSERT INTO connection_revisions
       (pair_key, identity_low, identity_high, current_state, revision, updated_at)
     VALUES ($1, $2, $3, $4, 1, NOW())
     ON CONFLICT (pair_key) DO UPDATE SET
       current_state = EXCLUDED.current_state,
       revision = connection_revisions.revision + 1,
       updated_at = NOW()
     RETURNING revision`,
    [key, low, high, currentState]
  );
  const revision = Number(result.rows[0]?.revision);
  if (!Number.isSafeInteger(revision) || revision < 1) {
    throw new Error('Unsafe or missing connection revision');
  }
  await txQuery(
    `INSERT INTO connection_events
       (pair_key, actor_identity, target_identity, action, resulting_state, revision)
     VALUES ($1, $2, $3, $4, $5, $6)`,
    [key, from, to, action, currentState, revision]
  );
  return revision;
}

module.exports = { revisionsEnabled, pairIdentityKey, stateFromDirections, advancePairRevision };
