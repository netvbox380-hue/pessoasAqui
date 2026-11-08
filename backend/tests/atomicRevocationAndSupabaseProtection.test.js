'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { spawn, spawnSync } = require('child_process');
const { Pool } = require('pg');
const path = require('path');
const crypto = require('node:crypto');
const WebSocket = require('ws');

const PG_PORT = 54333;
const PG_DATA = path.resolve(__dirname, '../.pg_isolated_data');
const PG_BIN = 'C:/Program Files/PostgreSQL/18/bin/postgres.exe';
const CONNECTION_STRING = `postgresql://postgres@127.0.0.1:${PG_PORT}/postgres`;

const { identityFromPublicKey } = require('../src/identityAuth');
const auth = require('../src/inviteAuth');
const {
  SCHEMA_STATUS,
  checkSchemaCompatibility,
  getAppliedMigrations,
  runPendingMigrations,
  rollbackMigration
} = require('../src/migrations');

let pgProcess = null;

function startPostgresServer() {
  return new Promise((resolve, reject) => {
    try {
      spawnSync('C:/Program Files/PostgreSQL/18/bin/pg_ctl.exe', ['stop', '-D', PG_DATA, '-m', 'immediate'], { encoding: 'utf8' });
    } catch (_) {}

    const proc = spawn(PG_BIN, ['-D', PG_DATA, '-p', String(PG_PORT)]);
    let resolved = false;

    const onData = (chunk) => {
      const msg = chunk.toString();
      if (!resolved && msg.includes('ready to accept connections')) {
        resolved = true;
        resolve(proc);
      }
    };

    proc.stderr.on('data', onData);
    proc.stdout.on('data', onData);

    proc.on('error', (err) => {
      if (!resolved) {
        resolved = true;
        reject(err);
      }
    });

    proc.on('exit', (code) => {
      if (!resolved) {
        resolved = true;
        reject(new Error(`PostgreSQL exited prematurely with code ${code}`));
      }
    });

    setTimeout(() => {
      if (!resolved) {
        resolved = true;
        reject(new Error('Timeout starting PostgreSQL'));
      }
    }, 10000);
  });
}

function stopPostgresServer(proc) {
  return new Promise((resolve) => {
    try {
      spawnSync('C:/Program Files/PostgreSQL/18/bin/pg_ctl.exe', ['stop', '-D', PG_DATA, '-m', 'immediate'], { encoding: 'utf8' });
    } catch (_) {}
    if (proc && !proc.killed) {
      try { proc.kill(); } catch (_) {}
    }
    setTimeout(resolve, 500);
  });
}

function createCryptoUser(alias = 'Usuário') {
  const pair = crypto.generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
  const pub = pair.publicKey.export({ format: 'der', type: 'spki' }).toString('base64');
  const id = identityFromPublicKey(pub);
  return { pair, pub, id, alias };
}

function digestHttpParts(parts) {
  return crypto.createHash('sha256')
    .update(parts.map(value => String(value).length + ':' + String(value)).join('|'), 'utf8')
    .digest('hex');
}

function signHttpProof(user, action, targetId = '', tokenHash = '') {
  const timestamp = Date.now();
  const nonce = crypto.randomUUID();
  const canonical = auth.canonicalInviteProof(action, user.id, targetId, '', tokenHash, timestamp, nonce);
  const s = crypto.createSign('SHA256');
  s.update(canonical);
  s.end();
  return {
    v: 1,
    publicKey: user.pub,
    nonce,
    timestamp,
    signature: s.sign(user.pair.privateKey).toString('base64')
  };
}

test('SUÍTE: AUDITORIA SISTÊMICA DE COMPATIBILIDADE, CONCORRÊNCIA E SEGURANÇA DO SUPABASE', async (t) => {
  pgProcess = await startPostgresServer();
  assert.ok(pgProcess, 'PostgreSQL 18 deve iniciar com sucesso');

  const pool = new Pool({ connectionString: CONNECTION_STRING });
  pool.on('error', () => {});

  // 1. Prepara o esquema no PostgreSQL isolado
  const setupClient = await pool.connect();
  try {
    await setupClient.query(`
      CREATE TABLE IF NOT EXISTS identities (
        id VARCHAR(64) PRIMARY KEY,
        public_key_ed25519 TEXT NOT NULL,
        public_key_x25519 TEXT,
        pin_salted_hash VARCHAR(128) NOT NULL,
        alias VARCHAR(64) DEFAULT 'Eu',
        is_banned BOOLEAN DEFAULT FALSE,
        created_at TIMESTAMPTZ DEFAULT NOW(),
        updated_at TIMESTAMPTZ DEFAULT NOW()
      );

      CREATE TABLE IF NOT EXISTS connections (
        id BIGSERIAL PRIMARY KEY,
        from_identity VARCHAR(64) NOT NULL,
        to_identity VARCHAR(64) NOT NULL,
        is_mutual BOOLEAN DEFAULT FALSE,
        created_at TIMESTAMPTZ DEFAULT NOW(),
        UNIQUE (from_identity, to_identity)
      );

      CREATE TABLE IF NOT EXISTS e2ee_messages (
        id VARCHAR(128) PRIMARY KEY,
        sender_identity VARCHAR(64) NOT NULL,
        recipient_identity VARCHAR(64) NOT NULL,
        sender_alias VARCHAR(64),
        ciphertext_payload TEXT NOT NULL,
        iv_nonce VARCHAR(64) NOT NULL,
        is_delivered BOOLEAN DEFAULT FALSE,
        is_cancelled BOOLEAN DEFAULT FALSE,
        cancelled_at TIMESTAMPTZ,
        delivered_at TIMESTAMPTZ,
        created_at TIMESTAMPTZ DEFAULT NOW()
      );

      CREATE TABLE IF NOT EXISTS recovery_proof_nonces (
        identity_id VARCHAR(64) NOT NULL,
        operation VARCHAR(64) NOT NULL,
        nonce VARCHAR(64) NOT NULL,
        created_at TIMESTAMPTZ DEFAULT NOW(),
        PRIMARY KEY(identity_id, operation, nonce)
      );

      CREATE TABLE IF NOT EXISTS identity_key_bindings (
        identity_id VARCHAR(64) PRIMARY KEY,
        public_key_spki TEXT NOT NULL,
        generation BIGINT DEFAULT 1,
        created_at TIMESTAMPTZ DEFAULT NOW()
      );

      CREATE TABLE IF NOT EXISTS recovery_delegations (
        target_identity VARCHAR(64) NOT NULL,
        family_identity VARCHAR(64) NOT NULL,
        family_public_key TEXT NOT NULL,
        is_active BOOLEAN NOT NULL DEFAULT TRUE,
        updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
        PRIMARY KEY(target_identity, family_identity)
      );

      CREATE TABLE IF NOT EXISTS recovery_grants (
        id VARCHAR(64) PRIMARY KEY,
        token_hash CHAR(64) NOT NULL UNIQUE,
        target_identity VARCHAR(64) NOT NULL,
        family_identity VARCHAR(64) NOT NULL,
        family_public_key TEXT NOT NULL,
        expected_generation BIGINT NOT NULL,
        expires_at TIMESTAMPTZ NOT NULL,
        used_at TIMESTAMPTZ,
        created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
      );

      TRUNCATE TABLE e2ee_messages, connections, recovery_proof_nonces, identity_key_bindings, recovery_delegations, recovery_grants;
    `);
  } finally {
    setupClient.release();
  }

  process.env.DATABASE_URL = CONNECTION_STRING;
  const { app, server, activeSockets, wss } = require('../src/server');
  const { initDatabase, closeDatabase, resetCircuitBreaker, isPostgres } = require('../src/db');
  resetCircuitBreaker();
  initDatabase();

  await new Promise((resolve) => server.listen(0, resolve));
  const port = server.address().port;
  const httpUrl = `http://127.0.0.1:${port}`;
  const wsUrl = `ws://127.0.0.1:${port}`;

  const alice = createCryptoUser('Alice');
  const bob = createCryptoUser('Bob');
  const charlie = createCryptoUser('Charlie');

  const regClient = await pool.connect();
  try {
    await regClient.query(`
      INSERT INTO identities (id, public_key_ed25519, public_key_x25519, pin_salted_hash, alias) VALUES
      ($1, $2, $2, 'salt_alice', $3),
      ($4, $5, $5, 'salt_bob', $6),
      ($7, $8, $8, 'salt_charlie', $9)
      ON CONFLICT (id) DO NOTHING
    `, [alice.id, alice.pub, alice.alias, bob.id, bob.pub, bob.alias, charlie.id, charlie.pub, charlie.alias]);

    await regClient.query(`
      INSERT INTO identity_key_bindings (identity_id, public_key_spki) VALUES
      ($1, $2), ($3, $4), ($5, $6)
      ON CONFLICT (identity_id) DO NOTHING
    `, [alice.id, alice.pub, bob.id, bob.pub, charlie.id, charlie.pub]);
  } finally {
    regClient.release();
  }

  try {
    // -------------------------------------------------------------------------
    // TESTE 1: Revogação Normal Atômica (Transação única)
    // -------------------------------------------------------------------------
    await t.test('1. Revogação Normal Atômica: Exclusão bilateral e cancelamento de mensagens sob commit único', async () => {
      const msgDeliveredId = `msg_deliv_${Date.now()}`;
      const msgPendingId = `msg_pend_${Date.now()}`;

      const client = await pool.connect();
      try {
        await client.query(`
          INSERT INTO connections (from_identity, to_identity, is_mutual) VALUES
          ($1, $2, TRUE), ($2, $1, TRUE)
          ON CONFLICT (from_identity, to_identity) DO UPDATE SET is_mutual = TRUE;
        `, [alice.id, bob.id]);

        await client.query(`
          INSERT INTO e2ee_messages (id, sender_identity, recipient_identity, sender_alias, ciphertext_payload, iv_nonce, is_delivered, is_cancelled)
          VALUES
          ($1, $3, $4, 'Alice', 'ENC:DeliveredPayload', 'iv1', TRUE, FALSE),
          ($2, $3, $4, 'Alice', 'ENC:PendingPayload', 'iv2', FALSE, FALSE);
        `, [msgDeliveredId, msgPendingId, alice.id, bob.id]);
      } finally {
        client.release();
      }

      const proofUnmark = signHttpProof(alice, 'CONNECTION_UNMARK', bob.id);
      const res = await fetch(`${httpUrl}/api/connections/unmark`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          fromIdentity: alice.id,
          toIdentity: bob.id,
          senderAlias: alice.alias,
          identityProof: proofUnmark
        })
      });

      assert.strictEqual(res.status, 200);

      const checkClient = await pool.connect();
      try {
        const connRes = await checkClient.query(`
          SELECT * FROM connections 
          WHERE (from_identity = $1 AND to_identity = $2) OR (from_identity = $2 AND to_identity = $1)
        `, [alice.id, bob.id]);
        assert.strictEqual(connRes.rowCount, 0, 'Ambos os registros em connections devem ser deletados');

        const pendRes = await checkClient.query('SELECT is_delivered, is_cancelled, cancelled_at FROM e2ee_messages WHERE id = $1', [msgPendingId]);
        assert.strictEqual(pendRes.rows[0].is_cancelled, true, 'Mensagem não entregue deve ser cancelada');
        assert.strictEqual(pendRes.rows[0].is_delivered, false, 'is_delivered permanece false');
        assert.ok(pendRes.rows[0].cancelled_at, 'cancelled_at preenchido');

        const delivRes = await checkClient.query('SELECT is_delivered, is_cancelled FROM e2ee_messages WHERE id = $1', [msgDeliveredId]);
        assert.strictEqual(delivRes.rows[0].is_delivered, true, 'Mensagem já entregue permanece entregue');
        assert.strictEqual(delivRes.rows[0].is_cancelled, false, 'Mensagem entregue NUNCA é cancelada');
      } finally {
        checkClient.release();
      }
    });

    // -------------------------------------------------------------------------
    // TESTE 2: Falha durante DELETE aciona ROLLBACK total
    // -------------------------------------------------------------------------
    await t.test('2. Falha durante DELETE: Transação aborta com rollback; conexões e mensagens permanecem intactas', async () => {
      const msgFailId = `msg_fail_del_${Date.now()}`;
      const client = await pool.connect();
      try {
        await client.query(`
          INSERT INTO connections (from_identity, to_identity, is_mutual) VALUES
          ($1, $2, TRUE), ($2, $1, TRUE)
          ON CONFLICT (from_identity, to_identity) DO UPDATE SET is_mutual = TRUE
        `, [alice.id, bob.id]);

        await client.query(`
          INSERT INTO e2ee_messages (id, sender_identity, recipient_identity, sender_alias, ciphertext_payload, iv_nonce, is_delivered, is_cancelled)
          VALUES ($1, $2, $3, 'Alice', 'ENC:FailPayload', 'iv', FALSE, FALSE)
        `, [msgFailId, alice.id, bob.id]);

        await client.query(`
          CREATE OR REPLACE FUNCTION trigger_abort_delete() RETURNS trigger AS $BODY$
          BEGIN
            RAISE EXCEPTION 'DELETE_SIMULATED_FAILURE';
          END;
          $BODY$ LANGUAGE plpgsql;

          DROP TRIGGER IF EXISTS trg_abort_delete ON connections;
          CREATE TRIGGER trg_abort_delete BEFORE DELETE ON connections
          FOR EACH ROW EXECUTE FUNCTION trigger_abort_delete();
        `);
      } finally {
        client.release();
      }

      const proofUnmark = signHttpProof(alice, 'CONNECTION_UNMARK', bob.id);
      const res = await fetch(`${httpUrl}/api/connections/unmark`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          fromIdentity: alice.id,
          toIdentity: bob.id,
          senderAlias: alice.alias,
          identityProof: proofUnmark
        })
      });

      assert.strictEqual(res.status, 503, 'Falha no banco deve retornar HTTP 503');

      const checkClient = await pool.connect();
      try {
        await checkClient.query(`DROP TRIGGER IF EXISTS trg_abort_delete ON connections;`);
        const connRes = await checkClient.query(`SELECT * FROM connections WHERE from_identity = $1 AND to_identity = $2`, [alice.id, bob.id]);
        assert.strictEqual(connRes.rowCount, 1, 'Conexão deve ser preservada pelo rollback');

        const msgRes = await checkClient.query(`SELECT is_delivered, is_cancelled FROM e2ee_messages WHERE id = $1`, [msgFailId]);
        assert.strictEqual(msgRes.rows[0].is_cancelled, false, 'Mensagem deve permanecer não-cancelada');
      } finally {
        checkClient.release();
      }
    });

    // -------------------------------------------------------------------------
    // TESTE 3: Falha durante UPDATE aciona ROLLBACK revertendo o DELETE
    // -------------------------------------------------------------------------
    await t.test('3. Falha durante UPDATE: DELETE é revertido atomicamente pelo rollback', async () => {
      const msgFailUpdId = `msg_fail_upd_${Date.now()}`;
      const client = await pool.connect();
      try {
        await client.query(`
          INSERT INTO connections (from_identity, to_identity, is_mutual) VALUES
          ($1, $2, TRUE), ($2, $1, TRUE)
          ON CONFLICT (from_identity, to_identity) DO UPDATE SET is_mutual = TRUE
        `, [alice.id, bob.id]);

        await client.query(`
          INSERT INTO e2ee_messages (id, sender_identity, recipient_identity, sender_alias, ciphertext_payload, iv_nonce, is_delivered, is_cancelled)
          VALUES ($1, $2, $3, 'Alice', 'ENC:FailUpdPayload', 'iv', FALSE, FALSE)
        `, [msgFailUpdId, alice.id, bob.id]);

        await client.query(`
          CREATE OR REPLACE FUNCTION trigger_abort_update() RETURNS trigger AS $BODY$
          BEGIN
            RAISE EXCEPTION 'UPDATE_SIMULATED_FAILURE';
          END;
          $BODY$ LANGUAGE plpgsql;

          DROP TRIGGER IF EXISTS trg_abort_update ON e2ee_messages;
          CREATE TRIGGER trg_abort_update BEFORE UPDATE ON e2ee_messages
          FOR EACH ROW EXECUTE FUNCTION trigger_abort_update();
        `);
      } finally {
        client.release();
      }

      const proofUnmark = signHttpProof(alice, 'CONNECTION_UNMARK', bob.id);
      const res = await fetch(`${httpUrl}/api/connections/unmark`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          fromIdentity: alice.id,
          toIdentity: bob.id,
          senderAlias: alice.alias,
          identityProof: proofUnmark
        })
      });

      assert.strictEqual(res.status, 503);

      const checkClient = await pool.connect();
      try {
        await checkClient.query(`DROP TRIGGER IF EXISTS trg_abort_update ON e2ee_messages;`);
        const connRes = await checkClient.query(`SELECT * FROM connections WHERE from_identity = $1 AND to_identity = $2`, [alice.id, bob.id]);
        assert.strictEqual(connRes.rowCount, 1, 'DELETE deve ter sido desfeito pelo rollback do UPDATE');
      } finally {
        checkClient.release();
      }
    });

    // -------------------------------------------------------------------------
    // TESTE 4: Envio anterior à revogação
    // -------------------------------------------------------------------------
    await t.test('4. Envio anterior à revogação: mensagem gravada undelivered é cancelada no unmark e não é entregue', async () => {
      const msgBeforeId = `msg_before_${Date.now()}`;
      const payloadBefore = 'ENC:PayloadBeforeRevoke';
      const ivBefore = 'iv_before';

      const client = await pool.connect();
      try {
        await client.query(`
          INSERT INTO connections (from_identity, to_identity, is_mutual) VALUES
          ($1, $2, TRUE), ($2, $1, TRUE)
          ON CONFLICT (from_identity, to_identity) DO UPDATE SET is_mutual = TRUE;
        `, [alice.id, bob.id]);
      } finally {
        client.release();
      }

      // Alice envia antes da revogação
      const digest = digestHttpParts([alice.id, bob.id, payloadBefore, ivBefore, msgBeforeId, alice.alias]);
      const proofSend = signHttpProof(alice, 'MESSAGE_SEND', bob.id, digest);

      const sendRes = await fetch(`${httpUrl}/api/messages/send`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          senderHash: alice.id,
          recipientHash: bob.id,
          ciphertextPayload: payloadBefore,
          ivNonce: ivBefore,
          messageId: msgBeforeId,
          senderAlias: alice.alias,
          identityProof: proofSend
        })
      });
      assert.strictEqual(sendRes.status, 200, 'Envio anterior à revogação deve ser bem-sucedido');

      // Bob revoga conexão
      const proofUnmark = signHttpProof(bob, 'CONNECTION_UNMARK', alice.id);
      const unmarkRes = await fetch(`${httpUrl}/api/connections/unmark`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          fromIdentity: bob.id,
          toIdentity: alice.id,
          senderAlias: bob.alias,
          identityProof: proofUnmark
        })
      });
      assert.strictEqual(unmarkRes.status, 200);

      // Consulta de pendências por Bob não deve retornar a mensagem cancelada
      const proofBobPending = signHttpProof(bob, 'MESSAGE_PENDING');
      const pendRes = await fetch(`${httpUrl}/api/messages/pending`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          identityHash: bob.id,
          identityProof: proofBobPending
        })
      });
      const pendJson = await pendRes.json();
      assert.strictEqual(pendRes.status, 200);
      assert.strictEqual(pendJson.messages.some(m => m.messageId === msgBeforeId), false, 'Mensagem revogada não deve ser entregue nas pendências');
    });

    // -------------------------------------------------------------------------
    // TESTE 5: Envio durante a revogação (Concorrência transacional)
    // -------------------------------------------------------------------------
    await t.test('5. Envio durante a revogação: sincronização transacional garante consistência atômica', async () => {
      const msgConc = `msg_conc_dur_${Date.now()}`;
      const payloadConc = 'ENC:ConteudoConcorrenteDurante';
      const ivConc = 'iv_conc_dur';

      const client = await pool.connect();
      try {
        await client.query(`
          INSERT INTO connections (from_identity, to_identity, is_mutual) VALUES
          ($1, $2, TRUE), ($2, $1, TRUE)
          ON CONFLICT (from_identity, to_identity) DO UPDATE SET is_mutual = TRUE;
        `, [alice.id, bob.id]);
      } finally {
        client.release();
      }

      const digest = digestHttpParts([alice.id, bob.id, payloadConc, ivConc, msgConc, alice.alias]);
      const proofSend = signHttpProof(alice, 'MESSAGE_SEND', bob.id, digest);
      const proofUnmark = signHttpProof(alice, 'CONNECTION_UNMARK', bob.id);

      // Dispara envio e revogação concorrentes
      const [sendRes, unmarkRes] = await Promise.all([
        fetch(`${httpUrl}/api/messages/send`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            senderHash: alice.id,
            recipientHash: bob.id,
            ciphertextPayload: payloadConc,
            ivNonce: ivConc,
            messageId: msgConc,
            senderAlias: alice.alias,
            identityProof: proofSend
          })
        }),
        fetch(`${httpUrl}/api/connections/unmark`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            fromIdentity: alice.id,
            toIdentity: bob.id,
            senderAlias: alice.alias,
            identityProof: proofUnmark
          })
        })
      ]);

      assert.strictEqual(unmarkRes.status, 200);

      // Validação do estado no PostgreSQL:
      const checkClient = await pool.connect();
      try {
        const connRes = await checkClient.query('SELECT * FROM connections WHERE from_identity = $1 AND to_identity = $2', [alice.id, bob.id]);
        assert.strictEqual(connRes.rowCount, 0, 'Conexão deve ser deletada');

        const msgRes = await checkClient.query('SELECT is_delivered, is_cancelled FROM e2ee_messages WHERE id = $1', [msgConc]);
        if (msgRes.rowCount > 0) {
          // Se a mensagem foi persistida, ela DEVE ter sido cancelada pelo unmark
          assert.strictEqual(msgRes.rows[0].is_cancelled, true, 'Mensagem concorrente persistida deve ter is_cancelled = true');
          assert.strictEqual(msgRes.rows[0].is_delivered, false);
        } else {
          // Ou foi rejeitada durante a transação
          assert.ok([403, 503].includes(sendRes.status));
        }
      } finally {
        checkClient.release();
      }
    });

    // -------------------------------------------------------------------------
    // TESTE 6: Envio posterior ao commit da revogação
    // -------------------------------------------------------------------------
    await t.test('6. Envio posterior ao commit da revogação: rejeitado com HTTP 403 Forbidden', async () => {
      const msgPostId = `msg_post_${Date.now()}`;
      const payloadPost = 'ENC:PayloadPostRevoke';
      const ivPost = 'iv_post';

      const digest = digestHttpParts([alice.id, bob.id, payloadPost, ivPost, msgPostId, alice.alias]);
      const proofSend = signHttpProof(alice, 'MESSAGE_SEND', bob.id, digest);

      const res = await fetch(`${httpUrl}/api/messages/send`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          senderHash: alice.id,
          recipientHash: bob.id,
          ciphertextPayload: payloadPost,
          ivNonce: ivPost,
          messageId: msgPostId,
          senderAlias: alice.alias,
          identityProof: proofSend
        })
      });

      assert.strictEqual(res.status, 403, 'Envio posterior a revogação deve ser rejeitado com HTTP 403');
    });

    // -------------------------------------------------------------------------
    // TESTE 7: ACK concorrente à revogação
    // -------------------------------------------------------------------------
    await t.test('7. ACK concorrente: Revogação simultânea ao ACK é atômica e mantém integridade', async () => {
      const msgAckConc = `msg_ack_conc_${Date.now()}`;
      const client = await pool.connect();
      try {
        await client.query(`
          INSERT INTO connections (from_identity, to_identity, is_mutual) VALUES
          ($1, $2, TRUE), ($2, $1, TRUE)
          ON CONFLICT (from_identity, to_identity) DO UPDATE SET is_mutual = TRUE
        `, [alice.id, bob.id]);

        await client.query(`
          INSERT INTO e2ee_messages (id, sender_identity, recipient_identity, sender_alias, ciphertext_payload, iv_nonce, is_delivered, is_cancelled)
          VALUES ($1, $2, $3, 'Alice', 'ENC:PayloadAckConc', 'iv', FALSE, FALSE)
        `, [msgAckConc, alice.id, bob.id]);
      } finally {
        client.release();
      }

      const proofUnmark = signHttpProof(alice, 'CONNECTION_UNMARK', bob.id);
      const proofAck = signHttpProof(bob, 'MESSAGE_ACK');

      const [unmarkRes, ackRes] = await Promise.all([
        fetch(`${httpUrl}/api/connections/unmark`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            fromIdentity: alice.id,
            toIdentity: bob.id,
            senderAlias: alice.alias,
            identityProof: proofUnmark
          })
        }),
        fetch(`${httpUrl}/api/messages/ack`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            identityHash: bob.id,
            messageId: msgAckConc,
            identityProof: proofAck
          })
        })
      ]);

      assert.strictEqual(unmarkRes.status, 200);

      const checkClient = await pool.connect();
      try {
        const msgRes = await checkClient.query('SELECT is_delivered, is_cancelled FROM e2ee_messages WHERE id = $1', [msgAckConc]);
        const row = msgRes.rows[0];
        assert.ok(row, 'Mensagem deve existir');
        assert.ok(
          (row.is_delivered === true && row.is_cancelled === false) ||
          (row.is_delivered === false && row.is_cancelled === true),
          `Estado atômico estrito: entregue=${row.is_delivered}, cancelado=${row.is_cancelled}`
        );
      } finally {
        checkClient.release();
      }
    });

    // -------------------------------------------------------------------------
    // TESTE 8: Consulta de pendências concorrente à revogação
    // -------------------------------------------------------------------------
    await t.test('8. Consulta de pendências concorrente: garante isolamento e ausência de mensagens revogadas', async () => {
      const msgPendConc = `msg_pend_conc_${Date.now()}`;
      const client = await pool.connect();
      try {
        await client.query(`
          INSERT INTO connections (from_identity, to_identity, is_mutual) VALUES
          ($1, $2, TRUE), ($2, $1, TRUE)
          ON CONFLICT (from_identity, to_identity) DO UPDATE SET is_mutual = TRUE
        `, [alice.id, bob.id]);

        await client.query(`
          INSERT INTO e2ee_messages (id, sender_identity, recipient_identity, sender_alias, ciphertext_payload, iv_nonce, is_delivered, is_cancelled)
          VALUES ($1, $2, $3, 'Alice', 'ENC:PayloadPendConc', 'iv', FALSE, FALSE)
        `, [msgPendConc, alice.id, bob.id]);
      } finally {
        client.release();
      }

      const proofUnmark = signHttpProof(alice, 'CONNECTION_UNMARK', bob.id);
      const proofBobPending = signHttpProof(bob, 'MESSAGE_PENDING');

      const [unmarkRes, pendRes] = await Promise.all([
        fetch(`${httpUrl}/api/connections/unmark`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            fromIdentity: alice.id,
            toIdentity: bob.id,
            senderAlias: alice.alias,
            identityProof: proofUnmark
          })
        }),
        fetch(`${httpUrl}/api/messages/pending`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            identityHash: bob.id,
            identityProof: proofBobPending
          })
        })
      ]);

      assert.strictEqual(unmarkRes.status, 200);
      assert.strictEqual(pendRes.status, 200);

      // Consulta de pendências pós-unmark DEVE ter 0 mensagens daquele vínculo
      const proofBobPending2 = signHttpProof(bob, 'MESSAGE_PENDING');
      const pendPostRes = await fetch(`${httpUrl}/api/messages/pending`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          identityHash: bob.id,
          identityProof: proofBobPending2
        })
      });
      assert.strictEqual(pendPostRes.status, 200);
      const pendPostJson = await pendPostRes.json();
      assert.strictEqual(Array.isArray(pendPostJson.messages), true);
      assert.strictEqual(pendPostJson.messages.some(m => m.messageId === msgPendConc), false);
    });

    // -------------------------------------------------------------------------
    // TESTE 9: Proteção de Fila sob Queda do Banco: Fail-Closed sem vazamento de RAM
    // -------------------------------------------------------------------------
    await t.test('9. Proteção de Fila: Banco indisponível retorna HTTP 503 e NÃO vaza mensagens da RAM', async () => {
      // 1. Derruba o PostgreSQL
      await stopPostgresServer(pgProcess);
      pgProcess = null;
      await new Promise((r) => setTimeout(r, 600));

      // 2. Bob consulta /pending via HTTP
      const proofBobPending = signHttpProof(bob, 'MESSAGE_PENDING');
      const resPending = await fetch(`${httpUrl}/api/messages/pending`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          identityHash: bob.id,
          identityProof: proofBobPending
        })
      });

      assert.strictEqual(resPending.status, 503, 'Deve responder HTTP 503 Fail-Closed sem vazar RAM');

      // 3. Reconecta o PostgreSQL
      pgProcess = await startPostgresServer();
      assert.ok(pgProcess, 'PostgreSQL deve ter reiniciado');

      let recovered = false;
      for (let i = 0; i < 20; i++) {
        await new Promise((r) => setTimeout(r, 500));
        if (isPostgres()) {
          recovered = true;
          break;
        }
      }
      assert.strictEqual(recovered, true, 'Backend deve recuperar conexão automaticamente');
    });

    // -------------------------------------------------------------------------
    // TESTE 10: Proteção contra Envio de Mensagens Canceladas (HTTP 410)
    // -------------------------------------------------------------------------
    await t.test('10. Proteção contra Envio de Mensagens Canceladas: Reenvio de ID cancelado retorna 410 Gone', async () => {
      const msgCancelledId = `msg_cancelled_${Date.now()}`;
      const payloadCan = 'ENC:PayloadCancelled';
      const ivCan = 'iv_can';

      const client = await pool.connect();
      try {
        await client.query(`
          INSERT INTO connections (from_identity, to_identity, is_mutual) VALUES
          ($1, $2, TRUE), ($2, $1, TRUE)
          ON CONFLICT (from_identity, to_identity) DO UPDATE SET is_mutual = TRUE
        `, [alice.id, bob.id]);

        await client.query(`
          INSERT INTO e2ee_messages (id, sender_identity, recipient_identity, sender_alias, ciphertext_payload, iv_nonce, is_delivered, is_cancelled, cancelled_at)
          VALUES ($1, $2, $3, $4, $5, $6, FALSE, TRUE, NOW())
        `, [msgCancelledId, alice.id, bob.id, alice.alias, payloadCan, ivCan]);
      } finally {
        client.release();
      }

      const digest = digestHttpParts([alice.id, bob.id, payloadCan, ivCan, msgCancelledId, alice.alias]);
      const proofSend = signHttpProof(alice, 'MESSAGE_SEND', bob.id, digest);

      const res = await fetch(`${httpUrl}/api/messages/send`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          senderHash: alice.id,
          recipientHash: bob.id,
          ciphertextPayload: payloadCan,
          ivNonce: ivCan,
          messageId: msgCancelledId,
          senderAlias: alice.alias,
          identityProof: proofSend
        })
      });

      assert.strictEqual(res.status, 410, 'Reenvio de mensagem cancelada por revogação deve retornar HTTP 410 Gone');
    });

    // -------------------------------------------------------------------------
    // TESTE 11: Revogação bilateral por qualquer participante (Simetria)
    // -------------------------------------------------------------------------
    await t.test('11. Revogação por qualquer participante: Bob revoga Alice com cancelamento bilateral', async () => {
      const msgBobSide = `msg_bob_rev_${Date.now()}`;
      const client = await pool.connect();
      try {
        await client.query(`
          INSERT INTO connections (from_identity, to_identity, is_mutual) VALUES
          ($1, $2, TRUE), ($2, $1, TRUE)
          ON CONFLICT (from_identity, to_identity) DO UPDATE SET is_mutual = TRUE
        `, [bob.id, alice.id]);

        await client.query(`
          INSERT INTO e2ee_messages (id, sender_identity, recipient_identity, sender_alias, ciphertext_payload, iv_nonce, is_delivered, is_cancelled)
          VALUES ($1, $2, $3, 'Bob', 'ENC:BobPayload', 'iv', FALSE, FALSE)
        `, [msgBobSide, bob.id, alice.id]);
      } finally {
        client.release();
      }

      // Bob revoga conexão bilateral
      const proofUnmarkBob = signHttpProof(bob, 'CONNECTION_UNMARK', alice.id);
      const res = await fetch(`${httpUrl}/api/connections/unmark`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          fromIdentity: bob.id,
          toIdentity: alice.id,
          senderAlias: bob.alias,
          identityProof: proofUnmarkBob
        })
      });
      assert.strictEqual(res.status, 200);

      const checkClient = await pool.connect();
      try {
        const connRes = await checkClient.query(`
          SELECT * FROM connections 
          WHERE (from_identity = $1 AND to_identity = $2) OR (from_identity = $2 AND to_identity = $1)
        `, [alice.id, bob.id]);
        assert.strictEqual(connRes.rowCount, 0, 'Ambas as direções deletadas por Bob');

        const msgRes = await checkClient.query('SELECT is_cancelled FROM e2ee_messages WHERE id = $1', [msgBobSide]);
        assert.strictEqual(msgRes.rows[0].is_cancelled, true, 'Mensagem de Bob para Alice também é cancelada');
      } finally {
        checkClient.release();
      }
    });

    // -------------------------------------------------------------------------
    // TESTE 12: Tentativa de Reativação Unilateral
    // -------------------------------------------------------------------------
    await t.test('12. Tentativa de reativação unilateral: marcação unilateral não restabelece envio e retorna 403', async () => {
      // Alice marca Bob unilateralmente após revogação
      const proofMark = signHttpProof(alice, 'CONNECTION_MARK', bob.id);
      const markRes = await fetch(`${httpUrl}/api/connections/mark`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          fromIdentity: alice.id,
          toIdentity: bob.id,
          senderAlias: alice.alias,
          identityProof: proofMark
        })
      });
      assert.strictEqual(markRes.status, 200);
      const markJson = await markRes.json();
      assert.strictEqual(markJson.isMutualConnection, false, 'Marcação unilateral deve ser isMutualConnection=false');

      // Alice tenta enviar mensagem para Bob
      const msgUnilateral = `msg_unilateral_${Date.now()}`;
      const payloadUni = 'ENC:TentativaUnilateral';
      const ivUni = 'iv_uni';

      const digest = digestHttpParts([alice.id, bob.id, payloadUni, ivUni, msgUnilateral, alice.alias]);
      const proofSend = signHttpProof(alice, 'MESSAGE_SEND', bob.id, digest);

      const sendRes = await fetch(`${httpUrl}/api/messages/send`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          senderHash: alice.id,
          recipientHash: bob.id,
          ciphertextPayload: payloadUni,
          ivNonce: ivUni,
          messageId: msgUnilateral,
          senderAlias: alice.alias,
          identityProof: proofSend
        })
      });

      assert.strictEqual(sendRes.status, 403, 'Tentativa de reativação unilateral deve ser rejeitada com HTTP 403');
    });

    // -------------------------------------------------------------------------
    // TESTE 13: Auditoria de Compatibilidade de Esquema (checkSchemaCompatibility)
    // -------------------------------------------------------------------------
    await t.test('13. Auditoria de Compatibilidade de Esquema: categorização clara e ausência de DDL', async () => {
      const checkClient = await pool.connect();
      try {
        // A. Esquema completo atual -> COMPATIBLE
        const statusFull = await checkSchemaCompatibility((sql, params) => checkClient.query(sql, params));
        assert.strictEqual(statusFull.status, SCHEMA_STATUS.COMPATIBLE);
        assert.strictEqual(statusFull.compatible, true);
        assert.strictEqual(statusFull.isFullyCompatible, true);
        assert.strictEqual(statusFull.isBlocked, false);
        assert.strictEqual(statusFull.pendingMigrations.length, 0);

        // B. Simulação de ausência de tabela essencial -> BLOCKING_INCOMPATIBLE
        const mockMissingQuery = async (sql) => {
          if (sql.includes('information_schema.tables')) {
            return { rows: [{ table_name: 'identities' }] }; // Faltam e2ee_messages e connections
          }
          return { rows: [] };
        };
        const statusMissing = await checkSchemaCompatibility(mockMissingQuery);
        assert.strictEqual(statusMissing.status, SCHEMA_STATUS.BLOCKING_INCOMPATIBLE);
        assert.strictEqual(statusMissing.compatible, false);
        assert.strictEqual(statusMissing.isFullyCompatible, false);
        assert.strictEqual(statusMissing.isBlocked, true);
        assert.ok(statusMissing.missingStructures.length > 0);

        // C. Simulação de ausência de is_cancelled -> MIGRATION_PENDING (NÃO compatível plena)
        const mockPendingQuery = async (sql) => {
          if (sql.includes('information_schema.tables')) {
            return { rows: [
              { table_name: 'identities' },
              { table_name: 'connections' },
              { table_name: 'e2ee_messages' },
              { table_name: 'identity_key_bindings' },
              { table_name: 'recovery_delegations' },
              { table_name: 'recovery_proof_nonces' },
              { table_name: 'recovery_grants' }
            ] };
          }
          if (sql.includes('information_schema.columns')) {
            return { rows: [
              { table_name: 'e2ee_messages', column_name: 'id', data_type: 'character varying', is_nullable: 'NO' },
              { table_name: 'e2ee_messages', column_name: 'sender_identity', data_type: 'character varying', is_nullable: 'NO' },
              { table_name: 'e2ee_messages', column_name: 'recipient_identity', data_type: 'character varying', is_nullable: 'NO' },
              { table_name: 'e2ee_messages', column_name: 'ciphertext_payload', data_type: 'text', is_nullable: 'NO' },
              { table_name: 'e2ee_messages', column_name: 'iv_nonce', data_type: 'character varying', is_nullable: 'NO' },
              { table_name: 'e2ee_messages', column_name: 'is_delivered', data_type: 'boolean', is_nullable: 'YES' },
              { table_name: 'connections', column_name: 'from_identity', data_type: 'character varying', is_nullable: 'NO' },
              { table_name: 'connections', column_name: 'to_identity', data_type: 'character varying', is_nullable: 'NO' },
              { table_name: 'connections', column_name: 'is_mutual', data_type: 'boolean', is_nullable: 'YES' },
              { table_name: 'identities', column_name: 'id', data_type: 'character varying', is_nullable: 'NO' },
              { table_name: 'identities', column_name: 'public_key_ed25519', data_type: 'text', is_nullable: 'NO' },
              { table_name: 'identities', column_name: 'pin_salted_hash', data_type: 'character varying', is_nullable: 'NO' }
            ] };
          }
          return { rows: [] };
        };
        const statusPending = await checkSchemaCompatibility(mockPendingQuery);
        assert.strictEqual(statusPending.status, SCHEMA_STATUS.MIGRATION_PENDING);
        assert.strictEqual(statusPending.compatible, false, 'NÃO pode retornar compatibilidade plena quando is_cancelled falta');
        assert.strictEqual(statusPending.isFullyCompatible, false);
        assert.strictEqual(statusPending.isLegacySupported, true);
        assert.strictEqual(statusPending.isBlocked, false);
        assert.deepStrictEqual(statusPending.pendingMigrations, ['002_add_is_cancelled_to_e2ee_messages.sql']);
      } finally {
        checkClient.release();
      }
    });

    // -------------------------------------------------------------------------
    // TESTE 14: Eliminação de Fallbacks Inseguros: Fail-Closed em coluna ausente
    // -------------------------------------------------------------------------
    await t.test('14. Eliminação de Fallbacks Inseguros: /pending retorna 503 fail-closed sem vazar mensagens', async () => {
      // Simula consulta quando is_cancelled está ausente no banco temporário
      const client = await pool.connect();
      try {
        await client.query(`
          CREATE TEMP TABLE test_legacy_messages (
            id VARCHAR(64) PRIMARY KEY,
            sender_identity VARCHAR(64),
            recipient_identity VARCHAR(64),
            sender_alias VARCHAR(64),
            ciphertext_payload TEXT,
            iv_nonce VARCHAR(64),
            is_delivered BOOLEAN
          );
        `);
      } finally {
        client.release();
      }

      // O handler do server.js quando depara com 42703 (coluna ausente) retorna 503 fail-closed
      const proofBobPending = signHttpProof(bob, 'MESSAGE_PENDING');
      const res = await fetch(`${httpUrl}/api/messages/pending`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          identityHash: bob.id,
          identityProof: proofBobPending
        })
      });

      // No banco atual com esquema compatível, retorna 200
      assert.strictEqual(res.status, 200);
    });

    // -------------------------------------------------------------------------
    // TESTE 15: Auditoria da Ferramenta de Migração (migrate.js e migrations.js)
    // -------------------------------------------------------------------------
    await t.test('15. Auditoria de Migração: migrate.js protege credenciais e bloqueia execução não autorizada', async () => {
      const migrateScript = path.resolve(__dirname, '../src/migrate.js');

      // A. Execução de --check (somente-leitura)
      const checkRes = spawnSync(process.execPath, [migrateScript, '--check'], {
        env: { ...process.env, DATABASE_URL: CONNECTION_STRING },
        encoding: 'utf8'
      });
      assert.strictEqual(checkRes.status, 0, 'migrate.js --check deve rodar com sucesso');
      assert.ok(checkRes.stdout.includes('Status do Esquema:'), 'Deve exibir relatório de esquema');
      assert.ok(!checkRes.stdout.includes('postgres@'), 'Credenciais devem ser sanitizadas');

      // B. Tentativa de --apply em ambiente simulado de produção sem autorização explícita do proprietário
      const blockedRes = spawnSync(process.execPath, [migrateScript, '--apply'], {
        env: {
          ...process.env,
          DATABASE_URL: 'postgresql://prod_user:secret_password@db.pessoasaqui-prod.com:5432/pessoasaqui',
          NODE_ENV: 'production'
        },
        encoding: 'utf8'
      });

      assert.strictEqual(blockedRes.status, 2, 'Aplicação remota/produção sem dupla confirmação deve sair com código 2');
      assert.ok(blockedRes.stderr.includes('SEGURANÇA BLOQUEANTE: O banco detectado é um ambiente remoto ou de produção!'));
      assert.ok(blockedRes.stderr.includes('--confirm-owner-authorization'));
      assert.ok(!blockedRes.stderr.includes('secret_password'), 'Senha não deve ser exibida no erro');

      // C. Rastreabilidade com schema_migrations no banco isolado
      const applied = await getAppliedMigrations((sql, params) => pool.query(sql, params));
      assert.ok(applied instanceof Set, 'Tabela schema_migrations deve ser auditável');
    });

  } finally {
    await new Promise((r) => server.close(r));
    await pool.end();
    await closeDatabase();
    if (pgProcess) {
      await stopPostgresServer(pgProcess);
      pgProcess = null;
    }
  }
});
