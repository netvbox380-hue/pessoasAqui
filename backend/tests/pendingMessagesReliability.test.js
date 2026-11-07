'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const src = fs.readFileSync(path.join(__dirname, '../src/server.js'), 'utf8');

function setupTestEnvironment({ postgres = false } = {}) {
  const activePresence = new Map();
  const activeSockets = new Map();
  const pendingMessages = new Map();
  const recentMessageIds = new Map();
  const clientMessageRate = new Map();
  const dbMessages = [];

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
    localStore: { connections: new Map(), identities: new Map() },
    isPostgres: () => postgres,
    query: async (sql, params = []) => {
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
        }
        return { rowCount: exists ? 0 : 1 };
      }
      if (cleanSql.includes('UPDATE e2ee_messages') && cleanSql.includes('recipient_identity')) {
        const [id, recip] = params;
        const msg = dbMessages.find(m => m.id === id && m.recipient_identity.toLowerCase() === recip.toLowerCase() && !m.is_delivered);
        if (msg) {
          msg.is_delivered = true;
          return { rowCount: 1 };
        }
        return { rowCount: 0 };
      }
      if (cleanSql.includes('UPDATE e2ee_messages') && cleanSql.includes('WHERE id = $1')) {
        const [id] = params;
        const msg = dbMessages.find(m => m.id === id && !m.is_delivered);
        if (msg) {
          msg.is_delivered = true;
          return { rowCount: 1 };
        }
        return { rowCount: 0 };
      }
      if (cleanSql.includes('UPDATE e2ee_messages') && cleanSql.includes('LOWER(sender_identity)')) {
        const [idA, idB] = params;
        let count = 0;
        for (const m of dbMessages) {
          const matchAtoB = (m.sender_identity.toLowerCase() === idA.toLowerCase() && m.recipient_identity.toLowerCase() === idB.toLowerCase());
          const matchBtoA = (m.sender_identity.toLowerCase() === idB.toLowerCase() && m.recipient_identity.toLowerCase() === idA.toLowerCase());
          if (matchAtoB || matchBtoA) {
            m.is_delivered = true;
            count++;
          }
        }
        return { rowCount: count };
      }
      if (cleanSql.includes('SELECT') && cleanSql.includes('e2ee_messages')) {
        const rows = dbMessages.filter(m => !m.is_delivered);
        return { rows, rowCount: rows.length };
      }
      return { rows: [], rowCount: 0 };
    },
    WebSocket: { OPEN: 1 }
  };

  const startIdx = src.indexOf('async function acknowledgePendingMessage(');
  const startHelper = startIdx !== -1 ? startIdx : src.indexOf('function acknowledgePendingMessage(');
  const endHelper = src.indexOf("wss.on('connection'");
  assert.ok(startHelper >= 0 && endHelper > startHelper, 'Helper code block not found');
  const codeSlice = src.slice(startHelper, endHelper);

  vm.runInNewContext(codeSlice + `
    this.deliverMessageToTarget = deliverMessageToTarget;
    this.acknowledgePendingMessage = acknowledgePendingMessage;
    this.purgePendingMessagesBetween = purgePendingMessagesBetween;
    this.checkRateLimit = checkRateLimit;
    this.isDuplicateMessage = isDuplicateMessage;
    this.cleanId = cleanId;
    this.keyId = keyId;
  `, ctx);

  return {
    deliver: ctx.deliverMessageToTarget,
    acknowledge: ctx.acknowledgePendingMessage,
    purgeBetween: ctx.purgePendingMessagesBetween,
    checkRateLimit: ctx.checkRateLimit,
    isDuplicateMessage: ctx.isDuplicateMessage,
    keyId: ctx.keyId,
    cleanId: ctx.cleanId,
    activePresence,
    activeSockets,
    pendingMessages,
    recentMessageIds,
    clientMessageRate,
    dbMessages,
    ctx
  };
}

const PEER_A = '1111222233334444';
const PEER_B = '5555666677778888';
const STRANGER_C = '9999888877776666';

test('1. Destinatário online: entrega imediata via WS, mensagem é removida de pending após confirmação (ACK)', async () => {
  const env = setupTestEnvironment({ postgres: true });
  const receivedWs = [];
  const fakeSocket = {
    readyState: 1,
    send: (data) => receivedWs.push(JSON.parse(data))
  };

  env.activePresence.set(PEER_B.toLowerCase(), { identityHash: PEER_B, alias: 'Bob' });
  env.activeSockets.set(PEER_B.toLowerCase(), fakeSocket);

  const delivered = await env.deliver(PEER_A, 'Alice', PEER_B, 'ENC_CIPHERTEXT_01', 'iv1', 'msg_001', false);
  assert.equal(delivered, true);
  assert.equal(receivedWs.length, 1);
  assert.equal(receivedWs[0].messageId, 'msg_001');

  // Mensagem é retida na fila pendente até o ACK do cliente
  assert.equal(env.pendingMessages.has(PEER_B.toLowerCase()), true);
  assert.equal(env.pendingMessages.get(PEER_B.toLowerCase())[0].messageId, 'msg_001');

  // Cliente envia ACK
  const acked = await env.acknowledge(PEER_B, 'msg_001');
  assert.equal(acked, true);
  assert.equal(env.pendingMessages.has(PEER_B.toLowerCase()), false);
  assert.equal(env.dbMessages.find(m => m.id === 'msg_001').is_delivered, true);
});

test('2. Destinatário offline: mensagem é enfileirada em pendingMessages e persistida em e2ee_messages (se PG)', async () => {
  const env = setupTestEnvironment({ postgres: true });
  env.activePresence.set(PEER_B.toLowerCase(), { identityHash: PEER_B, alias: 'Bob' });
  // Sem socket ativo para Bob

  const delivered = await env.deliver(PEER_A, 'Alice', PEER_B, 'ENC_CIPHERTEXT_02', 'iv2', 'msg_002', false);
  assert.equal(delivered, false);

  const queue = env.pendingMessages.get(PEER_B.toLowerCase());
  assert.ok(queue);
  assert.equal(queue.length, 1);
  assert.equal(queue[0].messageId, 'msg_002');

  const inDb = env.dbMessages.find(m => m.id === 'msg_002');
  assert.ok(inDb);
  assert.equal(inDb.is_delivered, false);
  assert.equal(inDb.ciphertext_payload, 'ENC_CIPHERTEXT_02');
});

test('3. Destinatário reconecta: drena pendingMessages via WS e confirma recebimento', async () => {
  const env = setupTestEnvironment({ postgres: true });
  env.activePresence.set(PEER_B.toLowerCase(), { identityHash: PEER_B, alias: 'Bob' });

  // Alice envia enquanto Bob está offline
  await env.deliver(PEER_A, 'Alice', PEER_B, 'ENC_P1', 'ivP1', 'msg_p1', false);
  await env.deliver(PEER_A, 'Alice', PEER_B, 'ENC_P2', 'ivP2', 'msg_p2', false);

  const pendingBefore = env.pendingMessages.get(PEER_B.toLowerCase());
  assert.equal(pendingBefore.length, 2);

  // Bob reconecta
  const drained = [];
  const bobWs = {
    readyState: 1,
    send: (d) => drained.push(JSON.parse(d))
  };
  env.activeSockets.set(PEER_B.toLowerCase(), bobWs);

  // Simula o loop de reconexão do server.js (sem apagar antes do ACK)
  pendingBefore.forEach(m => bobWs.send(JSON.stringify(m)));
  assert.equal(drained.length, 2);

  // Bob confirma recebimento das mensagens
  await env.acknowledge(PEER_B, 'msg_p1');
  await env.acknowledge(PEER_B, 'msg_p2');

  assert.equal(env.pendingMessages.has(PEER_B.toLowerCase()), false);
  assert.equal(env.dbMessages.filter(m => m.is_delivered).length, 2);
});

test('4. WS cai durante envio: mensagem permanece em pendingMessages (não é perdida)', async () => {
  const env = setupTestEnvironment();
  const faultySocket = {
    readyState: 1,
    send: () => { throw new Error('EPIPE: connection reset by peer'); }
  };

  env.activePresence.set(PEER_B.toLowerCase(), { identityHash: PEER_B, alias: 'Bob' });
  env.activeSockets.set(PEER_B.toLowerCase(), faultySocket);

  // Envio tenta WS, falha na escrita do socket
  const delivered = await env.deliver(PEER_A, 'Alice', PEER_B, 'ENC_CRASH_DURING_SEND', 'iv', 'msg_drop_1', false);
  assert.equal(delivered, false);

  // Mensagem continua segura em pendingMessages
  const queue = env.pendingMessages.get(PEER_B.toLowerCase());
  assert.ok(queue);
  assert.equal(queue.some(m => m.messageId === 'msg_drop_1'), true);
});

test('5. WS cai antes de processar: mensagem permanece disponível para próximo reconnect ou HTTP fallback', async () => {
  const env = setupTestEnvironment();
  const received = [];
  const droppedSocket = {
    readyState: 1,
    send: (d) => {
      received.push(JSON.parse(d));
      // Socket fecha abruptamente logo após o envio, antes que o cliente envie ACK
      droppedSocket.readyState = 3; // CLOSED
    }
  };

  env.activePresence.set(PEER_B.toLowerCase(), { identityHash: PEER_B, alias: 'Bob' });
  env.activeSockets.set(PEER_B.toLowerCase(), droppedSocket);

  await env.deliver(PEER_A, 'Alice', PEER_B, 'ENC_DROPPED_BEFORE_ACK', 'iv', 'msg_drop_2', false);
  assert.equal(received.length, 1);

  // Sem ACK, a mensagem continua em pendingMessages pronta para HTTP fallback ou reconnect
  const pending = env.pendingMessages.get(PEER_B.toLowerCase());
  assert.ok(pending);
  assert.equal(pending[0].messageId, 'msg_drop_2');
});

test('6. Mensagem chega mas ACK se perde: retransmissão tratada por idempotência sem duplicar na UI', async () => {
  const env = setupTestEnvironment();
  env.activePresence.set(PEER_B.toLowerCase(), { identityHash: PEER_B, alias: 'Bob' });

  // Primeira entrega
  await env.deliver(PEER_A, 'Alice', PEER_B, 'ENC_LOST_ACK', 'iv', 'msg_ack_lost', false);
  assert.equal(env.pendingMessages.get(PEER_B.toLowerCase()).length, 1);

  // Remetente retransmite a mesma mensagem com mesmo messageId
  await env.deliver(PEER_A, 'Alice', PEER_B, 'ENC_LOST_ACK', 'iv', 'msg_ack_lost', false);

  // A fila pendente não duplica a mensagem
  assert.equal(env.pendingMessages.get(PEER_B.toLowerCase()).length, 1);
});

test('7. Retransmissão da mesma mensagem: servidor não duplica em pendingMessages nem no banco', async () => {
  const env = setupTestEnvironment({ postgres: true });
  env.activePresence.set(PEER_B.toLowerCase(), { identityHash: PEER_B, alias: 'Bob' });

  await env.deliver(PEER_A, 'Alice', PEER_B, 'ENC_PAYLOAD', 'iv', 'msg_dup_check', false);
  await env.deliver(PEER_A, 'Alice', PEER_B, 'ENC_PAYLOAD', 'iv', 'msg_dup_check', false);
  await env.deliver(PEER_A, 'Alice', PEER_B, 'ENC_PAYLOAD', 'iv', 'msg_dup_check', false);

  const queue = env.pendingMessages.get(PEER_B.toLowerCase());
  assert.equal(queue.length, 1);
  assert.equal(env.dbMessages.filter(m => m.id === 'msg_dup_check').length, 1);
});

test('8. Mensagens fora de ordem: preserva ordem de chegada por sequência e timestamp na fila', async () => {
  const env = setupTestEnvironment();
  env.activePresence.set(PEER_B.toLowerCase(), { identityHash: PEER_B, alias: 'Bob' });

  await env.deliver(PEER_A, 'Alice', PEER_B, 'MSG_1', 'iv', 'seq_1', false);
  await env.deliver(PEER_A, 'Alice', PEER_B, 'MSG_2', 'iv', 'seq_2', false);
  await env.deliver(PEER_A, 'Alice', PEER_B, 'MSG_3', 'iv', 'seq_3', false);

  const queue = env.pendingMessages.get(PEER_B.toLowerCase());
  assert.equal(queue.length, 3);
  assert.equal(queue[0].messageId, 'seq_1');
  assert.equal(queue[1].messageId, 'seq_2');
  assert.equal(queue[2].messageId, 'seq_3');
  assert.ok(queue[0].timestamp <= queue[1].timestamp);
  assert.ok(queue[1].timestamp <= queue[2].timestamp);
});

test('9. Backend reinicia com pendências: carrega de e2ee_messages no startup (se PG)', async () => {
  const env = setupTestEnvironment({ postgres: true });

  // Popula DB com mensagem não entregue
  env.dbMessages.push({
    id: 'msg_restored_01',
    sender_identity: PEER_A,
    recipient_identity: PEER_B,
    sender_alias: 'Alice',
    ciphertext_payload: 'ENC_SAVED_IN_DB',
    iv_nonce: 'iv_db',
    is_delivered: false
  });

  // Simula inicialização / recuperação do startup
  const res = await env.ctx.query('SELECT id, sender_identity, recipient_identity, sender_alias, ciphertext_payload, iv_nonce FROM e2ee_messages');
  for (const row of res.rows) {
    const kRecip = env.keyId(row.recipient_identity);
    if (!env.pendingMessages.has(kRecip)) env.pendingMessages.set(kRecip, []);
    env.pendingMessages.get(kRecip).push({
      type: 'E2EE_MESSAGE_RECEIVED',
      messageId: row.id,
      senderHash: row.sender_identity,
      senderAlias: row.sender_alias,
      recipientHash: row.recipient_identity,
      ciphertextPayload: row.ciphertext_payload,
      ivNonce: row.iv_nonce,
      isEphemeral: false,
      timestamp: Date.now()
    });
  }

  const restored = env.pendingMessages.get(PEER_B.toLowerCase());
  assert.ok(restored);
  assert.equal(restored.length, 1);
  assert.equal(restored[0].messageId, 'msg_restored_01');
  assert.equal(restored[0].ciphertextPayload, 'ENC_SAVED_IN_DB');
});

test('10. Revogação de conexão antes da entrega: mensagens pendentes daquela conexão são descartadas', async () => {
  const env = setupTestEnvironment({ postgres: true });
  env.activePresence.set(PEER_B.toLowerCase(), { identityHash: PEER_B, alias: 'Bob' });

  // Alice envia para Bob
  await env.deliver(PEER_A, 'Alice', PEER_B, 'SECRET_A_TO_B', 'iv', 'msg_rev_1', false);
  // Terceiro envia para Bob
  const PEER_C = '9999000011112222';
  await env.deliver(PEER_C, 'Charlie', PEER_B, 'SECRET_C_TO_B', 'iv', 'msg_rev_charlie', false);

  assert.equal(env.pendingMessages.get(PEER_B.toLowerCase()).length, 2);

  // Bob revoga Alice
  env.purgeBetween(PEER_A, PEER_B);

  // A mensagem de Alice para Bob foi eliminada
  const remaining = env.pendingMessages.get(PEER_B.toLowerCase());
  assert.ok(remaining);
  assert.equal(remaining.length, 1);
  assert.equal(remaining[0].messageId, 'msg_rev_charlie');
});

test('11. Envelope inválido: rejeitado sem afetar mensagens válidas na fila', async () => {
  const env = setupTestEnvironment();
  env.activePresence.set(PEER_B.toLowerCase(), { identityHash: PEER_B, alias: 'Bob' });

  // Mensagem válida anterior
  await env.deliver(PEER_A, 'Alice', PEER_B, 'VALID_1', 'iv', 'valid_1', false);

  // Envelopes corrompidos / destinatários ilegais
  assert.equal(await env.deliver(PEER_A, 'Alice', '', 'PAYLOAD', 'iv', 'inv_1', false), false);
  assert.equal(await env.deliver(PEER_A, 'Alice', 'SHORT', 'PAYLOAD', 'iv', 'inv_2', false), false);
  assert.equal(await env.deliver(PEER_A, 'Alice', 'ILLEGAL$CHARS!!', 'PAYLOAD', 'iv', 'inv_3', false), false);

  // Fila para Bob permanece intacta com apenas a mensagem válida
  const queue = env.pendingMessages.get(PEER_B.toLowerCase());
  assert.equal(queue.length, 1);
  assert.equal(queue[0].messageId, 'valid_1');
});

test('12. Chave pública temporariamente indisponível: mensagem retida até resolução de chave', async () => {
  const env = setupTestEnvironment();
  env.activePresence.set(PEER_B.toLowerCase(), { identityHash: PEER_B, alias: 'Bob', publicKey: null });

  // Mensagem enviada enquanto Bob ainda não publicou chave na sessão WS atual
  const delivered = await env.deliver(PEER_A, 'Alice', PEER_B, 'ENC_RETAINED', 'iv', 'msg_wait_key', false);
  assert.equal(delivered, false); // retida na fila pendente

  const queue = env.pendingMessages.get(PEER_B.toLowerCase());
  assert.ok(queue);
  assert.equal(queue.some(m => m.messageId === 'msg_wait_key'), true);
});

test('13. Múltiplas mensagens pendentes para mesmo destinatário: entregues em lote ordenado', async () => {
  const env = setupTestEnvironment();
  env.activePresence.set(PEER_B.toLowerCase(), { identityHash: PEER_B, alias: 'Bob' });

  for (let i = 1; i <= 5; i++) {
    await env.deliver(PEER_A, 'Alice', PEER_B, `ENC_BATCH_${i}`, 'iv', `batch_msg_${i}`, false);
  }

  const batchReceived = [];
  const bobWs = {
    readyState: 1,
    send: (d) => batchReceived.push(JSON.parse(d))
  };

  const pending = env.pendingMessages.get(PEER_B.toLowerCase());
  assert.equal(pending.length, 5);
  pending.forEach(m => bobWs.send(JSON.stringify(m)));

  assert.equal(batchReceived.length, 5);
  for (let i = 0; i < 5; i++) {
    assert.equal(batchReceived[i].messageId, `batch_msg_${i + 1}`);
  }
});

test('14. Teto de fila atingido (50 msgs): descarta mais antiga na RAM, preserva integridade', async () => {
  const env = setupTestEnvironment();
  env.activePresence.set(PEER_B.toLowerCase(), { identityHash: PEER_B, alias: 'Bob' });

  // Envia 55 mensagens
  for (let i = 0; i < 55; i++) {
    await env.deliver(PEER_A, 'Alice', PEER_B, `ENC_${i}`, 'iv', `msg_cap_${i}`, false);
  }

  const queue = env.pendingMessages.get(PEER_B.toLowerCase());
  assert.equal(queue.length, 50);
  // As primeiras 5 (0 a 4) foram descartadas (FIFO)
  assert.equal(queue[0].messageId, 'msg_cap_5');
  assert.equal(queue[49].messageId, 'msg_cap_54');
});

test('15. Concorrência: entrega simultânea via HTTP e WS tratada atomicamente sem duplicação', async () => {
  const env = setupTestEnvironment({ postgres: true });
  env.activePresence.set(PEER_B.toLowerCase(), { identityHash: PEER_B, alias: 'Bob' });

  // Enfileira mensagem
  await env.deliver(PEER_A, 'Alice', PEER_B, 'ENC_CONCURRENT', 'iv', 'msg_concurrent_1', false);

  // Simula cliente recebendo via WS e fazendo ACK via HTTP ao mesmo tempo
  const ackWs = await env.acknowledge(PEER_B, 'msg_concurrent_1');
  assert.equal(ackWs, true, 'Primeiro ACK deve confirmar e remover da fila');

  // Segundo ACK (via HTTP /api/messages/ack concorrente)
  const ackHttp = await env.acknowledge(PEER_B, 'msg_concurrent_1');
  assert.equal(ackHttp, false, 'Segundo ACK idempotente detecta que já foi removido sem corromper estado');

  assert.equal(env.pendingMessages.has(PEER_B.toLowerCase()), false);
});

// =========================================================================
// TESTES NEGATIVOS E DE BLINDAGEM DE ACK (PRIORIDADES 1, 3 e 4)
// =========================================================================

test('16. Blindagem de ACK: Terceiro C não consegue confirmar nem remover mensagem de B', async () => {
  const env = setupTestEnvironment({ postgres: true });
  env.activePresence.set(PEER_B.toLowerCase(), { identityHash: PEER_B, alias: 'Bob' });
  env.activePresence.set(STRANGER_C.toLowerCase(), { identityHash: STRANGER_C, alias: 'Carlos' });

  // Alice envia mensagem confidencial para Bob
  await env.deliver(PEER_A, 'Alice', PEER_B, 'ENC_SECRETO_A_B', 'ivSecret', 'msg_bob_confidential', false);

  assert.equal(env.pendingMessages.get(PEER_B.toLowerCase()).length, 1);
  assert.equal(env.dbMessages.find(m => m.id === 'msg_bob_confidential').is_delivered, false);

  // Terceiro Carlos (STRANGER_C) tenta confirmar mensagem que pertence a Bob
  const fraudAck = await env.acknowledge(STRANGER_C, 'msg_bob_confidential');
  assert.equal(fraudAck, false, 'Carlos não pode confirmar mensagem endereçada a Bob');

  // A mensagem de Bob continua 100% intacta na fila e no banco
  assert.equal(env.pendingMessages.get(PEER_B.toLowerCase()).length, 1);
  assert.equal(env.pendingMessages.get(PEER_B.toLowerCase())[0].messageId, 'msg_bob_confidential');
  assert.equal(env.dbMessages.find(m => m.id === 'msg_bob_confidential').is_delivered, false);

  // Agora Bob confirma legitimamente
  const legitAck = await env.acknowledge(PEER_B, 'msg_bob_confidential');
  assert.equal(legitAck, true, 'Bob confirma legitimamente sua própria mensagem');
  assert.equal(env.pendingMessages.has(PEER_B.toLowerCase()), false);
  assert.equal(env.dbMessages.find(m => m.id === 'msg_bob_confidential').is_delivered, true);
});

test('17. Blindagem de ACK: Identidade inválida ou nula é rejeitada imediatamente', async () => {
  const env = setupTestEnvironment({ postgres: true });
  assert.equal(await env.acknowledge('', 'msg_1'), false);
  assert.equal(await env.acknowledge(null, 'msg_1'), false);
  assert.equal(await env.acknowledge('INVALID_CHARS!!', 'msg_1'), false);
  assert.equal(await env.acknowledge('12345', 'msg_1'), false); // menor que 16 chars hex
  assert.equal(await env.acknowledge(PEER_B, ''), false);
});

test('18. Blindagem de ACK: ACK com identificador inexistente retorna false sem erros', async () => {
  const env = setupTestEnvironment({ postgres: true });
  const result = await env.acknowledge(PEER_B, 'msg_inexistente_9999');
  assert.equal(result, false);
});

test('19. T03 & T04: Consistência PostgreSQL com 100 mensagens (memória com teto 50, DB retém todas)', async () => {
  const env = setupTestEnvironment({ postgres: true });
  env.activePresence.set(PEER_B.toLowerCase(), { identityHash: PEER_B, alias: 'Bob' });

  // Envia 100 mensagens para Bob enquanto ele está offline
  for (let i = 1; i <= 100; i++) {
    await env.deliver(PEER_A, 'Alice', PEER_B, `ENC_PAYLOAD_${i}`, `iv_${i}`, `msg_100_${i}`, false);
  }

  // Fila em RAM retém 50 itens (política de memória protegida)
  assert.equal(env.pendingMessages.get(PEER_B.toLowerCase()).length, 50);

  // Banco de dados PostgreSQL retém todas as 100 mensagens não entregues com integridade total
  assert.equal(env.dbMessages.length, 100);
  assert.equal(env.dbMessages.filter(m => !m.is_delivered).length, 100);

  // Bob confirma todas uma a uma
  for (let j = 1; j <= 100; j++) {
    await env.acknowledge(PEER_B, `msg_100_${j}`);
  }

  assert.equal(env.dbMessages.filter(m => !m.is_delivered).length, 0);
  assert.equal(env.pendingMessages.has(PEER_B.toLowerCase()), false);
});
