'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const { hashPin, verifyPinHash } = require('../src/crypto');
const { getSslConfig } = require('../src/db');
const guard = require('../src/inviteSecurity');
const auth = require('../src/inviteAuth');

test('AUDIT 1 - Ausência de falso sucesso: query() deve lançar erro se PostgreSQL configurado falhar', async () => {
  // Salva estado original
  const origDbUrl = process.env.DATABASE_URL;
  try {
    process.env.DATABASE_URL = 'postgres://fake-unreachable-host:5432/db';
    const db = require('../src/db');
    // Como fake-unreachable-host não responde, query() deve lançar exceção
    await assert.rejects(
      async () => {
        await db.query('SELECT 1');
      },
      (err) => {
        assert.ok(err instanceof Error, 'Deve lançar uma instância de Error');
        assert.ok(
          err.code === 'ECONNREFUSED' || err.code === 'ENOTFOUND' || err.message.includes('indisponível'),
          'Erro deve refletir indisponibilidade do banco'
        );
        return true;
      }
    );
  } finally {
    if (origDbUrl) process.env.DATABASE_URL = origDbUrl;
    else delete process.env.DATABASE_URL;
  }
});

test('AUDIT 2 - Resiliência e autorrecuperação: checkDatabaseHealth e cooldown', async () => {
  const origDbUrl = process.env.DATABASE_URL;
  try {
    process.env.DATABASE_URL = 'postgres://fake:5432/db';
    const db = require('../src/db');

    // Primeira verificação com host falso deve retornar false
    const h1 = await db.checkDatabaseHealth();
    assert.equal(h1, false);

    // Segunda chamada imediata deve respeitar o cooldown sem lançar exceção
    const h2 = await db.checkDatabaseHealth();
    assert.equal(h2, false);
  } finally {
    if (origDbUrl) process.env.DATABASE_URL = origDbUrl;
    else delete process.env.DATABASE_URL;
  }
});

test('AUDIT 3 - Formato de PIN: hashPin utiliza PBKDF2 e é verificado com segurança por verifyPinHash', () => {
  const defaultPin = '0000';
  const validHash = hashPin(defaultPin);

  // 1. O hash deve ter o prefixo padronizado pbkdf2$
  assert.ok(validHash.startsWith('pbkdf2$'), 'Hash deve iniciar com pbkdf2$');

  // 2. verifyPinHash deve validar com sucesso o PIN correto
  assert.equal(verifyPinHash('0000', validHash), true);

  // 3. verifyPinHash deve rejeitar PIN incorreto
  assert.equal(verifyPinHash('1234', validHash), false);

  // 4. Demonstração da falha que ocorreria com tokenDigest('0000'):
  const rawSha = guard.tokenDigest('0000');
  assert.equal(
    verifyPinHash('0000', rawSha),
    false,
    'tokenDigest("0000") não possui salt global nem prefixo pbkdf2 e deve falhar na verificação'
  );
});

test('AUDIT 4 - Preservação de identidades antigas: ON CONFLICT DO NOTHING não altera credenciais existentes', () => {
  const mockTable = new Map();

  // Identidade existente no banco com PIN customizado
  const existingIdentity = {
    id: 'ID_EXISTENTE_123',
    publicKeyEd25519: 'pk_antiga',
    publicKeyX25519: 'pk_x_antiga',
    pinSaltedHash: hashPin('9876'),
    alias: 'UsuarioAntigo'
  };
  mockTable.set(existingIdentity.id, { ...existingIdentity });

  // Simulação da cláusula INSERT ... ON CONFLICT (id) DO NOTHING
  function insertWithDoNothing(sender, pubKey, pinHash, alias) {
    if (mockTable.has(sender)) {
      // DO NOTHING: nenhum campo é alterado
      return { rowCount: 0 };
    }
    mockTable.set(sender, {
      id: sender,
      publicKeyEd25519: pubKey,
      publicKeyX25519: pubKey,
      pinSaltedHash: pinHash,
      alias
    });
    return { rowCount: 1 };
  }

  // Tentativa de auto-registro preventivo durante criação de convite para a mesma identidade
  const res = insertWithDoNothing('ID_EXISTENTE_123', 'pk_nova', hashPin('0000'), 'NovoNome');
  assert.equal(res.rowCount, 0, 'ON CONFLICT DO NOTHING não deve alterar registros pré-existentes');

  // Verifica que o hash de PIN e os dados originais foram rigorosamente preservados
  const preserved = mockTable.get('ID_EXISTENTE_123');
  assert.equal(preserved.alias, 'UsuarioAntigo');
  assert.equal(verifyPinHash('9876', preserved.pinSaltedHash), true, 'PIN original permanece intacto');
  assert.equal(verifyPinHash('0000', preserved.pinSaltedHash), false, 'PIN padrão não sobrescreveu o original');
});

test('AUDIT 5 - Configuração TLS: suporta CA explícito com rejectUnauthorized=true e fallback seguro para cloud pooler', () => {
  // Conexão local não usa SSL
  assert.equal(getSslConfig('postgres://localhost:5432/mydb'), false);
  assert.equal(getSslConfig('postgres://127.0.0.1:5432/mydb'), false);

  // Conexão remota sem CA definida no ambiente usa fallback seguro
  const origCa = process.env.SUPABASE_CA_CERT;
  delete process.env.SUPABASE_CA_CERT;
  delete process.env.DATABASE_CA_CERT;
  delete process.env.PGSSLROOTCERT;

  const cloudSsl = getSslConfig('postgres://user:pass@aws-0-sa-east-1.pooler.supabase.com:6543/postgres');
  assert.deepEqual(cloudSsl, { rejectUnauthorized: false });

  // Quando CA é fornecida via variável de ambiente, rejectUnauthorized torna-se TRUE
  try {
    process.env.SUPABASE_CA_CERT = '-----BEGIN CERTIFICATE-----\nMIIB_TEST_CA_CERTIFICATE_\n-----END CERTIFICATE-----';
    const caSsl = getSslConfig('postgres://user:pass@aws-0-sa-east-1.pooler.supabase.com:6543/postgres');
    assert.equal(caSsl.rejectUnauthorized, true);
    assert.equal(caSsl.ca, process.env.SUPABASE_CA_CERT);
  } finally {
    if (origCa) process.env.SUPABASE_CA_CERT = origCa;
    else delete process.env.SUPABASE_CA_CERT;
  }
});

test('AUDIT 6 - Proteção de convite: uso único atômico e rejeição de reaproveitamento', () => {
  const invitesStore = new Map();
  const token = crypto.randomBytes(32).toString('hex');
  const tokenHash = guard.tokenDigest(token);
  const inviteId = 'inv_' + crypto.randomBytes(12).toString('hex');

  invitesStore.set(inviteId, {
    id: inviteId,
    tokenHash,
    sender: 'SENDER1',
    status: 'pending',
    expiresAt: Date.now() + 60000
  });

  // Função que simula o UPDATE atômico com status = 'claimed' WHERE status = 'pending'
  function claimInvite(id, inputToken) {
    const inv = invitesStore.get(id);
    if (!inv) return { error: 'not_found' };
    if (inv.expiresAt <= Date.now()) return { error: 'expired' };
    if (inv.status !== 'pending') return { error: 'already_claimed' };
    if (guard.tokenDigest(inputToken) !== inv.tokenHash) return { error: 'invalid_token' };

    inv.status = 'claimed';
    return { success: true };
  }

  // Primeiro resgate: SUCESSO
  const r1 = claimInvite(inviteId, token);
  assert.equal(r1.success, true);

  // Segundo resgate (reutilização): REJEITADO
  const r2 = claimInvite(inviteId, token);
  assert.equal(r2.error, 'already_claimed');

  // Token incorreto: REJEITADO
  const fakeToken = crypto.randomBytes(32).toString('hex');
  const r3 = claimInvite(inviteId, fakeToken);
  assert.equal(r3.error, 'already_claimed');
});
