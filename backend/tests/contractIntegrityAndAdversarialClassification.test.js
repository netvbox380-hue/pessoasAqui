'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const path = require('node:path');
const fs = require('node:fs');
const vm = require('node:vm');

const src = fs.readFileSync(path.join(__dirname, '../src/server.js'), 'utf8');
const { identityFromPublicKey } = require('../src/identityAuth');
const auth = require('../src/inviteAuth');

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

function signHttpProof(user, action, targetId = '', tokenHash = '', customNonce = null) {
  const timestamp = Date.now();
  const nonce = customNonce || crypto.randomUUID();
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

function setupAdversarialTestEnv(options = {}) {
  const activePresence = new Map();
  const activeSockets = new Map();
  const pendingMessages = new Map();
  const recentMessageIds = new Map();
  const clientMessageRate = new Map();
  const dbMessages = [];
  const localDurableMessages = new Map();
  const usedNonces = new Set();
  const keyBindings = new Map();

  const localConnections = new Map();
  function addMutual(a, b) {
    const kA = a.toLowerCase();
    const kB = b.toLowerCase();
    localConnections.set(`${kA}->${kB}`, { fromIdentity: a, toIdentity: b, isMutual: true });
    localConnections.set(`${kB}->${kA}`, { fromIdentity: b, toIdentity: a, isMutual: true });
  }

  const alice = createCryptoUser('Alice');
  const bob = createCryptoUser('Bob');
  const stranger = createCryptoUser('Estranho');
  addMutual(alice.id, bob.id);

  keyBindings.set(alice.id, alice.pub);
  keyBindings.set(bob.id, bob.pub);
  keyBindings.set(stranger.id, stranger.pub);

  let isDbOnline = options.postgres !== false;

  const ctx = {
    console,
    Date,
    Set,
    Map,
    String,
    Boolean,
    Buffer,
    crypto,
    activePresence,
    activeSockets,
    pendingMessages,
    recentMessageIds,
    clientMessageRate,
    inFlightDeliveries: new Map(),
    localDurableMessages,
    revokedConnections: new Set(),
    process: { env: { DATABASE_URL: 'postgres://simulated_adversarial' } },
    isDatabaseConfigured: () => isDbOnline,
    localStore: { connections: localConnections },
    isPostgres: () => isDbOnline,
    query: async (sql, params) => {
      if (!isDbOnline) throw new Error('Database connection failed');
      const q = String(sql).toUpperCase();

      if (q.includes('FROM IDENTITY_KEY_BINDINGS')) {
        const id = params[0];
        const pub = keyBindings.get(id);
        return { rows: pub ? [{ public_key_spki: pub }] : [] };
      }

      if (q.includes('INSERT INTO RECOVERY_PROOF_NONCES')) {
        const nonce = params[2];
        if (usedNonces.has(nonce)) return { rowCount: 0, rows: [] };
        usedNonces.add(nonce);
        return { rowCount: 1, rows: [{ nonce }] };
      }

      if (q.includes('FROM CONNECTIONS')) {
        const id1 = cleanId(params[0]).toLowerCase();
        const id2 = cleanId(params[1]).toLowerCase();
        const c1 = localConnections.get(`${id1}->${id2}`);
        const c2 = localConnections.get(`${id2}->${id1}`);
        const rows = [];
        if (c1) rows.push({ from_identity: c1.fromIdentity, to_identity: c1.toIdentity, is_mutual: c1.isMutual });
        if (c2) rows.push({ from_identity: c2.fromIdentity, to_identity: c2.toIdentity, is_mutual: c2.isMutual });
        return { rowCount: rows.length, rows };
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
      if (!isDbOnline) throw new Error('Database connection failed');
      return cb(async (sql, params) => {
        return ctx.query(sql, params);
      });
    },
    WebSocket: { OPEN: 1 },
    verifyActiveInviteProof: async (operation, identity, alias, inviteId, tokenHash, proof) => {
      let binding = null;
      if (isDbOnline) {
        const found = await ctx.query('SELECT public_key_spki FROM identity_key_bindings WHERE identity_id=$1', [identity]);
        binding = found.rows[0]?.public_key_spki || null;
      }
      return auth.verifyInviteIdentityProof(operation, identity, alias, inviteId, tokenHash, proof, Date.now(), binding);
    }
  };

  function cleanId(raw) {
    if (!raw) return '';
    return String(raw).replace(/^peer-/i, '').trim().toUpperCase();
  }

  const startHelper = src.indexOf('function checkRateLimit(');
  const endHelper = src.indexOf("wss.on('connection'");
  assert.ok(startHelper >= 0 && endHelper > startHelper, 'Helper code block not found');
  const codeSlice = src.slice(startHelper, endHelper);

  const startVerify = src.indexOf('function digestHttpParts(');
  const endVerify = src.indexOf("app.post('/api/presence/heartbeat'", startVerify);
  assert.ok(startVerify >= 0 && endVerify > startVerify, 'Verify block not found');
  const verifySlice = src.slice(startVerify, endVerify);

  vm.runInNewContext(codeSlice + '\n' + verifySlice + `
    this.deliverMessageToTarget = deliverMessageToTarget;
    this.checkRateLimit = checkRateLimit;
    this.isDuplicateMessage = isDuplicateMessage;
    this.cleanId = cleanId;
    this.keyId = keyId;
    this.pairKey = pairKey;
    this.verifySignedHttp = verifySignedHttp;
    this.digestHttpParts = digestHttpParts;
    this.revokedConnections = revokedConnections;
  `, ctx);

  // Simulador da rota HTTP /api/messages/send
  async function simulateHttpSendMessage(body) {
    const req = { body };
    let status = 200;
    let jsonResult = null;
    const res = {
      status: (code) => {
        status = code;
        return {
          json: (data) => { jsonResult = data; return data; }
        };
      },
      json: (data) => {
        jsonResult = data;
        return data;
      }
    };

    const cleanSender = ctx.cleanId(body.senderHash);
    const cleanRecipient = ctx.cleanId(body.recipientHash);
    const declaredEphemeral = Boolean(body.isEphemeral);
    const declaredOperation = String(body.operation || (declaredEphemeral ? (String(body.messageId || '').startsWith('call_frame_') ? 'CALL_STREAM' : 'CALL_SIGNAL') : 'MESSAGE_SEND'));
    const ephemContract = declaredEphemeral ? 'ephemeral' : 'durable';

    const digestV2 = ctx.digestHttpParts([
      String(body.senderHash || ''), String(body.recipientHash || ''), String(body.ciphertextPayload || ''),
      String(body.ivNonce || ''), String(body.messageId || ''), String(body.senderAlias || ''),
      ephemContract, declaredOperation
    ]);

    const digestV1 = ctx.digestHttpParts([
      String(body.senderHash || ''), String(body.recipientHash || ''), String(body.ciphertextPayload || ''),
      String(body.ivNonce || ''), String(body.messageId || ''), String(body.senderAlias || '')
    ]);

    if (!await ctx.verifySignedHttp(req, res, 'MESSAGE_SEND', cleanSender, cleanRecipient, [digestV2, digestV1])) {
      return { statusCode: status, data: jsonResult };
    }

    const isV2Verified = (req.verifiedTokenHash === digestV2);
    const isEphemeralContract = isV2Verified ? declaredEphemeral : false;
    const effectiveOperation = isV2Verified ? declaredOperation : 'MESSAGE_SEND';

    const isPlaintextCall = typeof body.ciphertextPayload === 'string' && (
      body.ciphertextPayload.startsWith('[CALL_AUDIO_FRAME:') ||
      body.ciphertextPayload.startsWith('[CALL_VIDEO_FRAME:') ||
      body.ciphertextPayload.startsWith('[CALL_INIT:') ||
      body.ciphertextPayload.startsWith('[CALL_ACCEPT]') ||
      body.ciphertextPayload.startsWith('[CALL_ACK]') ||
      body.ciphertextPayload.startsWith('[CALL_END]') ||
      body.ciphertextPayload.startsWith('[ICE_CANDIDATE:')
    );

    const isEffectiveEphemeral = effectiveOperation !== 'MESSAGE_SEND' && (isEphemeralContract || isPlaintextCall);
    const isStreamRate = isEffectiveEphemeral && (
      effectiveOperation === 'CALL_STREAM' ||
      String(body.messageId || '').startsWith('call_frame_') ||
      (typeof body.ciphertextPayload === 'string' && (
        body.ciphertextPayload.startsWith('[CALL_AUDIO_FRAME:') ||
        body.ciphertextPayload.startsWith('[CALL_VIDEO_FRAME:')
      ))
    );

    if (!ctx.checkRateLimit(cleanSender, isStreamRate)) {
      return { statusCode: 429, data: { error: 'Muitas requisições. Limite de envio por segundo excedido.' } };
    }

    const msgId = body.messageId || `${cleanSender}_${Date.now()}_${Math.random()}`;
    const deliveryResult = await ctx.deliverMessageToTarget(
      cleanSender, body.senderAlias, body.recipientHash, body.ciphertextPayload, body.ivNonce, msgId, isEffectiveEphemeral, effectiveOperation
    );

    if (!deliveryResult.success) {
      return { statusCode: deliveryResult.statusCode || 400, data: { error: deliveryResult.error, code: deliveryResult.code } };
    }
    return { statusCode: 200, data: { success: true, delivered: deliveryResult.delivered, persisted: deliveryResult.persisted } };
  }

  // Simulador do manipulador WebSocket E2EE_MESSAGE
  async function simulateWsE2eeMessage(authenticatedUser, msg) {
    const { recipientHash, ciphertextPayload, ivNonce, messageId, isEphemeral, operation } = msg;
    if (!ciphertextPayload || (typeof ciphertextPayload === 'string' && ciphertextPayload.length > 25 * 1024 * 1024)) {
      return { success: false, error: 'Payload excessivo ou inválido' };
    }

    const legacyCall = typeof ciphertextPayload === 'string' && (
      ciphertextPayload.startsWith('[CALL_AUDIO_FRAME:') ||
      ciphertextPayload.startsWith('[CALL_VIDEO_FRAME:') ||
      ciphertextPayload.startsWith('[CALL_INIT:') ||
      ciphertextPayload.startsWith('[CALL_ACCEPT]') ||
      ciphertextPayload.startsWith('[CALL_ACK]') ||
      ciphertextPayload.startsWith('[CALL_END]') ||
      ciphertextPayload.startsWith('[ICE_CANDIDATE:')
    );
    const declaredOp = String(operation || (Boolean(isEphemeral) || legacyCall
      ? (String(messageId || '').startsWith('call_frame_') ? 'CALL_STREAM' : 'CALL_SIGNAL')
      : 'MESSAGE_SEND'));
    const isCallOp = declaredOp === 'CALL_STREAM' || declaredOp === 'CALL_SIGNAL';
    const isPlaintextCall = typeof ciphertextPayload === 'string' && (
      ciphertextPayload.startsWith('[CALL_AUDIO_FRAME:') ||
      ciphertextPayload.startsWith('[CALL_VIDEO_FRAME:') ||
      ciphertextPayload.startsWith('[CALL_INIT:') ||
      ciphertextPayload.startsWith('[CALL_ACCEPT]') ||
      ciphertextPayload.startsWith('[CALL_ACK]') ||
      ciphertextPayload.startsWith('[CALL_END]') ||
      ciphertextPayload.startsWith('[ICE_CANDIDATE:')
    );

    const isExplicitEphemeral = Boolean(isEphemeral) && isCallOp;
    const isEphemeralContract = isExplicitEphemeral || isPlaintextCall;

    const isStreamRate = isEphemeralContract && (
      declaredOp === 'CALL_STREAM' ||
      String(messageId || '').startsWith('call_frame_') ||
      (typeof ciphertextPayload === 'string' && (
        ciphertextPayload.startsWith('[CALL_AUDIO_FRAME:') ||
        ciphertextPayload.startsWith('[CALL_VIDEO_FRAME:')
      ))
    );
    if (!ctx.checkRateLimit(authenticatedUser.id, isStreamRate)) {
      return { success: false, error: 'Rate limit excedido' };
    }

    const msgId = messageId || `${authenticatedUser.id}_${Date.now()}_${Math.random()}`;
    return ctx.deliverMessageToTarget(authenticatedUser.id, authenticatedUser.alias, recipientHash, ciphertextPayload, ivNonce, msgId, isEphemeralContract, declaredOp);
  }

  return {
    alice,
    bob,
    stranger,
    dbMessages,
    localDurableMessages,
    pendingMessages,
    revokedConnections: ctx.revokedConnections,
    pairKey: ctx.pairKey,
    setDbOnline: (online) => { isDbOnline = online; },
    simulateHttpSendMessage,
    simulateWsE2eeMessage
  };
}

test('1. Adversarial: Alterar isEphemeral após assinatura HTTP é rejeitado com 401', async () => {
  const env = setupAdversarialTestEnv();
  const msgId = `msg_tamper_${Date.now()}`;
  const payload = 'ENC_ECDH:mensagem_secreta_permanente';
  const iv = 'iv_tamper_1';

  // Alice assina legitimamente uma mensagem durável (isEphemeral = false, operation = 'MESSAGE_SEND')
  const digestAlice = digestHttpParts([
    env.alice.id, env.bob.id, payload, iv, msgId, env.alice.alias, 'durable', 'MESSAGE_SEND'
  ]);
  const proof = signHttpProof(env.alice, 'MESSAGE_SEND', env.bob.id, digestAlice);

  // Atacante altera o corpo HTTP para isEphemeral = true tentando induzir descarte
  const tamperedBody = {
    senderHash: env.alice.id,
    recipientHash: env.bob.id,
    ciphertextPayload: payload,
    ivNonce: iv,
    senderAlias: env.alice.alias,
    messageId: msgId,
    operation: 'MESSAGE_SEND',
    isEphemeral: true, // ADULTERADO!
    identityProof: proof
  };

  const res = await env.simulateHttpSendMessage(tamperedBody);
  assert.equal(res.statusCode, 401, 'Adulteração de isEphemeral DEVE invalidar prova criptográfica (HTTP 401)');
  assert.equal(env.dbMessages.length, 0);
});

test('2. Adversarial: Reutilizar assinatura com classificação diferente é bloqueado', async () => {
  const env = setupAdversarialTestEnv();
  const msgId = `call_sig_${Date.now()}`;
  const payload = 'ENC_ECDH:call_init_encrypted';
  const iv = 'iv_call_1';

  // Prova assinada para sinalização efêmera
  const digestCall = digestHttpParts([
    env.alice.id, env.bob.id, payload, iv, msgId, env.alice.alias, 'ephemeral', 'CALL_SIGNAL'
  ]);
  const proof = signHttpProof(env.alice, 'MESSAGE_SEND', env.bob.id, digestCall);

  // Atacante tenta reenviar a mesma prova alegando ser mensagem durável permanente
  const tamperedBody = {
    senderHash: env.alice.id,
    recipientHash: env.bob.id,
    ciphertextPayload: payload,
    ivNonce: iv,
    senderAlias: env.alice.alias,
    messageId: msgId,
    operation: 'MESSAGE_SEND', // ADULTERADO!
    isEphemeral: false,        // ADULTERADO!
    identityProof: proof
  };

  const res = await env.simulateHttpSendMessage(tamperedBody);
  assert.equal(res.statusCode, 401);
});

test('3. Adversarial: Alterar tipo de operação após assinatura é rejeitado com 401', async () => {
  const env = setupAdversarialTestEnv();
  const msgId = `call_frame_${Date.now()}`;
  const payload = 'ENC_ECDH:audio_frame_chunk';
  const iv = 'iv_audio';

  // Alice assina CALL_STREAM efêmero
  const digestStream = digestHttpParts([
    env.alice.id, env.bob.id, payload, iv, msgId, env.alice.alias, 'ephemeral', 'CALL_STREAM'
  ]);
  const proof = signHttpProof(env.alice, 'MESSAGE_SEND', env.bob.id, digestStream);

  // Atacante adultera operação para MESSAGE_SEND
  const tamperedBody = {
    senderHash: env.alice.id,
    recipientHash: env.bob.id,
    ciphertextPayload: payload,
    ivNonce: iv,
    senderAlias: env.alice.alias,
    messageId: msgId,
    operation: 'MESSAGE_SEND', // ADULTERADO!
    isEphemeral: true,
    identityProof: proof
  };

  const res = await env.simulateHttpSendMessage(tamperedBody);
  assert.equal(res.statusCode, 401);
});

test('4. Adversarial WebSocket: Operação MESSAGE_SEND com flag isEphemeral = true é forçada a PERMANENTE', async () => {
  const env = setupAdversarialTestEnv();
  const msgId = `msg_ws_tamper_${Date.now()}`;

  // Cliente WebSocket tenta enviar MESSAGE_SEND declarando isEphemeral: true e prefixo ephem_
  const res = await env.simulateWsE2eeMessage(env.alice, {
    recipientHash: env.bob.id,
    ciphertextPayload: 'ENC_ECDH:chat_privado_confidencial',
    ivNonce: 'iv_ws',
    messageId: `ephem_${msgId}`,
    isEphemeral: true,
    operation: 'MESSAGE_SEND'
  });

  assert.equal(res.success, true);
  assert.equal(res.persisted, true, 'MESSAGE_SEND NUNCA pode ser descartada como efêmera no WebSocket');
  assert.equal(env.dbMessages.some(m => m.id === `ephem_${msgId}`), true);
});

test('5. Adversarial: Prefixo call_frame_ em mensagem permanente assina durável e é persistida no PostgreSQL', async () => {
  const env = setupAdversarialTestEnv();
  const msgId = `call_frame_spoofed_${Date.now()}`;
  const payload = 'ENC_ECDH:mensagem_com_prefixo_mascarado';
  const iv = 'iv_call_spoof';

  const digest = digestHttpParts([
    env.alice.id, env.bob.id, payload, iv, msgId, env.alice.alias, 'durable', 'MESSAGE_SEND'
  ]);
  const proof = signHttpProof(env.alice, 'MESSAGE_SEND', env.bob.id, digest);

  const res = await env.simulateHttpSendMessage({
    senderHash: env.alice.id,
    recipientHash: env.bob.id,
    ciphertextPayload: payload,
    ivNonce: iv,
    senderAlias: env.alice.alias,
    messageId: msgId,
    operation: 'MESSAGE_SEND',
    isEphemeral: false,
    identityProof: proof
  });

  assert.equal(res.statusCode, 200);
  assert.equal(res.data.persisted, true);
  assert.equal(env.dbMessages.some(m => m.id === msgId), true);
});

test('6. Formato E2EE desconhecido ou futuro sem contrato efêmero: persistido duravelmente sem quebra de sigilo', async () => {
  const env = setupAdversarialTestEnv();
  const msgId = `msg_unknown_envelope_${Date.now()}`;
  const unknownCiphertext = 'ENC_ECDH_V4_POLY1305:binary_blob_data_opaque';
  const iv = 'iv_opaque';

  const digest = digestHttpParts([
    env.alice.id, env.bob.id, unknownCiphertext, iv, msgId, env.alice.alias, 'durable', 'MESSAGE_SEND'
  ]);
  const proof = signHttpProof(env.alice, 'MESSAGE_SEND', env.bob.id, digest);

  const res = await env.simulateHttpSendMessage({
    senderHash: env.alice.id,
    recipientHash: env.bob.id,
    ciphertextPayload: unknownCiphertext,
    ivNonce: iv,
    senderAlias: env.alice.alias,
    messageId: msgId,
    operation: 'MESSAGE_SEND',
    isEphemeral: false,
    identityProof: proof
  });

  assert.equal(res.statusCode, 200);
  assert.equal(res.data.persisted, true);
  assert.equal(env.dbMessages.some(m => m.id === msgId && m.payload === unknownCiphertext), true);
});

test('7. Frames legítimos de streaming de chamada (CALL_STREAM): despachados sem persistência no banco', async () => {
  const env = setupAdversarialTestEnv();
  const msgId = `call_frame_audio_${Date.now()}`;

  const res = await env.simulateWsE2eeMessage(env.alice, {
    recipientHash: env.bob.id,
    ciphertextPayload: 'ENC_ECDH:audio_g711_frame_data',
    ivNonce: 'iv_stream',
    messageId: msgId,
    isEphemeral: true,
    operation: 'CALL_STREAM'
  });

  assert.equal(res.success, true);
  assert.equal(res.persisted, false);
  assert.equal(env.dbMessages.length, 0);
});

test('8. Sinalização de chamada e ICE Candidate legítimos: despachados em tempo real sem poluir o histórico', async () => {
  const env = setupAdversarialTestEnv();
  const msgId = `ephem_call_ice_${Date.now()}`;

  const res = await env.simulateWsE2eeMessage(env.alice, {
    recipientHash: env.bob.id,
    ciphertextPayload: '[ICE_CANDIDATE:candidate:udp:192.168.1.10:5004]',
    ivNonce: 'iv_ice',
    messageId: msgId,
    isEphemeral: true,
    operation: 'CALL_SIGNAL'
  });

  assert.equal(res.success, true);
  assert.equal(res.persisted, false);
  assert.equal(env.dbMessages.length, 0);
});

test('9. Repetição de mensagem (Replay e Anti-colisão): idempotência confirmada e colisões rejeitadas', async () => {
  const env = setupAdversarialTestEnv();
  const msgId = `msg_replay_id_${Date.now()}`;
  const payload = 'ENC_ECDH:conteudo_original';
  const iv = 'iv_rep';
  const nonce = crypto.randomUUID();

  const digest = digestHttpParts([
    env.alice.id, env.bob.id, payload, iv, msgId, env.alice.alias, 'durable', 'MESSAGE_SEND'
  ]);
  const proof = signHttpProof(env.alice, 'MESSAGE_SEND', env.bob.id, digest, nonce);

  // Primeiro envio: sucesso 200
  const res1 = await env.simulateHttpSendMessage({
    senderHash: env.alice.id,
    recipientHash: env.bob.id,
    ciphertextPayload: payload,
    ivNonce: iv,
    senderAlias: env.alice.alias,
    messageId: msgId,
    operation: 'MESSAGE_SEND',
    isEphemeral: false,
    identityProof: proof
  });
  assert.equal(res1.statusCode, 200);

  // Tentativa de replay com o mesmo nonce: bloqueado com HTTP 409
  const resReplay = await env.simulateHttpSendMessage({
    senderHash: env.alice.id,
    recipientHash: env.bob.id,
    ciphertextPayload: payload,
    ivNonce: iv,
    senderAlias: env.alice.alias,
    messageId: msgId,
    operation: 'MESSAGE_SEND',
    isEphemeral: false,
    identityProof: proof
  });
  assert.equal(resReplay.statusCode, 409, 'Replay com mesmo nonce DEVE retornar 409');
});

test('10. Chamada após revogação: bloqueada imediatamente com status 403 (CONNECTION_REVOKED)', async () => {
  const env = setupAdversarialTestEnv();
  const pairKey = env.pairKey(env.alice.id, env.bob.id);
  env.revokedConnections.add(pairKey);

  const res = await env.simulateWsE2eeMessage(env.alice, {
    recipientHash: env.bob.id,
    ciphertextPayload: 'ENC_ECDH:call_init',
    ivNonce: 'iv',
    messageId: `ephem_call_${Date.now()}`,
    isEphemeral: true,
    operation: 'CALL_SIGNAL'
  });

  assert.equal(res.success, false);
  assert.equal(res.statusCode, 403);
  assert.equal(res.code, 'CONNECTION_REVOKED');
});

test('11. Chamada com PostgreSQL / Supabase indisponível: fail-closed imediato com HTTP 503 (DB_UNAVAILABLE)', async () => {
  const env = setupAdversarialTestEnv();
  env.setDbOnline(false); // Simula queda do banco de dados

  const res = await env.simulateWsE2eeMessage(env.alice, {
    recipientHash: env.bob.id,
    ciphertextPayload: 'ENC_ECDH:call_init',
    ivNonce: 'iv',
    messageId: `ephem_call_${Date.now()}`,
    isEphemeral: true,
    operation: 'CALL_SIGNAL'
  });

  assert.equal(res.success, false);
  assert.equal(res.statusCode, 503);
  assert.equal(res.code, 'DB_UNAVAILABLE');
});

test('12. Compatibilidade com clientes legados (Digest V1 6 partes): aceito com fail-closed para DURÁVEL', async () => {
  const env = setupAdversarialTestEnv();
  const msgId = `msg_legacy_${Date.now()}`;
  const payload = 'ENC_ECDH:mensagem_cliente_legado';
  const iv = 'iv_leg';

  // Cliente legado assina APENAS 6 partes (sem efemeridade)
  const legacyDigest = digestHttpParts([
    env.alice.id, env.bob.id, payload, iv, msgId, env.alice.alias
  ]);
  const legacyProof = signHttpProof(env.alice, 'MESSAGE_SEND', env.bob.id, legacyDigest);

  // Cliente legado tenta passar isEphemeral: true no body (ou interceptor adiciona)
  const legacyBody = {
    senderHash: env.alice.id,
    recipientHash: env.bob.id,
    ciphertextPayload: payload,
    ivNonce: iv,
    senderAlias: env.alice.alias,
    messageId: msgId,
    isEphemeral: true, // Tentativa em body
    identityProof: legacyProof
  };

  const res = await env.simulateHttpSendMessage(legacyBody);
  assert.equal(res.statusCode, 200, 'Assinatura legada V1 deve ser aceita com sucesso');
  // Mas como a assinatura V1 NÃO assina efemeridade, é estritamente FORÇADA a durável!
  assert.equal(res.data.persisted, true, 'Assinatura V1 NUNCA pode ser efêmera (Fail-Closed to Durable)');
  assert.equal(env.dbMessages.some(m => m.id === msgId), true);
});

test('13. Digest V1 legado com payload CALL_INIT em texto claro permanece durável (não contorna contrato)', async () => {
  const env = setupAdversarialTestEnv();
  const msgId = `legacy_plaintext_call_${Date.now()}`;
  const payload = '[CALL_INIT:legacy-attacker-prefix]';
  const iv = 'iv_legacy_plaintext';
  const digestV1 = digestHttpParts([env.alice.id, env.bob.id, payload, iv, msgId, env.alice.alias]);
  const proof = signHttpProof(env.alice, 'MESSAGE_SEND', env.bob.id, digestV1);
  const result = await env.simulateHttpSendMessage({
    senderHash: env.alice.id,
    recipientHash: env.bob.id,
    ciphertextPayload: payload,
    ivNonce: iv,
    messageId: msgId,
    senderAlias: env.alice.alias,
    isEphemeral: true,
    operation: 'CALL_SIGNAL',
    identityProof: proof
  });
  assert.equal(result.statusCode, 200);
  assert.equal(result.data.persisted, true, 'Digest V1 não protege operation e deve forçar persistência');
  assert.equal(env.dbMessages.some(m => m.id === msgId), true);
});

test('14. WS com operation MESSAGE_SEND e payload CALL_INIT legado nunca ignora persistência', async () => {
  const env = setupAdversarialTestEnv();
  const msgId = `ws_plaintext_spoof_${Date.now()}`;
  const result = await env.simulateWsE2eeMessage(env.alice, {
    recipientHash: env.bob.id,
    ciphertextPayload: '[CALL_INIT:spoofed-as-chat]',
    ivNonce: 'iv_ws_spoof',
    messageId: msgId,
    isEphemeral: true,
    operation: 'MESSAGE_SEND'
  });
  assert.equal(result.success, true);
  assert.equal(result.persisted, true, 'MESSAGE_SEND explícito nunca pode ser efêmero');
  assert.equal(env.dbMessages.some(m => m.id === msgId), true);
});
