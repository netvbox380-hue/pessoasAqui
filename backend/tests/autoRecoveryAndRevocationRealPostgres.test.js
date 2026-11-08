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

test('SUÍTE: RECUPERAÇÃO AUTOMÁTICA DO POSTGRESQL E REVOGAÇÃO TRANSACIONAL ÍNTEGRA', async (t) => {
  // 1. Inicia PostgreSQL 18 real isolado
  pgProcess = await startPostgresServer();
  assert.ok(pgProcess, 'PostgreSQL 18 deve estar ativo');

  const pool = new Pool({
    connectionString: CONNECTION_STRING,
    max: 10,
    connectionTimeoutMillis: 5000
  });
  pool.on('error', () => {}); // Previne unhandled rejections no pool de teste em quedas intencionais

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
      CREATE INDEX IF NOT EXISTS idx_e2ee_messages_recipient_undelivered 
        ON e2ee_messages (recipient_identity, is_delivered) WHERE is_delivered = FALSE;

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

  // 2. Configura e inicializa o backend real
  process.env.DATABASE_URL = CONNECTION_STRING;
  const { app, server } = require('../src/server');
  const { initDatabase, isPostgres, closeDatabase } = require('../src/db');
  initDatabase();

  await new Promise((r) => setTimeout(r, 600));

  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const serverPort = server.address().port;
  const httpUrl = `http://127.0.0.1:${serverPort}`;

  const alice = createCryptoUser('Alice');
  const bob = createCryptoUser('Bob');

  // Registra as chaves de identidade no banco
  const bindClient = await pool.connect();
  try {
    await bindClient.query(`
      INSERT INTO identity_key_bindings (identity_id, public_key_spki) VALUES
      ($1, $2), ($3, $4)
      ON CONFLICT (identity_id) DO UPDATE SET public_key_spki = EXCLUDED.public_key_spki
    `, [alice.id, alice.pub, bob.id, bob.pub]);

    await bindClient.query(`
      INSERT INTO connections (from_identity, to_identity, is_mutual) VALUES
      ($1, $2, TRUE), ($2, $1, TRUE)
      ON CONFLICT (from_identity, to_identity) DO UPDATE SET is_mutual = TRUE
    `, [alice.id, bob.id]);
  } finally {
    bindClient.release();
  }

  const msgId1 = `msg_auto_${Date.now()}_1`;
  const payload1 = 'ENC_ECDH:ConteudoAutoRecovery';
  const iv1 = 'iv_auto_1';

  try {
    // -------------------------------------------------------------------------
    // TESTE 1: Envio inicial com banco ativo -> Gravado no PostgreSQL
    // -------------------------------------------------------------------------
    await t.test('1. Envio inicial: mensagem gravada no banco com is_delivered = FALSE', async () => {
      const digest = digestHttpParts([alice.id, bob.id, payload1, iv1, msgId1, alice.alias]);
      const proof = signHttpProof(alice, 'MESSAGE_SEND', bob.id, digest);

      const res = await fetch(`${httpUrl}/api/messages/send`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          senderHash: alice.id,
          recipientHash: bob.id,
          ciphertextPayload: payload1,
          ivNonce: iv1,
          messageId: msgId1,
          senderAlias: alice.alias,
          identityProof: proof
        })
      });

      assert.strictEqual(res.status, 200);
      const data = await res.json();
      assert.strictEqual(data.success, true);

      const checkClient = await pool.connect();
      try {
        const row = await checkClient.query('SELECT * FROM e2ee_messages WHERE id = $1', [msgId1]);
        assert.strictEqual(row.rowCount, 1);
        assert.strictEqual(row.rows[0].is_delivered, false);
        assert.strictEqual(row.rows[0].is_cancelled, false);
      } finally {
        checkClient.release();
      }
    });

    // -------------------------------------------------------------------------
    // TESTE 2: Derrubada do PostgreSQL -> Backend retorna 503 Fail-Closed
    // -------------------------------------------------------------------------
    await t.test('2. Queda do PostgreSQL: Backend detecta falha e retorna HTTP 503 sem falso sucesso', async () => {
      await stopPostgresServer(pgProcess);
      pgProcess = null;
      await new Promise((r) => setTimeout(r, 600));

      const msgFail = `msg_fail_${Date.now()}`;
      const digestFail = digestHttpParts([alice.id, bob.id, payload1, iv1, msgFail, alice.alias]);
      const proofFail = signHttpProof(alice, 'MESSAGE_SEND', bob.id, digestFail);

      const res = await fetch(`${httpUrl}/api/messages/send`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          senderHash: alice.id,
          recipientHash: bob.id,
          ciphertextPayload: payload1,
          ivNonce: iv1,
          messageId: msgFail,
          senderAlias: alice.alias,
          identityProof: proofFail
        })
      });

      assert.strictEqual(res.status, 503, 'Deve retornar HTTP 503 durante indisponibilidade do banco');
    });

    // -------------------------------------------------------------------------
    // TESTE 3: Reinicialização do PostgreSQL -> Backend recupera automaticamente sem restart
    // -------------------------------------------------------------------------
    await t.test('3. Reconexão Automática: Banco volta e backend recupera conexão SEM REINICIAR O SERVIDOR', async () => {
      pgProcess = await startPostgresServer();
      assert.ok(pgProcess, 'PostgreSQL deve ter reiniciado');

      // Aguarda o mecanismo de auto-recovery do db.js tentar e confirmar conexão (intervalo base ~800ms)
      let recovered = false;
      for (let i = 0; i < 20; i++) {
        await new Promise((r) => setTimeout(r, 500));
        if (isPostgres()) {
          recovered = true;
          break;
        }
      }
      assert.strictEqual(recovered, true, 'Backend deve recuperar isPostgres() automaticamente via probe SELECT 1');
    });

    // -------------------------------------------------------------------------
    // TESTE 4: Reenvio da mensagem com mesmo ID -> Idempotência e ACK confirmado
    // -------------------------------------------------------------------------
    await t.test('4. Reenvio idempotente com mesmo ID pós-recuperação -> Persistência, ACK e sem duplicação', async () => {
      const digestRetry = digestHttpParts([alice.id, bob.id, payload1, iv1, msgId1, alice.alias]);
      const proofRetry = signHttpProof(alice, 'MESSAGE_SEND', bob.id, digestRetry);

      const res = await fetch(`${httpUrl}/api/messages/send`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          senderHash: alice.id,
          recipientHash: bob.id,
          ciphertextPayload: payload1,
          ivNonce: iv1,
          messageId: msgId1,
          senderAlias: alice.alias,
          identityProof: proofRetry
        })
      });

      assert.strictEqual(res.status, 200);
      const data = await res.json();
      assert.strictEqual(data.success, true);

      // Bob confirma recebimento via ACK HTTP
      const proofAck = signHttpProof(bob, 'MESSAGE_ACK');
      const ackRes = await fetch(`${httpUrl}/api/messages/ack`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          identityHash: bob.id,
          messageId: msgId1,
          identityProof: proofAck
        })
      });

      assert.strictEqual(ackRes.status, 200);
      const ackData = await ackRes.json();
      assert.strictEqual(ackData.success, true);

      // Validação final no banco: exatamente 1 registro, is_delivered = TRUE
      const checkClient = await pool.connect();
      try {
        const rows = await checkClient.query('SELECT * FROM e2ee_messages WHERE id = $1', [msgId1]);
        assert.strictEqual(rows.rowCount, 1, 'Não pode haver duplicatas no banco');
        assert.strictEqual(rows.rows[0].is_delivered, true);
        assert.strictEqual(rows.rows[0].is_cancelled, false);
      } finally {
        checkClient.release();
      }
    });

    // -------------------------------------------------------------------------
    // TESTE 5: Revogação Bilateral Transacional: Distinção entre entregue e cancelado
    // -------------------------------------------------------------------------
    await t.test('5. Revogação de Vínculo: Mensagem não entregue vira is_cancelled=TRUE (is_delivered=FALSE); histórico entregue intacto', async () => {
      // Cria vínculo mútuo na tabela connections
      const connClient = await pool.connect();
      try {
        await connClient.query(`
          INSERT INTO connections (from_identity, to_identity, is_mutual) VALUES
          ($1, $2, TRUE), ($2, $1, TRUE)
          ON CONFLICT (from_identity, to_identity) DO UPDATE SET is_mutual = TRUE
        `, [alice.id, bob.id]);
      } finally {
        connClient.release();
      }

      // 1. Mensagem já entregue previamente (msgId1): já está no banco com is_delivered = TRUE
      // 2. Mensagem pendente não entregue (msgPending)
      const msgPendingId = `msg_pend_rev_${Date.now()}`;
      const payloadPending = 'ENC_ECDH:MensagemQueSeraRevogadaAntesDeChegar';
      const ivPending = 'iv_pending_rev';

      const digestPending = digestHttpParts([alice.id, bob.id, payloadPending, ivPending, msgPendingId, alice.alias]);
      const proofPending = signHttpProof(alice, 'MESSAGE_SEND', bob.id, digestPending);

      const sendPendingRes = await fetch(`${httpUrl}/api/messages/send`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          senderHash: alice.id,
          recipientHash: bob.id,
          ciphertextPayload: payloadPending,
          ivNonce: ivPending,
          messageId: msgPendingId,
          senderAlias: alice.alias,
          identityProof: proofPending
        })
      });
      assert.strictEqual(sendPendingRes.status, 200);

      // Agora Alice revoga a conexão mútua com Bob via /api/connections/unmark
      const proofUnmark = signHttpProof(alice, 'CONNECTION_UNMARK', bob.id);
      const unmarkRes = await fetch(`${httpUrl}/api/connections/unmark`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          fromIdentity: alice.id,
          toIdentity: bob.id,
          senderAlias: alice.alias,
          identityProof: proofUnmark
        })
      });

      assert.strictEqual(unmarkRes.status, 200);
      const unmarkData = await unmarkRes.json();
      assert.strictEqual(unmarkData.success, true);

      // Validação no PostgreSQL real:
      const checkClient = await pool.connect();
      try {
        // A. Conexão foi removida
        const connRes = await checkClient.query(
          `SELECT * FROM connections WHERE (from_identity=$1 AND to_identity=$2) OR (from_identity=$2 AND to_identity=$1)`,
          [alice.id, bob.id]
        );
        assert.strictEqual(connRes.rowCount, 0, 'Vínculo mútuo deve ser removido');

        // B. Mensagem que já havia sido entregue (msgId1) permanece intocada: is_delivered = TRUE, is_cancelled = FALSE
        const deliveredRow = await checkClient.query('SELECT * FROM e2ee_messages WHERE id = $1', [msgId1]);
        assert.strictEqual(deliveredRow.rows[0].is_delivered, true, 'Mensagem entregue deve preservar is_delivered = TRUE');
        assert.strictEqual(deliveredRow.rows[0].is_cancelled, false, 'Mensagem entregue não deve ser marcada como cancelada');

        // C. Mensagem pendente não entregue (msgPendingId) foi cancelada:
        // RIGOROSAMENTE: is_delivered = FALSE, is_cancelled = TRUE!
        const pendingRow = await checkClient.query('SELECT * FROM e2ee_messages WHERE id = $1', [msgPendingId]);
        assert.strictEqual(pendingRow.rowCount, 1);
        assert.strictEqual(pendingRow.rows[0].is_delivered, false, 'Mensagem revogada antes da entrega JAMAIS deve ser is_delivered = TRUE');
        assert.strictEqual(pendingRow.rows[0].is_cancelled, true, 'Mensagem revogada antes da entrega deve ter is_cancelled = TRUE');
        assert.ok(pendingRow.rows[0].cancelled_at, 'cancelled_at deve ser registrado');
      } finally {
        checkClient.release();
      }

      // D. Bob tenta consultar mensagens pendentes via HTTP
      const proofBobPending = signHttpProof(bob, 'MESSAGE_PENDING');
      const bobPendingRes = await fetch(`${httpUrl}/api/messages/pending`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          identityHash: bob.id,
          identityProof: proofBobPending
        })
      });
      assert.strictEqual(bobPendingRes.status, 200);
      const bobPendingData = await bobPendingRes.json();
      assert.ok(!bobPendingData.messages.some(m => m.id === msgPendingId || m.messageId === msgPendingId),
        'Mensagem cancelada por revogação NÃO pode ser entregue a Bob');

      // E. Bob tenta dar ACK na mensagem cancelada
      const proofBobAck = signHttpProof(bob, 'MESSAGE_ACK');
      const bobAckRes = await fetch(`${httpUrl}/api/messages/ack`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          identityHash: bob.id,
          messageId: msgPendingId,
          identityProof: proofBobAck
        })
      });
      // Deve retornar 410 Gone / Cancelled
      assert.strictEqual(bobAckRes.status, 410, 'ACK em mensagem cancelada deve retornar HTTP 410 Gone');
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
