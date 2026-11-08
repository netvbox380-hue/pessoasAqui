'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const src = fs.readFileSync(path.join(__dirname, '../src/server.js'), 'utf8');

function setupSystemicTestEnvironment({ postgres = true } = {}) {
  const activePresence = new Map();
  const activeSockets = new Map();
  const pendingMessages = new Map();
  const recentMessageIds = new Map();
  const clientMessageRate = new Map();
  const inFlightDeliveries = new Map();
  const localDurableMessages = new Map();
  const dbMessages = [];

  let forceDbFailure = false;

  const ctx = {
    console,
    Date,
    Set,
    Map,
    String,
    Boolean,
    Array,
    activePresence,
    activeSockets,
    pendingMessages,
    recentMessageIds,
    clientMessageRate,
    inFlightDeliveries,
    localDurableMessages,
    localStore: { connections: new Map(), identities: new Map() },
    process: { env: { DATABASE_URL: 'postgresql://mock_db' } },
    isDatabaseConfigured: () => true,
    isPostgres: () => postgres,
    setDbFailure: (fail) => { forceDbFailure = fail; },
    query: async (sql, params = []) => {
      if (forceDbFailure) {
        throw new Error('Connection terminated unexpectedly: PostgreSQL failure');
      }
      const cleanSql = sql.replace(/\s+/g, ' ');
      if (cleanSql.includes('INSERT INTO e2ee_messages')) {
        const [id, sId, rId, sAlias, cipher, iv] = params;
        const exists = dbMessages.some(m => m.id === id);
        if (!exists) {
          dbMessages.push({
            id,
            sender_identity: sId,
            recipient_identity: rId,
            sender_alias: sAlias,
            ciphertext_payload: cipher,
            iv_nonce: iv,
            is_delivered: false
          });
          return { rowCount: 1 };
        }
        return { rowCount: 0 }; // ON CONFLICT DO NOTHING
      }
      if (cleanSql.includes('SELECT') && cleanSql.includes('e2ee_messages WHERE id = $1')) {
        const [id] = params;
        const rows = dbMessages.filter(m => m.id === id);
        return { rows, rowCount: rows.length };
      }
      if (cleanSql.includes('UPDATE e2ee_messages') && cleanSql.includes('LOWER(recipient_identity) = LOWER($2)')) {
        const [id, recip] = params;
        const msg = dbMessages.find(m => m.id === id && m.recipient_identity.toLowerCase() === recip.toLowerCase());
        if (msg) {
          msg.is_delivered = true;
          return { rowCount: 1 };
        }
        return { rowCount: 0 };
      }
      if (cleanSql.includes('FROM connections')) {
        return {
          rows: [
            { from_identity: params[0], to_identity: params[1], is_mutual: true },
            { from_identity: params[1], to_identity: params[0], is_mutual: true }
          ],
          rowCount: 2
        };
      }
      return { rows: [], rowCount: 0 };
    },
    WebSocket: { OPEN: 1 }
  };

  const startIdx = src.indexOf('async function acknowledgePendingMessage(');
  const endHelper = src.indexOf("wss.on('connection'");
  assert.ok(startIdx >= 0 && endHelper > startIdx, 'Helper code block not found');
  const codeSlice = src.slice(startIdx, endHelper);

  vm.runInNewContext(codeSlice + `
    this.deliverMessageToTarget = deliverMessageToTarget;
    this.acknowledgePendingMessage = acknowledgePendingMessage;
    this.checkRateLimit = checkRateLimit;
    this.isDuplicateMessage = isDuplicateMessage;
    this.cleanId = cleanId;
    this.keyId = keyId;
  `, ctx);

  return {
    deliver: ctx.deliverMessageToTarget,
    acknowledge: ctx.acknowledgePendingMessage,
    setDbFailure: ctx.setDbFailure,
    activePresence,
    activeSockets,
    pendingMessages,
    dbMessages,
    ctx
  };
}

const ALICE = '1111222233334444';
const BOB = '5555666677778888';
const CHARLIE = '9999000011112222';

test('E2E-1. Cenário Principal: Alice -> PostgreSQL -> WS -> Bob -> ACK -> DB Delivered', async () => {
  const env = setupSystemicTestEnvironment({ postgres: true });
  const receivedWs = [];
  const bobSocket = {
    readyState: 1,
    send: (data) => receivedWs.push(JSON.parse(data))
  };

  env.activePresence.set(BOB.toLowerCase(), { identityHash: BOB, alias: 'Bob' });
  env.activeSockets.set(BOB.toLowerCase(), bobSocket);

  // 1. Alice envia mensagem com ID fixo
  const msgId = 'msg_e2e_principal_001';
  const cipher = 'ENC_ECDH:ciphertext_segredo_alice_bob';
  const iv = 'iv_nonce_123';

  const res = await env.deliver(ALICE, 'Alice', BOB, cipher, iv, msgId, false);
  assert.equal(res.success, true);
  assert.equal(res.delivered, true);
  assert.equal(res.persisted, true);

  // 2. Comprova gravação no PostgreSQL
  const dbRecord = env.dbMessages.find(m => m.id === msgId);
  assert.ok(dbRecord);
  assert.equal(dbRecord.is_delivered, false);

  // 3. Comprova recebimento por Bob no WebSocket
  assert.equal(receivedWs.length, 1);
  assert.equal(receivedWs[0].messageId, msgId);

  // 4. Bob persiste localmente e envia ACK
  const ackRes = await env.acknowledge(BOB, msgId);
  assert.equal(ackRes.success, true);
  assert.equal(ackRes.acknowledged, true);

  // 5. Comprova atualização autoritativa no PostgreSQL
  assert.equal(dbRecord.is_delivered, true);
  // Fila em memória deve ter sido limpa
  assert.equal(env.pendingMessages.has(BOB.toLowerCase()), false);
});

test('E2E-2. Cenário de Falha Crítico: DB cai -> 503 -> Android retém -> DB volta -> Reenvio mesmo ID -> Sucesso e ACK', async () => {
  const env = setupSystemicTestEnvironment({ postgres: true });
  const msgId = 'msg_e2e_fail_retry_002';
  const cipher = 'ENC_ECDH:conteudo_importante';
  const iv = 'iv_fail_002';

  // 1. Simula queda do PostgreSQL
  env.setDbFailure(true);

  // 2. Alice tenta enviar durante a queda
  const failRes = await env.deliver(ALICE, 'Alice', BOB, cipher, iv, msgId, false);
  assert.equal(failRes.success, false);
  assert.equal(failRes.statusCode, 503);
  assert.equal(failRes.error, 'Database persistence failed');
  assert.equal(failRes.persisted, false);

  // Comprova que NÃO registrou sucesso falso no DB nem na fila
  assert.equal(env.dbMessages.some(m => m.id === msgId), false);

  // 3. PostgreSQL é restaurado
  env.setDbFailure(false);

  // 4. Android retransmite exatamente com o MESMO msgId
  const successRes = await env.deliver(ALICE, 'Alice', BOB, cipher, iv, msgId, false);
  assert.equal(successRes.success, true);
  assert.equal(successRes.persisted, true);

  // Comprova que agora a mensagem foi duravelmente gravada no DB
  const inDb = env.dbMessages.find(m => m.id === msgId);
  assert.ok(inDb);
  assert.equal(inDb.is_delivered, false);

  // 5. Bob confirma com ACK
  const ackRes = await env.acknowledge(BOB, msgId);
  assert.equal(ackRes.success, true);
  assert.equal(inDb.is_delivered, true);
});

test('E2E-3. Concorrência: Duas requisições simultâneas com mesmo ID tratadas sem duplicação', async () => {
  const env = setupSystemicTestEnvironment({ postgres: true });
  const msgId = 'msg_concurrent_race_003';
  const cipher = 'ENC_ECDH:conteudo_concorrente';
  const iv = 'iv_conc';

  // Dispara dois envios concorrentes com o mesmo messageId
  const [res1, res2] = await Promise.all([
    env.deliver(ALICE, 'Alice', BOB, cipher, iv, msgId, false),
    env.deliver(ALICE, 'Alice', BOB, cipher, iv, msgId, false)
  ]);

  assert.equal(res1.success, true);
  assert.equal(res2.success, true);

  // No banco PostgreSQL deve existir estritamente 1 único registro
  const records = env.dbMessages.filter(m => m.id === msgId);
  assert.equal(records.length, 1);
});

test('E2E-4. Colisão de Identificador: Reuso do mesmo ID com conteúdo diferente é rejeitado (409 Conflict)', async () => {
  const env = setupSystemicTestEnvironment({ postgres: true });
  const msgId = 'msg_tamper_collision_004';

  // Alice envia primeira mensagem legítima
  const res1 = await env.deliver(ALICE, 'Alice', BOB, 'ENC_ECDH:legitimo', 'iv1', msgId, false);
  assert.equal(res1.success, true);

  // Charlie tenta enviar outra mensagem reutilizando o ID de Alice
  const res2 = await env.deliver(CHARLIE, 'Charlie', BOB, 'ENC_ECDH:fraudulento', 'iv2', msgId, false);
  assert.equal(res2.success, false);
  assert.equal(res2.statusCode, 409);
  assert.equal(res2.error, 'Identificador em conflito com mensagem existente diferente');

  // Conteúdo no banco não foi substituído nem corrompido
  const record = env.dbMessages.find(m => m.id === msgId);
  assert.equal(record.sender_identity, ALICE);
  assert.equal(record.ciphertext_payload, 'ENC_ECDH:legitimo');
});

test('E2E-5. Blindagem de ACK: Terceiro não autorizado é rejeitado com 403 Forbidden', async () => {
  const env = setupSystemicTestEnvironment({ postgres: true });
  const msgId = 'msg_bob_private_005';

  await env.deliver(ALICE, 'Alice', BOB, 'ENC_ECDH:secreto_para_bob', 'iv', msgId, false);

  // Charlie tenta confirmar recebimento da mensagem de Bob
  const fraudAck = await env.acknowledge(CHARLIE, msgId);
  assert.equal(fraudAck.success, false);
  assert.equal(fraudAck.statusCode, 403);
  assert.equal(fraudAck.error, 'Não autorizado: destinatário incorreto');

  // Mensagem permanece pendente no banco
  assert.equal(env.dbMessages.find(m => m.id === msgId).is_delivered, false);

  // Bob confirma legitimamente
  const legitAck = await env.acknowledge(BOB, msgId);
  assert.equal(legitAck.success, true);
  assert.equal(env.dbMessages.find(m => m.id === msgId).is_delivered, true);
});

test('E2E-6. Idempotência de ACK: Confirmação repetida pelo destinatário legítimo é segura', async () => {
  const env = setupSystemicTestEnvironment({ postgres: true });
  const msgId = 'msg_idempotent_ack_006';

  await env.deliver(ALICE, 'Alice', BOB, 'ENC_ECDH:msg', 'iv', msgId, false);

  // Primeiro ACK
  const ack1 = await env.acknowledge(BOB, msgId);
  assert.equal(ack1.success, true);
  assert.equal(ack1.duplicate, undefined);

  // Segundo ACK repetido
  const ack2 = await env.acknowledge(BOB, msgId);
  assert.equal(ack2.success, true);
  assert.equal(ack2.acknowledged, true);
  assert.equal(ack2.duplicate, true);
});

test('E2E-7. Classificação Efêmera: Mensagem normal com isEphemeral:true não burla persistência durável', async () => {
  const env = setupSystemicTestEnvironment({ postgres: true });
  // ID padrão sem prefixo call_frame_ nem ephem_
  const msgId = 'uuid_texto_importante_007';

  // Remetente tenta enviar texto secreto com flag isEphemeralParam = true para tentar burlar a gravação
  const res = await env.deliver(ALICE, 'Alice', BOB, 'ENC_ECDH:texto_discreto', 'iv', msgId, true);
  assert.equal(res.success, true);
  // Como não é frame de chamada nem prefixo ephem_, DEVE ser persistida no banco!
  assert.equal(res.persisted, true);
  assert.equal(env.dbMessages.some(m => m.id === msgId), true);
});

test('E2E-8. Roteamento por Identidade Técnica: Apelido homônimo não desvia mensagem privada', async () => {
  const env = setupSystemicTestEnvironment({ postgres: true });

  const BOB_LEGIT = 'BOB_LEGIT_123456';
  const BOB_CLONE = 'BOB_CLONE_999999';

  const receivedLegit = [];
  const receivedClone = [];

  // Dois aparelhos distintos com mesmo apelido "Roberto"
  env.activePresence.set(BOB_LEGIT.toLowerCase(), { identityHash: BOB_LEGIT, alias: 'Roberto' });
  env.activeSockets.set(BOB_LEGIT.toLowerCase(), { readyState: 1, send: d => receivedLegit.push(JSON.parse(d)) });

  env.activePresence.set(BOB_CLONE.toLowerCase(), { identityHash: BOB_CLONE, alias: 'Roberto' });
  env.activeSockets.set(BOB_CLONE.toLowerCase(), { readyState: 1, send: d => receivedClone.push(JSON.parse(d)) });

  // Alice envia especificamente para o BOB_LEGIT
  await env.deliver(ALICE, 'Alice', BOB_LEGIT, 'ENC_ECDH:para_roberto_original', 'iv', 'msg_roberto_1', false);

  // Apenas o Roberto legítimo deve receber a mensagem!
  assert.equal(receivedLegit.length, 1);
  assert.equal(receivedLegit[0].messageId, 'msg_roberto_1');

  // O Roberto homônimo não deve receber absolutamente nada!
  assert.equal(receivedClone.length, 0);
});
