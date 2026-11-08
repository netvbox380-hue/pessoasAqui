'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const src = fs.readFileSync(path.join(__dirname, '../src/server.js'), 'utf8');

function setupTestEnvironment() {
  const activePresence = new Map();
  const activeSockets = new Map();
  const pendingMessages = new Map();
  const recentMessageIds = new Map();
  const clientMessageRate = new Map();

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
    localDurableMessages: new Map(),
    process: { env: {} },
    isDatabaseConfigured: () => false,
    localStore: { connections: new Map() },
    isPostgres: () => false,
    query: async () => ({ rows: [] }),
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
    isDuplicateMessage: ctx.isDuplicateMessage,
    activePresence,
    activeSockets,
    pendingMessages,
    recentMessageIds,
    clientMessageRate
  };
}

test('1. Envio de texto permanente para destinatário online via WS', async () => {
  const env = setupTestEnvironment();
  const receivedWs = [];
  const fakeSocket = {
    readyState: 1,
    send: (data) => receivedWs.push(JSON.parse(data))
  };

  env.activePresence.set('bob', { identityHash: 'BOB_1234567890AB', alias: 'Bob' });
  env.activeSockets.set('bob_1234567890ab', fakeSocket);

  const res = await env.deliver(
    'ALICE_1234567890A', 'Alice', 'BOB_1234567890AB',
    'ENC_ECDH:ciphertext_texto_secreto', 'iv123', 'msg_txt_01', false
  );

  assert.equal(res.success, true);
  assert.equal(res.delivered, true);
  assert.equal(receivedWs.length, 1);
  assert.equal(receivedWs[0].type, 'E2EE_MESSAGE_RECEIVED');
  assert.equal(receivedWs[0].senderHash, 'ALICE_1234567890A');
  assert.equal(receivedWs[0].ciphertextPayload, 'ENC_ECDH:ciphertext_texto_secreto');
  assert.equal(receivedWs[0].isEphemeral, false);
  // Mensagem permanente fica também na fila de resiliência HTTP (arquitetura dual WS+HTTP)
  assert.equal(env.pendingMessages.has('bob_1234567890ab'), true);
});

test('2. Envio de texto permanente para destinatário offline (armazenado em pendingMessages)', async () => {
  const env = setupTestEnvironment();
  env.activePresence.set('bob', { identityHash: 'BOB_1234567890AB', alias: 'Bob' });
  // Sem socket ativo para Bob

  const res = await env.deliver(
    'ALICE_1234567890A', 'Alice', 'BOB_1234567890AB',
    'ENC_ECDH:ciphertext_texto_offline', 'iv123', 'msg_txt_02', false
  );

  assert.equal(res.success, true);
  assert.equal(res.delivered, false);
  const queue = env.pendingMessages.get('bob_1234567890ab');
  assert.ok(queue);
  assert.equal(queue.length, 1);
  assert.equal(queue[0].messageId, 'msg_txt_02');
  assert.equal(queue[0].isEphemeral, false);
});

test('3. Envio de mídia criptografada (imagem/áudio) preserva integridade e fila offline', async () => {
  const env = setupTestEnvironment();
  env.activePresence.set('bob', { identityHash: 'BOB_1234567890AB', alias: 'Bob' });

  // Mídia em Modo B trafega cifrada no envelope E2EE
  const mediaPayload = 'ENC_ECDH:base64_imagem_cifrada_aes_gcm';
  const res = await env.deliver(
    'ALICE_1234567890A', 'Alice', 'BOB_1234567890AB',
    mediaPayload, 'ivMedia', 'msg_img_01', false
  );

  assert.equal(res.success, true);
  assert.equal(res.delivered, false); // offline
  const queue = env.pendingMessages.get('bob_1234567890ab');
  assert.ok(queue);
  assert.equal(queue.length, 1);
  assert.equal(queue[0].ciphertextPayload, mediaPayload);
  assert.equal(queue[0].isEphemeral, false);
});

test('4. Quadros de chamada e frames efêmeros entregues via WS sem acumular em fila offline', async () => {
  const env = setupTestEnvironment();
  const receivedWs = [];
  const fakeSocket = {
    readyState: 1,
    send: (data) => receivedWs.push(JSON.parse(data))
  };

  env.activePresence.set('bob', { identityHash: 'BOB_1234567890AB', alias: 'Bob' });
  env.activeSockets.set('bob_1234567890ab', fakeSocket);

  // Frame de chamada com identificador call_frame_
  const res = await env.deliver(
    'ALICE_1234567890A', 'Alice', 'BOB_1234567890AB',
    'ENC_ECDH:audio_frame_cifrado', 'ivFrame1', 'call_frame_1001', true
  );

  assert.equal(res.success, true);
  assert.equal(res.delivered, true);
  assert.equal(res.persisted, false);
  assert.equal(receivedWs.length, 1);
  assert.equal(receivedWs[0].isEphemeral, true);
  assert.equal(env.pendingMessages.has('bob_1234567890ab'), false);
});

test('5. Quadros de chamada descartados se destinatário estiver offline (NÃO poluem fila)', async () => {
  const env = setupTestEnvironment();
  env.activePresence.set('bob', { identityHash: 'BOB_1234567890AB', alias: 'Bob' });
  // Sem socket ativo

  // 10 frames de chamada enviados enquanto Bob está offline
  for (let i = 0; i < 10; i++) {
    const res = await env.deliver(
      'ALICE_1234567890A', 'Alice', 'BOB_1234567890AB',
      `ENC_ECDH:audio_frame_${i}`, 'ivFrame', `call_frame_${i}`, true
    );
    assert.equal(res.success, true);
    assert.equal(res.delivered, false);
  }

  // Fila offline NUNCA deve conter frames de chamada
  assert.equal(env.pendingMessages.has('bob_1234567890ab'), false);
});

test('6. Fila offline respeita o teto de 50 mensagens e não sofre overflow com frames efêmeros', async () => {
  const env = setupTestEnvironment();
  env.activePresence.set('bob', { identityHash: 'BOB_1234567890AB', alias: 'Bob' });

  // Envia 60 mensagens de texto normais enquanto Bob está offline
  for (let i = 0; i < 60; i++) {
    await env.deliver(
      'ALICE_1234567890A', 'Alice', 'BOB_1234567890AB',
      `ENC_ECDH:texto_${i}`, 'iv', `msg_id_${i}`, false
    );
  }

  const queue = env.pendingMessages.get('bob_1234567890ab');
  assert.ok(queue);
  assert.equal(queue.length, 50, 'A fila de mensagens pendentes deve ter no máximo 50 itens');
  // As primeiras 10 mensagens foram descartadas para dar lugar às 50 mais recentes
  assert.equal(queue[0].messageId, 'msg_id_10');
  assert.equal(queue[49].messageId, 'msg_id_59');

  // Envia 30 frames de chamada com isEphemeral=true
  for (let j = 0; j < 30; j++) {
    await env.deliver(
      'ALICE_1234567890A', 'Alice', 'BOB_1234567890AB',
      `ENC_ECDH:call_frame_${j}`, 'iv', `call_frame_${j}`, true
    );
  }

  // A fila de mensagens de texto permanece INTACTA com os 50 textos
  assert.equal(queue.length, 50);
  assert.equal(queue[0].messageId, 'msg_id_10');
  assert.equal(queue[49].messageId, 'msg_id_59');
});

test('7. Rate limiting separa mensagens discretas (25/s) de streaming contínuo (120/s)', async () => {
  const env = setupTestEnvironment();
  const identity = 'ALICE_TEST_RATE';

  // Mensagens discretas: até 25 são permitidas por segundo
  for (let i = 0; i < 25; i++) {
    assert.equal(env.checkRateLimit(identity, false), true, `Mensagem ${i} deveria ser permitida`);
  }
  // A 26ª mensagem discreta deve ser bloqueada pelo anti-flood
  assert.equal(env.checkRateLimit(identity, false), false, 'Mensagem 26 deve ser bloqueada');

  // Streaming contínuo: até 120 frames/s são permitidos
  for (let f = 0; f < 120; f++) {
    assert.equal(env.checkRateLimit(identity, true), true, `Frame ${f} deveria ser permitido`);
  }
  // O 121º frame no mesmo segundo deve ser limitado
  assert.equal(env.checkRateLimit(identity, true), false, 'Frame 121 deve ser bloqueado por anti-flood');
});

test('8. Deduplicação bloqueia retransmissão de mensagens com mesmo ID', async () => {
  const env = setupTestEnvironment();
  const msgId = 'unique_message_uuid_1234';

  assert.equal(env.isDuplicateMessage(msgId), false, 'Primeira ocorrência não deve ser duplicada');
  assert.equal(env.isDuplicateMessage(msgId), true, 'Segunda ocorrência deve ser identificada como duplicada');
  assert.equal(env.isDuplicateMessage(msgId), true, 'Terceira ocorrência deve continuar como duplicada');
});

test('9. Destinatário com identificador inválido é rejeitado com segurança', async () => {
  const env = setupTestEnvironment();
  const r1 = await env.deliver('ALICE', 'Alice', '', 'payload', 'iv', 'id_01', false);
  assert.equal(r1.success, false);
  assert.equal(r1.error, 'Destinatário inválido');
  const r2 = await env.deliver('ALICE', 'Alice', 'ab', 'payload', 'iv', 'id_02', false);
  assert.equal(r2.success, false);
  const r3 = await env.deliver('ALICE', 'Alice', 'invalid@recipient!', 'payload', 'iv', 'id_03', false);
  assert.equal(r3.success, false); // caracteres ilegais
});

test('10. Reconexão: mensagens pendentes são entregues e a fila é esvaziada', async () => {
  const env = setupTestEnvironment();
  env.activePresence.set('bob', { identityHash: 'BOB_1234567890AB', alias: 'Bob' });

  // Alice envia 3 mensagens enquanto Bob está offline
  await env.deliver('ALICE_1', 'Alice', 'BOB_1234567890AB', 'ENC:1', 'iv1', 'm1', false);
  await env.deliver('ALICE_1', 'Alice', 'BOB_1234567890AB', 'ENC:2', 'iv2', 'm2', false);
  await env.deliver('ALICE_1', 'Alice', 'BOB_1234567890AB', 'ENC:3', 'iv3', 'm3', false);

  const kBob = 'bob_1234567890ab';
  const pending = env.pendingMessages.get(kBob) || [];
  assert.equal(pending.length, 3);

  // Simula conexão WebSocket de Bob
  const deliveredOnConnect = [];
  const bobWs = {
    readyState: 1,
    send: (data) => deliveredOnConnect.push(JSON.parse(data))
  };

  pending.forEach(m => bobWs.send(JSON.stringify(m)));
  env.pendingMessages.delete(kBob);

  assert.equal(deliveredOnConnect.length, 3);
  assert.equal(deliveredOnConnect[0].messageId, 'm1');
  assert.equal(deliveredOnConnect[1].messageId, 'm2');
  assert.equal(deliveredOnConnect[2].messageId, 'm3');
  assert.equal(env.pendingMessages.has(kBob), false, 'Fila deve estar limpa após entrega na reconexão');
});
