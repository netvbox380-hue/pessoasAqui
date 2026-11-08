'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { spawn } = require('child_process');
const { Client, Pool } = require('pg');
const path = require('path');
const crypto = require('node:crypto');
const WebSocket = require('ws');

const PG_PORT = 54333;
const PG_DATA = path.resolve(__dirname, '../.pg_isolated_data');
const PG_BIN = 'C:/Program Files/PostgreSQL/18/bin/postgres.exe';
const CONNECTION_STRING = `postgresql://postgres@127.0.0.1:${PG_PORT}/postgres`;

const { identityFromPublicKey, verifyWsIdentity } = require('../src/identityAuth');
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

function signWsAuth(user, challenge) {
  const s = crypto.createSign('SHA256');
  s.update('PA-WS-AUTH-V2|' + user.id + '|' + challenge);
  s.end();
  return s.sign(user.pair.privateKey).toString('base64');
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

test('SUÍTE DE INTEGRAÇÃO REAL: FUNÇÕES REAIS, HTTP, WEBSOCKET E POSTGRESQL 18', async (t) => {
  // 1. Inicia instância real e isolada do PostgreSQL 18
  pgProcess = await startPostgresServer();
  assert.ok(pgProcess, 'PostgreSQL 18 deve estar ativo');

  const pool = new Pool({
    connectionString: CONNECTION_STRING,
    max: 10,
    connectionTimeoutMillis: 5000
  });
  // Ignora erros de desconexão no pool de teste quando o banco for propositalmente desligado
  pool.on('error', () => {});

  const setupClient = await pool.connect();
  try {
    await setupClient.query(`
      CREATE TABLE IF NOT EXISTS e2ee_messages (
        id VARCHAR(64) PRIMARY KEY,
        sender_identity VARCHAR(64) NOT NULL,
        recipient_identity VARCHAR(64) NOT NULL,
        sender_alias VARCHAR(64),
        ciphertext_payload TEXT NOT NULL,
        iv_nonce VARCHAR(64) NOT NULL,
        is_delivered BOOLEAN DEFAULT FALSE,
        created_at TIMESTAMPTZ DEFAULT NOW(),
        delivered_at TIMESTAMPTZ
      );
      CREATE INDEX IF NOT EXISTS idx_e2ee_messages_recipient_undelivered 
        ON e2ee_messages (recipient_identity, is_delivered) WHERE is_delivered = FALSE;

      CREATE TABLE IF NOT EXISTS connections (
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

      TRUNCATE TABLE e2ee_messages, connections, recovery_proof_nonces, identity_key_bindings;
    `);
  } finally {
    setupClient.release();
  }

  // 2. Configura ambiente para o backend real conectar ao PostgreSQL isolado
  process.env.DATABASE_URL = CONNECTION_STRING;
  const { app, server, activeSockets, wss } = require('../src/server');
  const { initDatabase, resetCircuitBreaker } = require('../src/db');
  resetCircuitBreaker();
  initDatabase();

  // Espera a sonda SELECT 1 do db.js confirmar conexão
  await new Promise((r) => setTimeout(r, 600));

  // 3. Inicia o servidor HTTP + WebSocket real em porta efêmera (0)
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const serverPort = server.address().port;
  const wsUrl = `ws://127.0.0.1:${serverPort}`;
  const httpUrl = `http://127.0.0.1:${serverPort}`;

  const alice = createCryptoUser('Alice');
  const bob = createCryptoUser('Bob');
  const eve = createCryptoUser('Eve');

  // Registra as chaves de identidade no banco para vincular dispositivos
  const bindClient = await pool.connect();
  try {
    await bindClient.query(`
      INSERT INTO identity_key_bindings (identity_id, public_key_spki) VALUES
      ($1, $2), ($3, $4), ($5, $6)
      ON CONFLICT (identity_id) DO UPDATE SET public_key_spki = EXCLUDED.public_key_spki
    `, [alice.id, alice.pub, bob.id, bob.pub, eve.id, eve.pub]);

    await bindClient.query(`
      INSERT INTO connections (from_identity, to_identity, is_mutual) VALUES
      ($1, $2, TRUE), ($2, $1, TRUE)
      ON CONFLICT (from_identity, to_identity) DO UPDATE SET is_mutual = TRUE
    `, [alice.id, bob.id]);
  } finally {
    bindClient.release();
  }

  // Helper para conectar cliente WebSocket real autenticado
  function connectAuthenticatedWs(user) {
    return new Promise((resolve, reject) => {
      const ws = new WebSocket(wsUrl);
      let challenge = null;

      ws.on('message', (raw) => {
        try {
          const msg = JSON.parse(raw.toString());
          if (msg.type === 'AUTH_CHALLENGE') {
            challenge = msg.nonce;
            const signature = signWsAuth(user, challenge);
            ws.send(JSON.stringify({
              type: 'AUTH',
              identityHash: user.id,
              alias: user.alias,
              publicKey: user.pub,
              challengeNonce: challenge,
              signature: signature
            }));
          } else if (msg.type === 'AUTH_SUCCESS') {
            resolve(ws);
          } else if (msg.type === 'AUTH_FAILED') {
            reject(new Error(`AUTH_FAILED: ${msg.message}`));
          }
        } catch (e) {
          reject(e);
        }
      });

      ws.on('error', reject);
    });
  }

  let wsAlice = null;
  let wsBob = null;

  try {
    // -------------------------------------------------------------------------
    // SUBTEST 1: Conexão WebSocket real com autenticação criptográfica P-256
    // -------------------------------------------------------------------------
    await t.test('1. WebSocket Real: Alice e Bob conectam e autenticam com sucesso', async () => {
      wsAlice = await connectAuthenticatedWs(alice);
      wsBob = await connectAuthenticatedWs(bob);
      assert.ok(wsAlice, 'Alice deve estar conectada');
      assert.ok(wsBob, 'Bob deve estar conectado');
      assert.strictEqual(wsAlice.readyState, WebSocket.OPEN);
      assert.strictEqual(wsBob.readyState, WebSocket.OPEN);
    });

    // -------------------------------------------------------------------------
    // SUBTEST 2: Envio E2EE via WebSocket -> Persistência durável no DB real
    // -------------------------------------------------------------------------
    const msgId1 = `msg_e2e_${Date.now()}_1`;
    const payload1 = 'ENC_ECDH:CifradoPontaAPontaReal';
    const iv1 = 'iv_teste_real_1';

    await t.test('2. WebSocket E2EE: Alice envia para Bob -> Mensagem persistida no PostgreSQL antes da entrega', async () => {
      const bobReceivedPromise = new Promise((resolve) => {
        const handler = (raw) => {
          const m = JSON.parse(raw.toString());
          if (m.type === 'E2EE_MESSAGE_RECEIVED' && m.messageId === msgId1) {
            wsBob.off('message', handler);
            resolve(m);
          }
        };
        wsBob.on('message', handler);
      });

      wsAlice.send(JSON.stringify({
        type: 'E2EE_MESSAGE',
        recipientHash: bob.id,
        ciphertextPayload: payload1,
        ivNonce: iv1,
        messageId: msgId1
      }));

      const receivedMsg = await bobReceivedPromise;
      assert.strictEqual(receivedMsg.senderHash, alice.id);
      assert.strictEqual(receivedMsg.ciphertextPayload, payload1);

      // Validação direta no PostgreSQL 18 real: a linha DEVE existir no banco com is_delivered = FALSE
      const checkClient = await pool.connect();
      try {
        const res = await checkClient.query('SELECT * FROM e2ee_messages WHERE id = $1', [msgId1]);
        assert.strictEqual(res.rowCount, 1, 'Mensagem deve estar fisicamente no banco');
        assert.strictEqual(res.rows[0].sender_identity, alice.id);
        assert.strictEqual(res.rows[0].recipient_identity, bob.id);
        assert.strictEqual(res.rows[0].is_delivered, false, 'Inicialmente não entregue');
      } finally {
        checkClient.release();
      }
    });

    // -------------------------------------------------------------------------
    // SUBTEST 3: Bob envia MESSAGE_ACK via WebSocket -> Banco atualizado para TRUE
    // -------------------------------------------------------------------------
    await t.test('3. Confirmação (ACK) via WebSocket: Bob confirma e PostgreSQL registra is_delivered = TRUE', async () => {
      const ackConfirmPromise = new Promise((resolve) => {
        const handler = (raw) => {
          const m = JSON.parse(raw.toString());
          if (m.type === 'MESSAGE_ACK_CONFIRMED' && m.messageId === msgId1) {
            wsBob.off('message', handler);
            resolve(m);
          }
        };
        wsBob.on('message', handler);
      });

      wsBob.send(JSON.stringify({
        type: 'MESSAGE_ACK',
        messageId: msgId1
      }));

      const ackResult = await ackConfirmPromise;
      assert.strictEqual(ackResult.success, true);
      assert.strictEqual(ackResult.acknowledged, true);

      // Validação direta no PostgreSQL 18 real: is_delivered DEVE ser TRUE
      const checkClient = await pool.connect();
      try {
        const res = await checkClient.query('SELECT is_delivered FROM e2ee_messages WHERE id = $1', [msgId1]);
        assert.strictEqual(res.rows[0].is_delivered, true, 'Banco deve confirmar is_delivered = TRUE');
      } finally {
        checkClient.release();
      }
    });

    // -------------------------------------------------------------------------
    // SUBTEST 4: Idempotência de ACK: Confirmação repetida via WebSocket
    // -------------------------------------------------------------------------
    await t.test('4. Idempotência de ACK: Bob envia segundo ACK para mesmo ID sem erro no PostgreSQL', async () => {
      const ackConfirmPromise = new Promise((resolve) => {
        const handler = (raw) => {
          const m = JSON.parse(raw.toString());
          if (m.type === 'MESSAGE_ACK_CONFIRMED' && m.messageId === msgId1) {
            wsBob.off('message', handler);
            resolve(m);
          }
        };
        wsBob.on('message', handler);
      });

      wsBob.send(JSON.stringify({
        type: 'MESSAGE_ACK',
        messageId: msgId1
      }));

      const ackResult = await ackConfirmPromise;
      assert.strictEqual(ackResult.success, true);
      assert.strictEqual(ackResult.acknowledged, true);
      assert.strictEqual(ackResult.duplicate, true, 'Deve sinalizar duplicate de forma idempotente');
    });

    // -------------------------------------------------------------------------
    // SUBTEST 5: Segurança de ACK: Terceiro não autorizado (Eve) rejeitado com 403
    // -------------------------------------------------------------------------
    await t.test('5. Segurança de ACK: Eve (não autorizada) tenta confirmar mensagem de Bob via HTTP e recebe 403', async () => {
      // Eve assina prova válida com a chave dela
      const eveProof = signHttpProof(eve, 'MESSAGE_ACK');
      const res = await fetch(`${httpUrl}/api/messages/ack`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          identityHash: eve.id,
          messageId: msgId1,
          identityProof: eveProof
        })
      });

      assert.strictEqual(res.status, 403, 'Eve deve ser rejeitada com 403 Forbidden pelo banco');
      const body = await res.json();
      assert.strictEqual(body.success, false);
      assert.ok(body.error.includes('Não autorizado'));
    });

    // -------------------------------------------------------------------------
    // SUBTEST 6: Envio HTTP com destinatário offline -> Persistência durável
    // -------------------------------------------------------------------------
    const msgId2 = `msg_offline_${Date.now()}_2`;
    const payload2 = 'ENC_ECDH:ConteudoParaUsuarioOffline';
    const iv2 = 'iv_offline_2';

    await t.test('6. Envio HTTP Offline: Mensagem para Bob quando offline fica gravada no PostgreSQL', async () => {
      // Fecha a conexão do Bob
      wsBob.close();
      await new Promise((r) => setTimeout(r, 200));

      const digest = digestHttpParts([alice.id, bob.id, payload2, iv2, msgId2, alice.alias]);
      const aliceProof = signHttpProof(alice, 'MESSAGE_SEND', bob.id, digest);

      const res = await fetch(`${httpUrl}/api/messages/send`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          senderHash: alice.id,
          recipientHash: bob.id,
          ciphertextPayload: payload2,
          ivNonce: iv2,
          messageId: msgId2,
          senderAlias: alice.alias,
          identityProof: aliceProof
        })
      });

      assert.strictEqual(res.status, 200, 'Envio HTTP deve ser bem-sucedido');
      const body = await res.json();
      assert.strictEqual(body.success, true);
      assert.strictEqual(body.delivered, false, 'Bob estava offline');

      // Validação no PostgreSQL real
      const checkClient = await pool.connect();
      try {
        const dbRes = await checkClient.query('SELECT * FROM e2ee_messages WHERE id = $1', [msgId2]);
        assert.strictEqual(dbRes.rowCount, 1);
        assert.strictEqual(dbRes.rows[0].is_delivered, false);
      } finally {
        checkClient.release();
      }
    });

    // -------------------------------------------------------------------------
    // SUBTEST 7: Consulta HTTP de pendências e ACK HTTP legítimo por Bob
    // -------------------------------------------------------------------------
    await t.test('7. Recuperação e ACK HTTP: Bob busca mensagens pendentes e confirma recebimento via HTTP', async () => {
      const bobProofPending = signHttpProof(bob, 'MESSAGE_PENDING');
      const pendingRes = await fetch(`${httpUrl}/api/messages/pending`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          identityHash: bob.id,
          identityProof: bobProofPending
        })
      });

      assert.strictEqual(pendingRes.status, 200);
      const pendingData = await pendingRes.json();
      assert.ok(Array.isArray(pendingData.messages));
      const found = pendingData.messages.find(m => m.id === msgId2 || m.messageId === msgId2);
      assert.ok(found, 'Mensagem pendente deve constar na resposta');

      // Bob envia ACK legítimo via HTTP
      const bobProofAck = signHttpProof(bob, 'MESSAGE_ACK');
      const ackRes = await fetch(`${httpUrl}/api/messages/ack`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          identityHash: bob.id,
          messageId: msgId2,
          identityProof: bobProofAck
        })
      });

      assert.strictEqual(ackRes.status, 200);
      const ackBody = await ackRes.json();
      assert.strictEqual(ackBody.success, true);

      // Validação no PostgreSQL real: is_delivered deve ser TRUE
      const checkClient = await pool.connect();
      try {
        const dbRes = await checkClient.query('SELECT is_delivered FROM e2ee_messages WHERE id = $1', [msgId2]);
        assert.strictEqual(dbRes.rows[0].is_delivered, true, 'PostgreSQL deve confirmar entrega após ACK HTTP');
      } finally {
        checkClient.release();
      }
    });

    // -------------------------------------------------------------------------
    // SUBTEST 8: Política Fail-Closed: PostgreSQL cai -> Retorna HTTP 503 sem falso sucesso
    // -------------------------------------------------------------------------
    await t.test('8. Fail-Closed: Queda do PostgreSQL retorna HTTP 503 e rejeita simulação em RAM', async () => {
      await stopPostgresServer(pgProcess);
      pgProcess = null;
      await new Promise((r) => setTimeout(r, 600));

      const failPayload = 'ENC_ECDH:TentativaComBancoFora';
      const failMsgId = `msg_fail_${Date.now()}`;
      const failIv = 'iv_fail_closed';
      const digestFail = digestHttpParts([alice.id, bob.id, failPayload, failIv, failMsgId, alice.alias]);
      const aliceProofFail = signHttpProof(alice, 'MESSAGE_SEND', bob.id, digestFail);

      const res = await fetch(`${httpUrl}/api/messages/send`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          senderHash: alice.id,
          recipientHash: bob.id,
          ciphertextPayload: failPayload,
          ivNonce: failIv,
          messageId: failMsgId,
          senderAlias: alice.alias,
          identityProof: aliceProofFail
        })
      });

      assert.strictEqual(res.status, 503, 'Deve retornar HTTP 503 Service Unavailable');
      const body = await res.json();
      assert.strictEqual(body.error, 'Autenticação indisponível.');
    });

    // -------------------------------------------------------------------------
    // SUBTEST 9: Recuperação Pós-Restart: Banco reinicia e mensagens do disco persistem
    // -------------------------------------------------------------------------
    await t.test('9. Recuperação: PostgreSQL reinicia e todas as mensagens do disco permanecem intactas', async () => {
      pgProcess = await startPostgresServer();
      assert.ok(pgProcess, 'PostgreSQL deve ter reiniciado com sucesso');

      const recoveryPool = new Pool({
        connectionString: CONNECTION_STRING,
        max: 5,
        connectionTimeoutMillis: 5000
      });

      const recoveryClient = await recoveryPool.connect();
      try {
        const res1 = await recoveryClient.query('SELECT is_delivered FROM e2ee_messages WHERE id = $1', [msgId1]);
        assert.strictEqual(res1.rowCount, 1);
        assert.strictEqual(res1.rows[0].is_delivered, true);

        const res2 = await recoveryClient.query('SELECT is_delivered FROM e2ee_messages WHERE id = $1', [msgId2]);
        assert.strictEqual(res2.rowCount, 1);
        assert.strictEqual(res2.rows[0].is_delivered, true);
      } finally {
        recoveryClient.release();
        await recoveryPool.end();
      }
    });

  } finally {
    // Encerramento limpo de todos os recursos
    if (wsAlice && wsAlice.readyState === WebSocket.OPEN) {
      wsAlice.close();
    }
    if (wsBob && wsBob.readyState === WebSocket.OPEN) {
      wsBob.close();
    }
    await new Promise((r) => server.close(r));
    await pool.end();
    if (pgProcess) {
      await stopPostgresServer(pgProcess);
      pgProcess = null;
    }
  }
});
