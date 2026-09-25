const assert = require('assert');
const http = require('http');
const WebSocket = require('ws');
const { app, server } = require('./src/server');
const { calculateLockoutDurationSeconds, hashPin } = require('./src/crypto');

async function runTests() {
  console.log('🧪 Iniciando Bateria de Testes Automatizados E2E do Backend PessoasAqui...');

  // 1. Inicia o servidor em porta dinâmica livre
  await new Promise((resolve) => server.listen(0, resolve));
  const port = server.address().port;
  const baseUrl = `http://localhost:${port}`;
  const wsUrl = `ws://localhost:${port}`;
  console.log(`[Test Server] Rodando na porta temporária ${port}`);

  async function postJson(endpoint, data) {
    const res = await fetch(`${baseUrl}${endpoint}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(data)
    });
    return { status: res.status, json: await res.json() };
  }

  try {
    // -------------------------------------------------------------
    // Teste 1: Health Check
    // -------------------------------------------------------------
    console.log('\n--- Teste 1: Health Check (GET /) ---');
    const resRoot = await fetch(`${baseUrl}/`);
    const jsonRoot = await resRoot.json();
    assert.strictEqual(resRoot.status, 200);
    assert.strictEqual(jsonRoot.app, 'PessoasAqui Backend');
    console.log('✅ Health check validado com sucesso.');

    // -------------------------------------------------------------
    // Teste 2: Registro de Identidade Técnica Criptográfica
    // -------------------------------------------------------------
    console.log('\n--- Teste 2: Registro de Identidades ---');
    const userA = {
      identityHash: 'hash-alice-1234567890abcdef',
      publicKeyEd25519: 'ed25519-alice-pubkey',
      publicKeyX25519: 'x25519-alice-pubkey',
      pin: '2B5C',
      alias: 'Alice'
    };
    const userB = {
      identityHash: 'hash-bob-0987654321fedcba',
      publicKeyEd25519: 'ed25519-bob-pubkey',
      publicKeyX25519: 'x25519-bob-pubkey',
      pin: '7F9A',
      alias: 'Bob'
    };

    const regA = await postJson('/api/identities/register', userA);
    assert.strictEqual(regA.status, 200);
    assert.strictEqual(regA.json.success, true);

    const regB = await postJson('/api/identities/register', userB);
    assert.strictEqual(regB.status, 200);
    assert.strictEqual(regB.json.success, true);
    console.log('✅ Identidades registradas com chaves assimétricas e PIN salgado.');

    // -------------------------------------------------------------
    // Teste 3: Marcação Unilateral e Conexão Mútua
    // -------------------------------------------------------------
    console.log('\n--- Teste 3: Marcação e Conexão Mútua ---');
    // Alice marca Bob (unilateral)
    const mark1 = await postJson('/api/connections/mark', {
      fromIdentity: userA.identityHash,
      toIdentity: userB.identityHash
    });
    assert.strictEqual(mark1.json.isMutualConnection, false, 'Primeira marcação deve ser unilateral');

    // Bob marca Alice (recíproca -> vira mútua)
    const mark2 = await postJson('/api/connections/mark', {
      fromIdentity: userB.identityHash,
      toIdentity: userA.identityHash
    });
    assert.strictEqual(mark2.json.isMutualConnection, true, 'Segunda marcação deve fechar vínculo mútuo');
    console.log('✅ Regra de descoberta 10m -> vínculo mútuo validada.');

    // -------------------------------------------------------------
    // Teste 4: Emissão de Autorização Familiar ("Entre na sua")
    // -------------------------------------------------------------
    console.log('\n--- Teste 4: Emissão de Autorização de Recuperação Familiar ---');
    const issueRes = await postJson('/api/family/recovery/issue', {
      targetIdentity: userA.identityHash,
      familyIdentity: userB.identityHash
    });
    assert.strictEqual(issueRes.status, 200);
    assert.ok(issueRes.json.recoveryKey.startsWith('PA-REC-'));
    const recoveryKey = issueRes.json.recoveryKey;
    console.log(`✅ Chave emitida com sucesso: ${recoveryKey}`);

    // -------------------------------------------------------------
    // Teste 5: Escada Progressiva de Bloqueio por Erros de PIN
    // -------------------------------------------------------------
    console.log('\n--- Teste 5: Escada Progressiva de Bloqueios no PIN ---');
    // 1ª tentativa errada: sem bloqueio
    const claimErr1 = await postJson('/api/family/recovery/claim', {
      recoveryKey,
      pin: 'WRONG1'
    });
    assert.strictEqual(claimErr1.status, 401);
    assert.strictEqual(claimErr1.json.lockoutSeconds, 0);

    // 2ª tentativa errada: sem bloqueio
    const claimErr2 = await postJson('/api/family/recovery/claim', {
      recoveryKey,
      pin: 'WRONG2'
    });
    assert.strictEqual(claimErr2.status, 401);
    assert.strictEqual(claimErr2.json.lockoutSeconds, 0);

    // 3ª tentativa errada: bloqueio de 5 horas (18000s)
    const claimErr3 = await postJson('/api/family/recovery/claim', {
      recoveryKey,
      pin: 'WRONG3'
    });
    assert.strictEqual(claimErr3.status, 401);
    assert.strictEqual(claimErr3.json.lockoutSeconds, 5 * 3600);
    console.log('✅ 3ª tentativa disparou bloqueio de segurança de 5 horas com precisão.');

    // Tentativa subsequente durante bloqueio ativo retorna 429
    const claimDuringLock = await postJson('/api/family/recovery/claim', {
      recoveryKey,
      pin: userA.pin
    });
    assert.strictEqual(claimDuringLock.status, 429);
    console.log('✅ Tentativa bloqueada durante lockout retornou HTTP 429.');

    // -------------------------------------------------------------
    // Teste 6: WebSockets - Relay Cego E2EE & Revogação de Sessão
    // -------------------------------------------------------------
    console.log('\n--- Teste 6: WebSockets (Relay E2EE & Revogação) ---');
    // Cria nova autorização de recuperação para testar claim com sucesso
    const issueRes2 = await postJson('/api/family/recovery/issue', {
      targetIdentity: userA.identityHash,
      familyIdentity: userB.identityHash
    });
    const validRecoveryKey = issueRes2.json.recoveryKey;

    // Conecta Celular Antigo da Alice
    const wsAliceOld = new WebSocket(wsUrl);
    await new Promise((resolve) => wsAliceOld.on('open', resolve));

    // Conecta Bob
    const wsBob = new WebSocket(wsUrl);
    await new Promise((resolve) => wsBob.on('open', resolve));

    // Autentica Alice Antiga
    wsAliceOld.send(JSON.stringify({ type: 'AUTH', identityHash: userA.identityHash }));
    // Autentica Bob
    wsBob.send(JSON.stringify({ type: 'AUTH', identityHash: userB.identityHash }));

    await new Promise((r) => setTimeout(r, 200));

    // Bob envia mensagem E2EE para Alice
    const receivedPromise = new Promise((resolve) => {
      wsAliceOld.on('message', (raw) => {
        const msg = JSON.parse(raw.toString());
        if (msg.type === 'E2EE_MESSAGE_RECEIVED') {
          resolve(msg);
        }
      });
    });

    wsBob.send(JSON.stringify({
      type: 'E2EE_MESSAGE',
      recipientHash: userA.identityHash,
      ciphertextPayload: 'BASE64_ENCRYPTED_TEXT_CIPHER',
      ivNonce: 'RANDOM_IV_NONCE_123'
    }));

    const receivedE2ee = await receivedPromise;
    assert.strictEqual(receivedE2ee.senderHash, userB.identityHash);
    assert.strictEqual(receivedE2ee.ciphertextPayload, 'BASE64_ENCRYPTED_TEXT_CIPHER');
    console.log('✅ Relay cego E2EE entregue com sucesso via WebSocket.');

    // Agora, novo celular da Alice reivindica a identidade com o PIN correto!
    // O celular antigo da Alice DEVE receber SESSION_REVOKED
    const revocationPromise = new Promise((resolve) => {
      wsAliceOld.on('message', (raw) => {
        const msg = JSON.parse(raw.toString());
        if (msg.type === 'SESSION_REVOKED') {
          resolve(msg);
        }
      });
    });

    const claimSuccess = await postJson('/api/family/recovery/claim', {
      recoveryKey: validRecoveryKey,
      pin: userA.pin,
      newDeviceFingerprint: 'pixel-9-pro-fingerprint'
    });

    assert.strictEqual(claimSuccess.status, 200);
    assert.strictEqual(claimSuccess.json.success, true);

    const revocationMsg = await revocationPromise;
    assert.strictEqual(revocationMsg.type, 'SESSION_REVOKED');
    console.log('✅ Regra 18 cumprida: Dispositivo antigo revogado em tempo real via WebSocket!');

    try { wsAliceOld.close(); } catch (_) {}
    try { wsBob.close(); } catch (_) {}

    // -------------------------------------------------------------
    // Teste 7: Convite Criptografado de Uso Único (Conexão Mútua à Distância)
    // -------------------------------------------------------------
    console.log('\n--- Teste 7: Convite Criptografado de Uso Único ---');
    const userC = {
      identityHash: 'hash-charlie-1122334455667788',
      publicKeyEd25519: 'ed25519-charlie-pubkey',
      pin: '9900',
      alias: 'Charlie'
    };
    await postJson('/api/identities/register', userC);

    // Charlie gera convite criptografado
    const createInviteRes = await postJson('/api/invites/create', {
      senderIdentity: userC.identityHash,
      senderAlias: userC.alias
    });
    assert.strictEqual(createInviteRes.status, 200);
    assert.ok(createInviteRes.json.inviteId.startsWith('inv_'));
    assert.ok(createInviteRes.json.token);
    assert.ok(createInviteRes.json.signature, 'Convite deve possuir assinatura criptográfica HMAC');
    assert.ok(createInviteRes.json.inviteUrl.includes('&sig='));
    const { inviteId, token, signature, expiresAt } = createInviteRes.json;
    console.log(`✅ Convite gerado com assinatura criptográfica: ${inviteId}`);

    // Tentativa 1: Charlie tenta resgatar o próprio convite (deve falhar 400)
    const selfRedeem = await postJson('/api/invites/redeem', {
      inviteId,
      token,
      receiverIdentity: userC.identityHash,
      receiverAlias: userC.alias
    });
    assert.strictEqual(selfRedeem.status, 400);
    console.log('✅ Bloqueio de auto-resgate validado.');

    // Tentativa 2: Token adulterado (deve falhar 403)
    const badTokenRedeem = await postJson('/api/invites/redeem', {
      inviteId,
      token: 'TOKEN_ADULTERADO',
      receiverIdentity: userA.identityHash,
      receiverAlias: userA.alias
    });
    assert.strictEqual(badTokenRedeem.status, 403);
    console.log('✅ Rejeição de token adulterado validada.');

    // Tentativa 3: Alice resgata o convite legitimamente
    const redeemRes = await postJson('/api/invites/redeem', {
      inviteId,
      token,
      receiverIdentity: userA.identityHash,
      receiverAlias: userA.alias,
      senderIdentity: userC.identityHash,
      senderAlias: userC.alias,
      signature,
      expiresAt
    });
    assert.strictEqual(redeemRes.status, 200);
    assert.strictEqual(redeemRes.json.success, true);
    console.log('✅ Convite resgatado e conexão mútua à distância estabelecida com sucesso.');

    // Tentativa 4: Segundo resgate do mesmo convite (deve falhar 410 Gone - Uso Único)
    const secondRedeem = await postJson('/api/invites/redeem', {
      inviteId,
      token,
      receiverIdentity: userB.identityHash,
      receiverAlias: userB.alias
    });
    assert.strictEqual(secondRedeem.status, 410);
    console.log('✅ Regra de Uso Único estrito validada (reutilização rejeitada com 410).');

    console.log('\n🎉 TODOS OS TESTES DO BACKEND PASSARAM COM 100% DE SUCESSO!\n');
  } finally {
    server.close();
  }
}

runTests().catch((err) => {
  console.error('\n❌ ERRO NOS TESTES:', err);
  process.exit(1);
});
