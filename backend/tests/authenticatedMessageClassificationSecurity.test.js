'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const src = fs.readFileSync(path.join(__dirname, '../src/server.js'), 'utf8');

function setupClassificationTestEnv(options = {}) {
  const activePresence = new Map();
  const activeSockets = new Map();
  const pendingMessages = new Map();
  const recentMessageIds = new Map();
  const clientMessageRate = new Map();
  const dbMessages = [];
  const localDurableMessages = new Map();

  const localConnections = new Map();
  function addMutual(a, b) {
    const kA = a.toLowerCase();
    const kB = b.toLowerCase();
    localConnections.set(`${kA}->${kB}`, { fromIdentity: a, toIdentity: b, isMutual: true });
    localConnections.set(`${kB}->${kA}`, { fromIdentity: b, toIdentity: a, isMutual: true });
  }

  const ALICE = 'ALICE_CLASS_1234';
  const BOB = 'BOB_CLASS_567890';
  addMutual(ALICE, BOB);

  const ctx = {
    console,
    Date,
    Set,
    Map,
    String,
    Boolean,
    activePresence,
    activeSockets,
    pendingMessages,
    recentMessageIds,
    clientMessageRate,
    inFlightDeliveries: new Map(),
    localDurableMessages,
    revokedConnections: new Set(),
    process: { env: options.postgres ? { DATABASE_URL: 'postgres://simulated' } : {} },
    isDatabaseConfigured: () => Boolean(options.postgres),
    localStore: { connections: localConnections },
    isPostgres: () => Boolean(options.postgres),
    query: async (sql, params) => {
      const q = String(sql).toUpperCase();
      if (q.includes('FROM CONNECTIONS')) {
        return {
          rows: [
            { from_identity: ALICE, to_identity: BOB, is_mutual: true },
            { from_identity: BOB, to_identity: ALICE, is_mutual: true }
          ]
        };
      }
      if (q.includes('INSERT INTO E2EE_MESSAGES')) {
        const id = params[0];
        const sender = params[1];
        const recip = params[2];
        const alias = params[3];
        const payload = params[4];
        const iv = params[5];
        dbMessages.push({ id, sender, recip, alias, payload, iv });
        return { rowCount: 1, rows: [{ id }] };
      }
      return { rows: [] };
    },
    withTransaction: async (cb) => {
      return cb(async (sql, params) => {
        const q = String(sql).toUpperCase();
        if (q.includes('FROM CONNECTIONS')) {
          return {
            rows: [
              { from_identity: ALICE, to_identity: BOB, is_mutual: true },
              { from_identity: BOB, to_identity: ALICE, is_mutual: true }
            ]
          };
        }
        if (q.includes('INSERT INTO E2EE_MESSAGES')) {
          const id = params[0];
          const sender = params[1];
          const recip = params[2];
          const alias = params[3];
          const payload = params[4];
          const iv = params[5];
          dbMessages.push({ id, sender, recip, alias, payload, iv });
          return { rowCount: 1, rows: [{ id }] };
        }
        return { rows: [] };
      });
    },
    WebSocket: { OPEN: 1 }
  };

  const startHelper = src.indexOf('function checkRateLimit(');
  const endHelper = src.indexOf("wss.on('connection'");
  assert.ok(startHelper >= 0 && endHelper > startHelper, 'Helper code block not found');
  const codeSlice = src.slice(startHelper, endHelper);

  vm.runInNewContext(codeSlice + `
    this.deliverMessageToTarget = deliverMessageToTarget;
    this.checkRateLimit = checkRateLimit;
    this.isDuplicateMessage = isDuplicateMessage;
    this.cleanId = cleanId;
    this.keyId = keyId;
  `, ctx);

  return {
    deliver: ctx.deliverMessageToTarget,
    checkRateLimit: ctx.checkRateLimit,
    activePresence,
    activeSockets,
    pendingMessages,
    dbMessages,
    localDurableMessages,
    ALICE,
    BOB
  };
}

test('1. Prefixo manipulado call_frame_ sem flag efêmera: persistido duravelmente no PostgreSQL', async () => {
  const env = setupClassificationTestEnv({ postgres: true });
  const msgId = `call_frame_spoofed_${Date.now()}`;

  // Atacante tenta prefixo call_frame_ mas isEphemeralParam = false
  const res = await env.deliver(
    env.ALICE, 'Alice', env.BOB,
    'ENC_ECDH:texto_sigiloso_tentando_burlar_db', 'iv1', msgId, false
  );

  assert.equal(res.success, true);
  assert.equal(res.persisted, true, 'Mensagem deve ser persistida duravelmente');
  assert.equal(env.dbMessages.some(m => m.id === msgId), true);
});

test('2. Prefixo manipulado ephem_ sem flag efêmera: persistido duravelmente no PostgreSQL', async () => {
  const env = setupClassificationTestEnv({ postgres: true });
  const msgId = `ephem_spoofed_${Date.now()}`;

  const res = await env.deliver(
    env.ALICE, 'Alice', env.BOB,
    'ENC_ECDH:outro_texto_sigiloso', 'iv2', msgId, false
  );

  assert.equal(res.success, true);
  assert.equal(res.persisted, true);
  assert.equal(env.dbMessages.some(m => m.id === msgId), true);
});

test('3. Formato futuro / desconhecido de payload cifrado sem flag efêmera: persistido duravelmente', async () => {
  const env = setupClassificationTestEnv({ postgres: true });
  const msgId = `msg_future_format_${Date.now()}`;
  const futureCiphertext = 'ENC_ECDH_V3:{"type":"future_poll","meta":{"v":3},"data":"A1B2C3"}';

  const res = await env.deliver(
    env.ALICE, 'Alice', env.BOB,
    futureCiphertext, 'iv3', msgId, false
  );

  assert.equal(res.success, true);
  assert.equal(res.persisted, true);
  assert.equal(env.dbMessages.some(m => m.id === msgId && m.payload === futureCiphertext), true);
});

test('4. Legítima sinalização de chamada cifrada com contrato efêmero: despachada em tempo real sem persistir no banco', async () => {
  const env = setupClassificationTestEnv({ postgres: true });
  const receivedWs = [];
  env.activeSockets.set(env.BOB.toLowerCase(), {
    readyState: 1,
    send: (d) => receivedWs.push(JSON.parse(d))
  });

  const msgId = `ephem_call_init_${Date.now()}`;
  const res = await env.deliver(
    env.ALICE, 'Alice', env.BOB,
    'ENC_ECDH:sinalizacao_chamada_cifrada', 'ivCall', msgId, true
  );

  assert.equal(res.success, true);
  assert.equal(res.delivered, true);
  assert.equal(res.persisted, false, 'Sinalização efêmera NÃO deve ser persistida no banco');
  assert.equal(env.dbMessages.length, 0);
  assert.equal(receivedWs.length, 1);
  assert.equal(receivedWs[0].isEphemeral, true);
});

test('5. Legítimo frame de áudio/vídeo cifrado com contrato efêmero: despachado sem poluir banco nem fila offline', async () => {
  const env = setupClassificationTestEnv({ postgres: true });
  // Bob está offline
  const msgId = `call_frame_audio_${Date.now()}`;
  const res = await env.deliver(
    env.ALICE, 'Alice', env.BOB,
    'ENC_ECDH:audio_frame_data_chunk', 'ivAudio', msgId, true
  );

  assert.equal(res.success, true);
  assert.equal(res.delivered, false);
  assert.equal(res.persisted, false);
  assert.equal(env.dbMessages.length, 0);
  // Fila offline não retém frames de chamada
  assert.equal(env.pendingMessages.has(env.BOB.toLowerCase()), false);
});

test('6. Tentativa de burlar persistência com flag efêmera em mensagem de texto comum (UUID sem prefixo de chamada): fail-closed para permanente', async () => {
  const env = setupClassificationTestEnv({ postgres: true });
  const normalUuid = '550e8400-e29b-41d4-a716-446655440000';

  const res = await env.deliver(
    env.ALICE, 'Alice', env.BOB,
    'ENC_ECDH:tentativa_de_apagar_mensagem_importante', 'ivNorm', normalUuid, true
  );

  assert.equal(res.success, true);
  assert.equal(res.persisted, true, 'Deve persistir duravelmente por falha fechada se ID não tem contrato de chamada');
  assert.equal(env.dbMessages.some(m => m.id === normalUuid), true);
});

test('7. Comandos de cofre e controle em texto claro com prefixo call_frame_ e flag efêmera: protegidos e persistidos', async () => {
  const env = setupClassificationTestEnv({ postgres: true });
  const masqueradeId = `call_frame_vault_${Date.now()}`;

  const res = await env.deliver(
    env.ALICE, 'Alice', env.BOB,
    '[VAULT_KEY_SHARE:segmento_secreto]', 'ivV', masqueradeId, true
  );

  assert.equal(res.success, true);
  assert.equal(res.persisted, true, 'Comando [VAULT_] NUNCA pode ser descartado como efêmero');
  assert.equal(env.dbMessages.some(m => m.id === masqueradeId), true);
});

test('8. Comandos de foto real e vínculos familiares em texto claro: persistidos duravelmente', async () => {
  const env = setupClassificationTestEnv({ postgres: true });
  const photoId = `call_frame_photo_${Date.now()}`;
  const res1 = await env.deliver(
    env.ALICE, 'Alice', env.BOB,
    '[PA_REAL_PHOTO_V1:base64_photo_data]', 'ivP', photoId, true
  );
  assert.equal(res1.persisted, true);

  const familyId = `call_frame_family_${Date.now()}`;
  const res2 = await env.deliver(
    env.ALICE, 'Alice', env.BOB,
    '[FAMILY_GUARD_REQUEST:v1]', 'ivF', familyId, true
  );
  assert.equal(res2.persisted, true);
});

test('9. Chamada legada sem contrato autenticado explícito: preservação durável por segurança (fail-closed)', async () => {
  const env = setupClassificationTestEnv({ postgres: true });
  const receivedWs = [];
  env.activeSockets.set(env.BOB.toLowerCase(), {
    readyState: 1,
    send: (d) => receivedWs.push(JSON.parse(d))
  });

  const legacyId = `legacy_call_${Date.now()}`;
  const res = await env.deliver(
    env.ALICE, 'Alice', env.BOB,
    '[CALL_INIT:true:Alice:room_legacy]', 'ivLeg', legacyId, false
  );

  assert.equal(res.success, true);
  // Sem operation e sem isEphemeral assinados não há como atestar
  // que o payload legado é uma chamada. Não classificar só por prefixo.
  assert.equal(res.persisted, true, 'Sem contrato autenticado a entrega deve ser durável');
  assert.equal(receivedWs.length, 1);
  assert.equal(receivedWs[0].isEphemeral, false);
});

test('10. Anti-flood e rate-limit: mensagem com prefixo call_frame_ sem flag efêmera sofre limite normal de 25/s', async () => {
  const env = setupClassificationTestEnv({ postgres: true });
  const identity = 'ALICE_FLOOD_TEST';

  // Sem flag efêmera, isStream deve ser false (limite de 25 msg/s)
  for (let i = 0; i < 25; i++) {
    assert.equal(env.checkRateLimit(identity, false), true);
  }
  // A 26ª requisição deve ser bloqueada
  assert.equal(env.checkRateLimit(identity, false), false, '26ª mensagem discreta deve ser bloqueada');
});

test('11. Anti-flood e rate-limit: streaming legítimo com contrato efêmero permite até 120 frames/s', async () => {
  const env = setupClassificationTestEnv({ postgres: true });
  const identity = 'ALICE_STREAM_RATE_TEST';

  for (let i = 0; i < 120; i++) {
    assert.equal(env.checkRateLimit(identity, true), true);
  }
  // O 121º frame deve ser bloqueado
  assert.equal(env.checkRateLimit(identity, true), false, '121º frame de streaming deve ser bloqueado');
});

test('12. Tentativa de manipulação com prefixo call_ em mensagem não-efêmera: persistida duravelmente', async () => {
  const env = setupClassificationTestEnv({ postgres: true });
  const msgId = `call_session_spoofed_${Date.now()}`;

  const res = await env.deliver(
    env.ALICE, 'Alice', env.BOB,
    'ENC_ECDH:mensagem_sigilosa', 'iv4', msgId, false
  );

  assert.equal(res.success, true);
  assert.equal(res.persisted, true, 'Prefixo call_ sem contrato efêmero DEVE ser persistido duravelmente');
  assert.equal(env.dbMessages.some(m => m.id === msgId), true);
});

