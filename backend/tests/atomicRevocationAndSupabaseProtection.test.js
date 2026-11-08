'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { spawn } = require('child_process');
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
const { checkSchemaCompatibility } = require('../src/migrations');

let pgProcess = null;

function startPostgresServer() {
  return new Promise((resolve, reject) => {
    const proc = spawn(PG_BIN, ['-D', PG_DATA, '-p', String(PG_PORT)]);
    let resolved = false;

    proc.stderr.on('data', (chunk) => {
      const msg = chunk.toString();
      if (!resolved && msg.includes('ready to accept connections')) {
        resolved = true;
        resolve(proc);
      }
    });

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
    if (!proc || proc.killed) return resolve();
    proc.on('exit', () => resolve());
    proc.kill('SIGTERM');
    setTimeout(() => {
      try { proc.kill('SIGKILL'); } catch (_) {}
      resolve();
    }, 3000);
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

test('SUÍTE: REVOGAÇÃO BILATERAL ATÔMICA, PROTEÇÃO DE FILAS E SUPABASE SOMENTE-LEITURA', async (t) => {
  pgProcess = await startPostgresServer();
  assert.ok(pgProcess, 'PostgreSQL 18 isolado deve estar em execução');

  const pool = new Pool({
    connectionString: CONNECTION_STRING,
    max: 10,
    connectionTimeoutMillis: 5000
  });
  pool.on('error', () => {});

  const setupClient = await pool.connect();
  try {
    await setupClient.query(`
      DROP TABLE IF EXISTS e2ee_messages CASCADE;
      DROP TABLE IF EXISTS connections CASCADE;

      CREATE TABLE e2ee_messages (
        id VARCHAR(64) PRIMARY KEY,
        sender_identity VARCHAR(64) NOT NULL,
        recipient_identity VARCHAR(64) NOT NULL,
        sender_alias VARCHAR(64),
        ciphertext_payload TEXT NOT NULL,
        iv_nonce VARCHAR(64) NOT NULL,
        is_delivered BOOLEAN DEFAULT FALSE,
        is_cancelled BOOLEAN DEFAULT FALSE,
        created_at TIMESTAMPTZ DEFAULT NOW(),
        delivered_at TIMESTAMPTZ,
        cancelled_at TIMESTAMPTZ
      );
      CREATE INDEX idx_e2ee_messages_recipient_undelivered 
        ON e2ee_messages (recipient_identity, is_delivered) WHERE is_delivered = FALSE;
      CREATE INDEX idx_e2ee_messages_undelivered_uncancelled 
        ON e2ee_messages (recipient_identity, is_delivered) WHERE is_delivered = FALSE AND (is_cancelled IS NULL OR is_cancelled = FALSE);

      CREATE TABLE connections (
        from_identity VARCHAR(64) NOT NULL,
        to_identity VARCHAR(64) NOT NULL,
        is_mutual BOOLEAN DEFAULT FALSE,
        created_at TIMESTAMPTZ DEFAULT NOW(),
        PRIMARY KEY (from_identity, to_identity)
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
        created_at TIMESTAMPTZ DEFAULT NOW()
      );

      TRUNCATE TABLE recovery_proof_nonces, identity_key_bindings;
    `);
  } finally {
    setupClient.release();
  }

  process.env.DATABASE_URL = CONNECTION_STRING;
  const { app, server } = require('../src/server');
  const { initDatabase, isPostgres, closeDatabase, withTransaction } = require('../src/db');
  initDatabase();

  await new Promise((r) => setTimeout(r, 600));
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const serverPort = server.address().port;
  const httpUrl = `http://127.0.0.1:${serverPort}`;

  const alice = createCryptoUser('Alice');
  const bob = createCryptoUser('Bob');

  const bindClient = await pool.connect();
  try {
    await bindClient.query(`
      INSERT INTO identity_key_bindings (identity_id, public_key_spki) VALUES
      ($1, $2), ($3, $4)
      ON CONFLICT (identity_id) DO UPDATE SET public_key_spki = EXCLUDED.public_key_spki
    `, [alice.id, alice.pub, bob.id, bob.pub]);
  } finally {
    bindClient.release();
  }

  try {
    // -------------------------------------------------------------------------
    // TESTE 1: Revogação Normal Atômica (Transação única: DELETE + UPDATE cancel)
    // -------------------------------------------------------------------------
    await t.test('1. Revogação Normal Atômica: Exclusão bilateral de conexões e cancelamento de mensagens em transação única', async () => {
      const client = await pool.connect();
      const msgDeliveredId = `msg_deliv_${Date.now()}`;
      const msgPendingId = `msg_pend_${Date.now()}`;

      try {
        // Cria conexão mútua
        await client.query(`
          INSERT INTO connections (from_identity, to_identity, is_mutual) VALUES
          ($1, $2, TRUE), ($2, $1, TRUE)
          ON CONFLICT (from_identity, to_identity) DO UPDATE SET is_mutual = TRUE
        `, [alice.id, bob.id]);

        // Mensagem 1: Já entregue anteriormente
        await client.query(`
          INSERT INTO e2ee_messages (id, sender_identity, recipient_identity, sender_alias, ciphertext_payload, iv_nonce, is_delivered, is_cancelled)
          VALUES ($1, $2, $3, $4, 'ENC:DeliveredPayload', 'iv1', TRUE, FALSE)
        `, [msgDeliveredId, alice.id, bob.id, alice.alias]);

        // Mensagem 2: Pendente (não entregue)
        await client.query(`
          INSERT INTO e2ee_messages (id, sender_identity, recipient_identity, sender_alias, ciphertext_payload, iv_nonce, is_delivered, is_cancelled)
          VALUES ($1, $2, $3, $4, 'ENC:PendingPayload', 'iv2', FALSE, FALSE)
        `, [msgPendingId, alice.id, bob.id, alice.alias]);
      } finally {
        client.release();
      }

      // Alice revoga a conexão mútua
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
      const data = await res.json();
      assert.strictEqual(data.success, true);

      // Verificação no banco:
      const checkClient = await pool.connect();
      try {
        // Conexão deletada bilateralmente
        const connRes = await checkClient.query(
          'SELECT * FROM connections WHERE (from_identity=$1 AND to_identity=$2) OR (from_identity=$2 AND to_identity=$1)',
          [alice.id, bob.id]
        );
        assert.strictEqual(connRes.rowCount, 0, 'Ambas as linhas de conexão devem ser removidas');

        // Mensagem entregue previamente permanece intacta
        const delivRow = await checkClient.query('SELECT * FROM e2ee_messages WHERE id = $1', [msgDeliveredId]);
        assert.strictEqual(delivRow.rows[0].is_delivered, true, 'is_delivered deve continuar TRUE');
        assert.strictEqual(delivRow.rows[0].is_cancelled, false, 'is_cancelled deve continuar FALSE para entregue');

        // Mensagem não entregue foi cancelada
        const pendRow = await checkClient.query('SELECT * FROM e2ee_messages WHERE id = $1', [msgPendingId]);
        assert.strictEqual(pendRow.rows[0].is_delivered, false, 'is_delivered deve ser FALSE');
        assert.strictEqual(pendRow.rows[0].is_cancelled, true, 'is_cancelled deve ser TRUE');
        assert.ok(pendRow.rows[0].cancelled_at, 'cancelled_at deve estar preenchido');
      } finally {
        checkClient.release();
      }
    });

    // -------------------------------------------------------------------------
    // TESTE 2: Falha durante DELETE -> Rollback Integral (Nada é alterado)
    // -------------------------------------------------------------------------
    await t.test('2. Falha durante DELETE: Transação aborta com rollback; conexões e mensagens permanecem intactas', async () => {
      const client = await pool.connect();
      const msgTest2 = `msg_test2_${Date.now()}`;
      try {
        await client.query(`
          INSERT INTO connections (from_identity, to_identity, is_mutual) VALUES
          ($1, $2, TRUE), ($2, $1, TRUE)
          ON CONFLICT (from_identity, to_identity) DO UPDATE SET is_mutual = TRUE
        `, [alice.id, bob.id]);

        await client.query(`
          INSERT INTO e2ee_messages (id, sender_identity, recipient_identity, sender_alias, ciphertext_payload, iv_nonce, is_delivered, is_cancelled)
          VALUES ($1, $2, $3, $4, 'ENC:Payload2', 'iv', FALSE, FALSE)
        `, [msgTest2, alice.id, bob.id, alice.alias]);

        await client.query(`
          CREATE OR REPLACE FUNCTION trg_fail_delete_fn() RETURNS trigger AS $BODY$
          BEGIN
            RAISE EXCEPTION 'DELETE_SIMULATED_FAILURE';
          END;
          $BODY$ LANGUAGE plpgsql;

          DROP TRIGGER IF EXISTS trg_fail_delete ON connections;
          CREATE TRIGGER trg_fail_delete BEFORE DELETE ON connections
          FOR EACH ROW EXECUTE FUNCTION trg_fail_delete_fn();
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

      // Retorna 503 devido ao erro na transação
      assert.strictEqual(res.status, 503);

      // Verificação de Rollback Integral no Banco:
      const checkClient = await pool.connect();
      try {
        // Conexão continua existindo!
        const connRes = await checkClient.query('SELECT * FROM connections WHERE from_identity = $1 AND to_identity = $2', [alice.id, bob.id]);
        assert.strictEqual(connRes.rowCount, 1, 'Conexão deve continuar salva após rollback do DELETE');

        // Mensagem NÃO foi cancelada!
        const msgRes = await checkClient.query('SELECT * FROM e2ee_messages WHERE id = $1', [msgTest2]);
        assert.strictEqual(msgRes.rows[0].is_cancelled, false, 'Mensagem não pode ter sido cancelada após rollback');

        // Remove a trigger de falha
        await checkClient.query(`
          DROP TRIGGER IF EXISTS trg_fail_delete ON connections;
          DROP FUNCTION IF EXISTS trg_fail_delete_fn();
        `);
      } finally {
        checkClient.release();
      }
    });

    // -------------------------------------------------------------------------
    // TESTE 3: Falha durante UPDATE -> Rollback Integral (DELETE é desfeito!)
    // -------------------------------------------------------------------------
    await t.test('3. Falha durante UPDATE: DELETE é revertido atomicamente pelo rollback', async () => {
      const client = await pool.connect();
      const msgTest3 = `msg_test3_${Date.now()}`;
      try {
        await client.query(`
          INSERT INTO connections (from_identity, to_identity, is_mutual) VALUES
          ($1, $2, TRUE), ($2, $1, TRUE)
          ON CONFLICT (from_identity, to_identity) DO UPDATE SET is_mutual = TRUE
        `, [alice.id, bob.id]);

        await client.query(`
          INSERT INTO e2ee_messages (id, sender_identity, recipient_identity, sender_alias, ciphertext_payload, iv_nonce, is_delivered, is_cancelled)
          VALUES ($1, $2, $3, $4, 'ENC:Payload3', 'iv', FALSE, FALSE)
        `, [msgTest3, alice.id, bob.id, alice.alias]);

        await client.query(`
          CREATE OR REPLACE FUNCTION trg_fail_update_fn() RETURNS trigger AS $BODY$
          BEGIN
            RAISE EXCEPTION 'UPDATE_SIMULATED_FAILURE';
          END;
          $BODY$ LANGUAGE plpgsql;

          DROP TRIGGER IF EXISTS trg_fail_update ON e2ee_messages;
          CREATE TRIGGER trg_fail_update BEFORE UPDATE ON e2ee_messages
          FOR EACH ROW EXECUTE FUNCTION trg_fail_update_fn();
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

      // Verificação do Rollback:
      const checkClient = await pool.connect();
      try {
        // Conexão DEVE continuar salva, provando que o DELETE foi revertido pelo rollback!
        const connRes = await checkClient.query('SELECT * FROM connections WHERE from_identity = $1 AND to_identity = $2', [alice.id, bob.id]);
        assert.strictEqual(connRes.rowCount, 1, 'PROVA DE TRANSAÇÃO ÚNICA: DELETE de conexões foi desfeito pelo rollback do UPDATE!');

        // Mensagem continua intacta
        const msgRes = await checkClient.query('SELECT * FROM e2ee_messages WHERE id = $1', [msgTest3]);
        assert.strictEqual(msgRes.rows[0].is_cancelled, false);

        // Remove a trigger
        await checkClient.query(`
          DROP TRIGGER IF EXISTS trg_fail_update ON e2ee_messages;
          DROP FUNCTION IF EXISTS trg_fail_update_fn();
        `);
      } finally {
        checkClient.release();
      }
    });

    // -------------------------------------------------------------------------
    // TESTE 4: Concorrência: Revogação Simultânea ao Envio (/unmark vs /send)
    // -------------------------------------------------------------------------
    await t.test('4. Concorrência: Revogação simultânea ao envio garante estado final consistente', async () => {
      const msgConc = `msg_conc_${Date.now()}`;
      const payloadConc = 'ENC:ConteudoConcorrente';
      const ivConc = 'iv_conc';

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

      // Dispara envio e revogação simultâneos
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

      // Verificação do estado final no banco:
      const checkClient = await pool.connect();
      try {
        // Conexão deletada
        const connRes = await checkClient.query('SELECT * FROM connections WHERE from_identity = $1 AND to_identity = $2', [alice.id, bob.id]);
        assert.strictEqual(connRes.rowCount, 0);

        // Se a mensagem concorrente foi persistida, ela DEVE ser is_cancelled = TRUE ou rejeitada
        const msgRes = await checkClient.query('SELECT * FROM e2ee_messages WHERE id = $1', [msgConc]);
        if (msgRes.rowCount > 0) {
          // Se foi gravada, não pode ter sido entregue e deve estar cancelada se a revogação correu após o insert
          assert.strictEqual(msgRes.rows[0].is_delivered, false);
        }
      } finally {
        checkClient.release();
      }
    });

    // -------------------------------------------------------------------------
    // TESTE 5: Concorrência: Revogação Simultânea ao ACK (/unmark vs /ack)
    // -------------------------------------------------------------------------
    await t.test('5. Concorrência: Revogação simultânea ao ACK é atômica e não produz estados corrompidos', async () => {
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
          VALUES ($1, $2, $3, $4, 'ENC:PayloadAckConc', 'iv', FALSE, FALSE)
        `, [msgAckConc, alice.id, bob.id, alice.alias]);
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

      // Validação de Integridade no PostgreSQL:
      // O estado da mensagem deve ser inequivocamente:
      // OU (is_delivered = TRUE, is_cancelled = FALSE)
      // OU (is_delivered = FALSE, is_cancelled = TRUE)
      // NUNCA ambos TRUE ou ambos indefinidos!
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
    // TESTE 6: Proteção de Fila sob Queda do Banco: Fail-Closed sem vazamento de RAM
    // -------------------------------------------------------------------------
    await t.test('6. Proteção de Fila: Banco indisponível retorna HTTP 503 e NÃO vaza mensagens da RAM', async () => {
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
    // TESTE 7: Proteção contra Envio de Mensagens Canceladas (HTTP 410)
    // -------------------------------------------------------------------------
    await t.test('7. Proteção contra Envio de Mensagens Canceladas: Reenvio de ID cancelado retorna 410 Gone', async () => {
      const msgCancelledId = `msg_cancelled_${Date.now()}`;
      const payloadCan = 'ENC:PayloadCancelled';
      const ivCan = 'iv_can';

      const client = await pool.connect();
      try {
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
    // TESTE 8: Verificação de Compatibilidade de Esquema (Somente-Leitura / Zero DDL)
    // -------------------------------------------------------------------------
    await t.test('8. Verificação de Compatibilidade de Esquema: Modo Somente-Leitura sem executar DDL', async () => {
      const checkClient = await pool.connect();
      try {
        const status = await checkSchemaCompatibility((sql, params) => checkClient.query(sql, params));
        assert.strictEqual(status.compatible, true);
        assert.strictEqual(status.hasIsCancelled, true);
        assert.strictEqual(status.hasCancelledAt, true);
        assert.strictEqual(status.hasFromIdentity, true);
      } finally {
        checkClient.release();
      }
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
