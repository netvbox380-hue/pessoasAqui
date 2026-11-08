'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { spawn, spawnSync } = require('child_process');
const { Pool } = require('pg');
const path = require('path');
const crypto = require('node:crypto');
const http = require('http');
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

test('SUÍTE: FECHAMENTO DOS CAMINHOS ALTERNATIVOS DE AUTORIZAÇÃO (CHAMADAS E DEV LOCAL)', async (t) => {
  // Certifica que DATABASE_URL inicia limpa para os testes de desenvolvimento local
  delete process.env.DATABASE_URL;

  const { app, server, deliverMessageToTarget, getConnectionStatus, localStore, revokedConnections, activePresence, activeSockets } = require('../src/server');
  const { initDatabase, closeDatabase, resetCircuitBreaker, isPostgres } = require('../src/db');

  const alice = createCryptoUser('Alice');
  const bob = createCryptoUser('Bob');
  const stranger = createCryptoUser('Estranho');

  // --------------------------------------------------------------------------
  // PARTE 1: TESTES DE DESENVOLVIMENTO LOCAL (SEM BANCO CONFIGURADO)
  // --------------------------------------------------------------------------
  await t.test('1. Desenvolvimento local sem conexões: rejeitado com HTTP 403 (NO_CONNECTION)', async () => {
    localStore.connections.clear();
    const res = await deliverMessageToTarget(alice.id, 'Alice', bob.id, 'ENC:msg_local_sem_conn', 'iv', 'msg_local_01', false);
    assert.equal(res.success, false);
    assert.equal(res.statusCode, 403);
    assert.equal(res.code, 'NO_CONNECTION');
  });

  await t.test('2. Desenvolvimento local com vínculo unilateral: rejeitado com HTTP 403 (UNILATERAL_NOT_PERMITTED)', async () => {
    localStore.connections.clear();
    const kA = alice.id.toLowerCase();
    const kB = bob.id.toLowerCase();
    // Apenas Alice marca Bob, Bob NÃO marcou Alice
    localStore.connections.set(`${kA}->${kB}`, { fromIdentity: alice.id, toIdentity: bob.id, isMutual: false });

    const res = await deliverMessageToTarget(alice.id, 'Alice', bob.id, 'ENC:msg_local_unilateral', 'iv', 'msg_local_02', false);
    assert.equal(res.success, false);
    assert.equal(res.statusCode, 403);
    assert.equal(res.code, 'UNILATERAL_NOT_PERMITTED');
  });

  await t.test('3. Desenvolvimento local com conexão bilateral válida: autorizado com sucesso 200', async () => {
    localStore.connections.clear();
    const kA = alice.id.toLowerCase();
    const kB = bob.id.toLowerCase();
    // Vínculo bilateral mútuo confirmado
    localStore.connections.set(`${kA}->${kB}`, { fromIdentity: alice.id, toIdentity: bob.id, isMutual: true });
    localStore.connections.set(`${kB}->${kA}`, { fromIdentity: bob.id, toIdentity: alice.id, isMutual: true });

    const res = await deliverMessageToTarget(alice.id, 'Alice', bob.id, 'ENC:msg_local_bilateral', 'iv', 'msg_local_03', false);
    assert.equal(res.success, true);
    // No modo local sem PostgreSQL, persisted (no DB) é false, mas a mensagem é aceita com sucesso
    assert.equal(res.persisted, false);
  });

  // --------------------------------------------------------------------------
  // PARTE 2: INICIALIZAÇÃO DO POSTGRESQL 18 ISOLADO E TESTES DURÁVEIS
  // --------------------------------------------------------------------------
  pgProcess = await startPostgresServer();
  assert.ok(pgProcess, 'PostgreSQL 18 isolado deve iniciar');

  const pool = new Pool({ connectionString: CONNECTION_STRING });
  pool.on('error', () => {});

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
        delivered_at TIMESTAMPTZ,
        created_at TIMESTAMPTZ DEFAULT NOW()
      );
      CREATE TABLE IF NOT EXISTS recovery_proof_nonces (
        id BIGSERIAL PRIMARY KEY,
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
      TRUNCATE TABLE e2ee_messages, connections, recovery_proof_nonces, identity_key_bindings, identities;
    `);

    // Registra Alice, Bob e Estranho no PostgreSQL
    for (const u of [alice, bob, stranger]) {
      await setupClient.query(
        `INSERT INTO identities (id, public_key_ed25519, pin_salted_hash, alias)
         VALUES ($1, $2, 'pin_mock', $3)`,
        [u.id, u.pub, u.alias]
      );
      await setupClient.query(
        `INSERT INTO identity_key_bindings (identity_id, public_key_spki)
         VALUES ($1, $2)`,
        [u.id, u.pub]
      );
    }
  } finally {
    setupClient.release();
  }

  // Conecta o backend ao banco
  process.env.DATABASE_URL = CONNECTION_STRING;
  resetCircuitBreaker();
  initDatabase();

  // Aguarda confirmação de pool ativo no backend
  for (let i = 0; i < 20; i++) {
    if (isPostgres()) break;
    await new Promise(r => setTimeout(r, 100));
  }
  assert.equal(isPostgres(), true, 'Backend deve estar conectado ao PostgreSQL');

  await new Promise(resolve => server.listen(0, resolve));
  const serverPort = server.address().port;
  const baseUrl = `http://127.0.0.1:${serverPort}`;

  // --------------------------------------------------------------------------
  // CENÁRIOS 4, 5, 6: AUTORIZAÇÃO DE CHAMADAS NO POSTGRESQL
  // --------------------------------------------------------------------------
  await t.test('4. Chamada com conexão bilateral válida: autorizada com sucesso', async () => {
    const c = await pool.connect();
    try {
      await c.query(`DELETE FROM connections`);
      await c.query(`INSERT INTO connections (from_identity, to_identity, is_mutual) VALUES ($1, $2, TRUE), ($2, $1, TRUE)`, [alice.id, bob.id]);
    } finally {
      c.release();
    }

    const res = await deliverMessageToTarget(
      alice.id, 'Alice', bob.id,
      '[CALL_INIT:true:Alice:call_room_001]', 'iv_call', 'msg_call_init_01', true
    );
    assert.equal(res.success, true);
    assert.equal(res.persisted, false, 'Chamadas efêmeras não são persistidas');
  });

  await t.test('5. Chamada sem vínculo: rejeitada com HTTP 403 (NO_CONNECTION)', async () => {
    const res = await deliverMessageToTarget(
      stranger.id, 'Estranho', bob.id,
      '[CALL_INIT:true:Estranho:call_stranger]', 'iv_call', 'msg_call_stranger_01', true
    );
    assert.equal(res.success, false);
    assert.equal(res.statusCode, 403);
    assert.equal(res.code, 'NO_CONNECTION');
  });

  await t.test('6. Chamada após revogação: rejeitada com HTTP 403 (CONNECTION_REVOKED)', async () => {
    const pairK = [alice.id.toLowerCase(), bob.id.toLowerCase()].sort().join('<->');
    revokedConnections.add(pairK);

    const res = await deliverMessageToTarget(
      alice.id, 'Alice', bob.id,
      '[CALL_INIT:true:Alice:call_after_revoke]', 'iv_call', 'msg_call_revoked', true
    );
    assert.equal(res.success, false);
    assert.equal(res.statusCode, 403);
    assert.equal(res.code, 'CONNECTION_REVOKED');

    revokedConnections.delete(pairK);
  });

  // --------------------------------------------------------------------------
  // CENÁRIO 7: CHAMADA COM CACHE DESATUALIZADO (SUPABASE É AUTORITATIVO)
  // --------------------------------------------------------------------------
  await t.test('7. Chamada com cache desatualizado: rejeitada pela autoridade do banco relacional', async () => {
    const c = await pool.connect();
    try {
      await c.query(`DELETE FROM connections WHERE (from_identity = $1 AND to_identity = $2) OR (from_identity = $2 AND to_identity = $1)`, [alice.id, bob.id]);
    } finally {
      c.release();
    }

    // Injeta conexões no localStore (simulando cache obsoleto em RAM)
    const kA = alice.id.toLowerCase();
    const kB = bob.id.toLowerCase();
    localStore.connections.set(`${kA}->${kB}`, { fromIdentity: alice.id, toIdentity: bob.id, isMutual: true });
    localStore.connections.set(`${kB}->${kA}`, { fromIdentity: bob.id, toIdentity: alice.id, isMutual: true });

    // Consulta de status e tentativa de chamada
    const status = await getConnectionStatus(alice.id, bob.id);
    assert.equal(status.isMutual, false, 'O banco é a única autoridade: cache em RAM obsoleto não pode ressuscitar conexão');

    const res = await deliverMessageToTarget(
      alice.id, 'Alice', bob.id,
      '[CALL_INIT:true:Alice:call_stale_cache]', 'iv_call', 'msg_call_stale', true
    );
    assert.equal(res.success, false);
    assert.equal(res.statusCode, 403);
    assert.equal(res.code, 'NO_CONNECTION');

    localStore.connections.clear();
  });

  // --------------------------------------------------------------------------
  // CENÁRIO 8: CHAMADA COM POSTGRESQL INDISPONÍVEL (FAIL-CLOSED 503)
  // --------------------------------------------------------------------------
  await t.test('8. Chamada com PostgreSQL indisponível: fail-closed estrito retorna HTTP 503', async () => {
    await stopPostgresServer(pgProcess);

    const res = await deliverMessageToTarget(
      alice.id, 'Alice', bob.id,
      '[CALL_INIT:true:Alice:call_db_down]', 'iv_call', 'msg_call_db_down', true
    );
    assert.equal(res.success, false);
    assert.equal(res.statusCode, 503);
    assert.equal(res.code, 'DB_UNAVAILABLE');

    // Reinicia o banco para os próximos testes
    pgProcess = await startPostgresServer();
    assert.ok(pgProcess, 'PostgreSQL deve reiniciar');
    resetCircuitBreaker();
    initDatabase();
    for (let i = 0; i < 20; i++) {
      if (isPostgres()) break;
      await new Promise(r => setTimeout(r, 100));
    }
    assert.equal(isPostgres(), true, 'PostgreSQL recuperado');
  });

  // --------------------------------------------------------------------------
  // CENÁRIOS 9 & 10: MANIPULAÇÃO DE PREFIXOS E MENSAGENS EFÊMERAS LEGÍTIMAS
  // --------------------------------------------------------------------------
  await t.test('9. Mensagem permanente tentando utilizar prefixo efêmero é persistida duravelmente', async () => {
    const c = await pool.connect();
    try {
      await c.query(`INSERT INTO connections (from_identity, to_identity, is_mutual) VALUES ($1, $2, TRUE), ($2, $1, TRUE) ON CONFLICT DO NOTHING`, [alice.id, bob.id]);
    } finally {
      c.release();
    }

    const masqueradeId = `call_frame_masquerade_${Date.now()}`;
    const vaultPayload = '[VAULT_RECOVERY_SHARE:shamir_secret_part_12345]';

    const res = await deliverMessageToTarget(
      alice.id, 'Alice', bob.id,
      vaultPayload, 'iv_vault', masqueradeId, true
    );
    assert.equal(res.success, true);
    assert.equal(res.persisted, true, 'Payloads permanentes ([VAULT_]) DEVEM ser persistidos mesmo com prefixo de frame');

    const cCheck = await pool.connect();
    try {
      const q = await cCheck.query(`SELECT id, ciphertext_payload FROM e2ee_messages WHERE id = $1`, [masqueradeId]);
      assert.equal(q.rowCount, 1);
      assert.equal(q.rows[0].ciphertext_payload, vaultPayload);
    } finally {
      cCheck.release();
    }
  });

  await t.test('10. Mensagem efêmera legítima: transmitida via WebSocket sem persistir no banco', async () => {
    const callFrameId = `call_frame_audio_${Date.now()}`;
    const audioFramePayload = '[CALL_AUDIO_FRAME:pcm_packet_bytes]';

    const res = await deliverMessageToTarget(
      alice.id, 'Alice', bob.id,
      audioFramePayload, 'iv_audio', callFrameId, true
    );
    assert.equal(res.success, true);
    assert.equal(res.persisted, false, 'Frames de chamada em tempo real não devem poluir a persistência durável');

    const cCheck = await pool.connect();
    try {
      const q = await cCheck.query(`SELECT id FROM e2ee_messages WHERE id = $1`, [callFrameId]);
      assert.equal(q.rowCount, 0, 'Frame efêmero não pode constar em e2ee_messages');
    } finally {
      cCheck.release();
    }
  });

  // --------------------------------------------------------------------------
  // CENÁRIO 11: REINICIALIZAÇÃO DO BACKEND
  // --------------------------------------------------------------------------
  await t.test('11. Reinicialização do backend: autorização é restaurada do PostgreSQL sem vazar estado em RAM', async () => {
    localStore.connections.clear();

    const status = await getConnectionStatus(alice.id, bob.id);
    assert.equal(status.isMutual, true, 'Status mútua restaurado diretamente do PostgreSQL');

    const res = await deliverMessageToTarget(
      alice.id, 'Alice', bob.id,
      '[CALL_ACCEPT]', 'iv_accept', `msg_accept_${Date.now()}`, true
    );
    assert.equal(res.success, true);
  });

  // --------------------------------------------------------------------------
  // CENÁRIO 12: COMPATIBILIDADE COM O CLIENTE ANDROID
  // --------------------------------------------------------------------------
  await t.test('12. Compatibilidade com cliente Android: contrato estruturado com error codes e status codes padronizados', async () => {
    // 1. Envio HTTP para estranho retorna HTTP 403 com code: NO_CONNECTION
    const digestStranger = digestHttpParts([stranger.id, bob.id, 'ENC:msg', 'iv', 'msg_android_compat', 'Estranho']);
    const proofStranger = signHttpProof(stranger, 'MESSAGE_SEND', bob.id, digestStranger);

    const httpRes = await fetch(`${baseUrl}/api/messages/send`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({
        senderHash: stranger.id,
        recipientHash: bob.id,
        ciphertextPayload: 'ENC:msg',
        ivNonce: 'iv',
        messageId: 'msg_android_compat',
        senderAlias: 'Estranho',
        identityProof: proofStranger
      })
    });

    assert.equal(httpRes.status, 403);
    const json = await httpRes.json();
    assert.ok(json.error);
    assert.equal(json.code, 'NO_CONNECTION');

    // 2. Envio HTTP de chamada entre Alice e Bob autorizada com HTTP 200
    const callDigest = digestHttpParts([alice.id, bob.id, '[CALL_INIT:true:Alice:android_call]', 'iv', 'msg_android_call', 'Alice']);
    const proofAlice = signHttpProof(alice, 'MESSAGE_SEND', bob.id, callDigest);

    const callHttpRes = await fetch(`${baseUrl}/api/messages/send`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({
        senderHash: alice.id,
        recipientHash: bob.id,
        ciphertextPayload: '[CALL_INIT:true:Alice:android_call]',
        ivNonce: 'iv',
        messageId: 'msg_android_call',
        senderAlias: 'Alice',
        isEphemeral: true,
        identityProof: proofAlice
      })
    });

    assert.equal(callHttpRes.status, 200);
    const callJson = await callHttpRes.json();
    assert.equal(callJson.success, true);
    assert.equal(callJson.persisted, false);
  });

  // Finalização e limpeza
  await new Promise(resolve => server.close(resolve));
  await pool.end();
  closeDatabase();
  await stopPostgresServer(pgProcess);
});
