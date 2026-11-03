'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { makeInviteLifecycle, tokenHash, CONSUME_ATOMIC_SQL } = require('../src/inviteLifecycle');

const cleanId = value => String(value || '').replace(/^peer-/i, '').trim().toUpperCase();
function memoryFixture(now = () => Date.now()) {
  const memoryStore = new Map();
  const svc = makeInviteLifecycle({
    memoryStore, now, secret: 'only-for-tests', isPostgres: () => false,
    databaseConfigured: () => false, query: async () => { throw Error('Not allowed'); }
  });
  return { svc, memoryStore };
}

function dbFixture(options = {}) {
  const rows = new Map();
  const edges = new Map();
  let ready = options.ready !== false;
  let fail = false;
  const query = async (sql, args) => {
    if (fail) throw Error('database down');
    if (/^INSERT INTO invites/i.test(sql.trim())) {
      rows.set(args[0], {
        id: args[0], token: args[1], sender_identity: args[2],
        sender_alias: args[3], signature: args[4], expires_at: new Date(args[5]),
        is_used: false
      });
      return { rows: [{ id: args[0] }], rowCount: 1 };
    }
    if (sql.includes('WITH consumed AS')) {
      const [id, receiver, hash, token] = args;
      const row = rows.get(id);
      if (!row || row.is_used || Date.now() >= row.expires_at.getTime() ||
          row.sender_identity === receiver || !(row.token === hash || row.token === token)) {
        return { rows: [], rowCount: 0 };
      }
      // Model the SQL as one indivisible step.
      row.is_used = true;
      edges.set(row.sender_identity + ':' + receiver, true);
      edges.set(receiver + ':' + row.sender_identity, true);
      return { rows: [{ sender_identity: row.sender_identity,
        sender_alias: row.sender_alias }], rowCount: 1 };
    }
    if (sql.startsWith('SELECT token,')) {
      const row = rows.get(args[0]);
      return { rows: row ? [row] : [] };
    }
    throw Error('Unexpected query: ' + sql);
  };
  const svc = makeInviteLifecycle({
    memoryStore: new Map(), query, isPostgres: () => ready,
    databaseConfigured: () => true, secret: 'only-for-tests'
  });
  return { svc, rows, edges, setReady: v => { ready = v; },
    setFail: v => { fail = v; } };
}

test('new tokens are stored as digests, not reusable plaintext', async () => {
  const db = dbFixture();
  const r = await db.svc.create({ sender: 'ALICE', alias: 'Alice' });
  assert.equal(r.status, 200);
  const row = db.rows.get(r.invite.inviteId);
  assert.equal(row.token, tokenHash(r.invite.token));
  assert.notEqual(row.token, r.invite.token);
  assert.ok(r.invite.signature.length === 64);
});

test('link and QR contain one shared capability; first redemption wins atomically', async () => {
  const db = dbFixture();
  const created = await db.svc.create({ sender: 'ALICE', alias: 'Alice' });
  const i = created.invite;
  const results = await Promise.all([
    db.svc.redeem({ inviteId: i.inviteId, token: i.token, receiver: 'BOB', cleanId }),
    db.svc.redeem({ inviteId: i.inviteId, token: i.token, receiver: 'CAROL', cleanId })
  ]);
  assert.deepEqual(results.map(x => x.status).sort(), [200,410]);
  assert.equal(db.edges.size, 2);
  assert.ok(CONSUME_ATOMIC_SQL.includes('UPDATE invites'));
  assert.ok(CONSUME_ATOMIC_SQL.includes('INSERT INTO connections'));
});

test('invalid tokens, expired ids and self-redemption are rejected', async () => {
  const local = memoryFixture();
  const r = await local.svc.create({ sender: 'ALICE', alias: 'Alice' });
  const {inviteId, token} = r.invite;
  let result = await local.svc.redeem({ inviteId, token: 'a'.repeat(48), receiver: 'BOB', cleanId });
  assert.equal(result.status, 403);
  result = await local.svc.redeem({ inviteId, token, receiver: 'ALICE', cleanId });
  assert.equal(result.status, 400);
  result = await local.svc.redeem({ inviteId, token, receiver: 'BOB', cleanId });
  assert.equal(result.status, 200);
  result = await local.svc.redeem({ inviteId, token, receiver: 'CAROL', cleanId });
  assert.equal(result.status, 410);
  result = await local.svc.redeem({ inviteId: 'inv_ffffffffffff', token, receiver: 'BOB', cleanId });
  assert.equal(result.status, 404);
});

test('authoritative DB outage never falls back to forgeable unsigned / RAM invite', async () => {
  const db = dbFixture({ ready: false });
  assert.equal((await db.svc.create({ sender: 'ALICE', alias: 'Alice' })).status, 503);
  assert.equal((await db.svc.redeem({
    inviteId: 'inv_0123456789ab', token: 'a'.repeat(48),
    receiver: 'BOB', cleanId
  })).status, 503);
  db.setReady(true);
  const created = await db.svc.create({ sender: 'ALICE', alias: 'Alice' });
  db.setFail(true);
  assert.equal((await db.svc.redeem({
    inviteId: created.invite.inviteId, token: created.invite.token,
    receiver: 'BOB', cleanId
  })).status, 503);
  assert.equal(db.edges.size, 0);
});

test('postgreSQL rows with legacy plaintext token can still be redeemed only once', async () => {
  const db = dbFixture();
  const created = await db.svc.create({ sender: 'ALICE', alias: 'Alice' });
  const i = created.invite;
  db.rows.get(i.inviteId).token = i.token;
  assert.equal((await db.svc.redeem({
    inviteId: i.inviteId, token: i.token, receiver: 'BOB', cleanId
  })).status, 200);
  assert.equal((await db.svc.redeem({
    inviteId: i.inviteId, token: i.token, receiver: 'CAROL', cleanId
  })).status, 410);
});
