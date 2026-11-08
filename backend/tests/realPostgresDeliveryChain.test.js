const test = require('node:test');
const assert = require('node:assert/strict');
const { spawn } = require('child_process');
const { Client, Pool } = require('pg');
const path = require('path');

const PG_PORT = 54333;
const PG_DATA = path.resolve(__dirname, '../.pg_isolated_data');
const PG_BIN = 'C:/Program Files/PostgreSQL/18/bin/postgres.exe';
const CONNECTION_STRING = `postgresql://postgres@127.0.0.1:${PG_PORT}/postgres`;

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

test('SUÍTE DE INTEGRAÇÃO REAL COM POSTGRESQL 18 ISOLADO', async (t) => {
  // Inicializa servidor PostgreSQL 18 real isolado
  pgProcess = await startPostgresServer();
  assert.ok(pgProcess, 'Processo do PostgreSQL 18 deve estar ativo');

  const pool = new Pool({
    connectionString: CONNECTION_STRING,
    max: 10,
    connectionTimeoutMillis: 5000
  });

  // Prepara o esquema de produção oficial
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
        identity_a VARCHAR(64) NOT NULL,
        identity_b VARCHAR(64) NOT NULL,
        is_mutual BOOLEAN DEFAULT FALSE,
        created_at TIMESTAMPTZ DEFAULT NOW(),
        PRIMARY KEY (identity_a, identity_b)
      );

      CREATE TABLE IF NOT EXISTS identity_key_bindings (
        identity_id VARCHAR(64) PRIMARY KEY,
        public_key_spki TEXT NOT NULL,
        created_at TIMESTAMPTZ DEFAULT NOW()
      );

      TRUNCATE TABLE e2ee_messages, connections, identity_key_bindings;
    `);
  } finally {
    setupClient.release();
  }

  // 1. INSERT e confirmação de commit
  await t.test('1. INSERT e confirmação de commit durável', async () => {
    const client = await pool.connect();
    try {
      const msgId = 'real_pg_msg_001';
      const insertRes = await client.query(
        `INSERT INTO e2ee_messages (id, sender_identity, recipient_identity, sender_alias, ciphertext_payload, iv_nonce, is_delivered)
         VALUES ($1, $2, $3, $4, $5, $6, FALSE)
         RETURNING id, is_delivered, created_at`,
        [msgId, 'ALICE_REAL_HASH_123', 'BOB_REAL_HASH_456', 'Alice', 'CIPHERTEXT_AES_GCM_001', 'IV_NONCE_001']
      );
      assert.equal(insertRes.rowCount, 1);
      assert.equal(insertRes.rows[0].id, msgId);
      assert.equal(insertRes.rows[0].is_delivered, false);

      // Comprova persistência física via leitura isolada
      const selectRes = await client.query('SELECT * FROM e2ee_messages WHERE id = $1', [msgId]);
      assert.equal(selectRes.rowCount, 1);
      assert.equal(selectRes.rows[0].ciphertext_payload, 'CIPHERTEXT_AES_GCM_001');
    } finally {
      client.release();
    }
  });

  // 2. Conflito de chave primária
  await t.test('2. Conflito de chave primária com ON CONFLICT (id) DO NOTHING', async () => {
    const client = await pool.connect();
    try {
      const msgId = 'real_pg_msg_001'; // ID existente
      const conflictRes = await client.query(
        `INSERT INTO e2ee_messages (id, sender_identity, recipient_identity, sender_alias, ciphertext_payload, iv_nonce, is_delivered)
         VALUES ($1, $2, $3, $4, $5, $6, FALSE)
         ON CONFLICT (id) DO NOTHING`,
        [msgId, 'ALICE_REAL_HASH_123', 'BOB_REAL_HASH_456', 'Alice', 'CIPHERTEXT_AES_GCM_001', 'IV_NONCE_001']
      );
      assert.equal(conflictRes.rowCount, 0, 'Conflito de chave primária não deve inserir nova linha');
    } finally {
      client.release();
    }
  });

  // 3. Duas inserções simultâneas
  await t.test('3. Duas inserções simultâneas com concorrência real no PostgreSQL', async () => {
    const msgIdA = 'real_pg_concurrent_A';
    const msgIdB = 'real_pg_concurrent_B';

    const [resA, resB] = await Promise.all([
      pool.query(
        `INSERT INTO e2ee_messages (id, sender_identity, recipient_identity, sender_alias, ciphertext_payload, iv_nonce, is_delivered)
         VALUES ($1, $2, $3, $4, $5, $6, FALSE)`,
        [msgIdA, 'ALICE_111', 'BOB_222', 'Alice', 'CIPHER_A', 'IV_A']
      ),
      pool.query(
        `INSERT INTO e2ee_messages (id, sender_identity, recipient_identity, sender_alias, ciphertext_payload, iv_nonce, is_delivered)
         VALUES ($1, $2, $3, $4, $5, $6, FALSE)`,
        [msgIdB, 'ALICE_111', 'BOB_222', 'Alice', 'CIPHER_B', 'IV_B']
      )
    ]);

    assert.equal(resA.rowCount, 1);
    assert.equal(resB.rowCount, 1);
  });

  // 4. Falha de conexão
  await t.test('4. Falha de conexão gera erro capturável e não encerra processo', async () => {
    let clientErrorCaptured = null;
    const isolatedClient = new Client({ connectionString: CONNECTION_STRING });
    isolatedClient.on('error', (err) => {
      clientErrorCaptured = err;
    });
    await isolatedClient.connect();
    // Força destruição abrupta do socket
    isolatedClient.connection.stream.destroy();

    await assert.rejects(async () => {
      await isolatedClient.query('SELECT 1');
    }, /Connection terminated|closed|destroyed/i);

    assert.ok(clientErrorCaptured, 'Erro de conexão deve ser capturado pelo listener de erro');
  });

  // 5. Reconexão
  await t.test('5. Reconexão bem-sucedida após falha', async () => {
    const freshClient = new Client({ connectionString: CONNECTION_STRING });
    await freshClient.connect();
    const res = await freshClient.query('SELECT 1 as connected');
    assert.equal(res.rows[0].connected, 1);
    await freshClient.end();
  });

  // 6. ACK durante concorrência
  await t.test('6. ACK durante concorrência simultânea no PostgreSQL', async () => {
    const msgId = 'real_pg_ack_concurrent';
    await pool.query(
      `INSERT INTO e2ee_messages (id, sender_identity, recipient_identity, sender_alias, ciphertext_payload, iv_nonce, is_delivered)
       VALUES ($1, $2, $3, $4, $5, $6, FALSE)`,
      [msgId, 'ALICE_111', 'BOB_222', 'Alice', 'CIPHER_ACK', 'IV_ACK']
    );

    const [ack1, ack2] = await Promise.all([
      pool.query(
        `UPDATE e2ee_messages SET is_delivered = TRUE, delivered_at = NOW() WHERE id = $1 AND is_delivered = FALSE`,
        [msgId]
      ),
      pool.query(
        `UPDATE e2ee_messages SET is_delivered = TRUE, delivered_at = NOW() WHERE id = $1 AND is_delivered = FALSE`,
        [msgId]
      )
    ]);

    // Exatamente uma das transações realiza o UPDATE de FALSE para TRUE; a outra é no-op idempotente
    const totalUpdated = ack1.rowCount + ack2.rowCount;
    assert.equal(totalUpdated, 1, 'Apenas uma atualização altera o estado inicial; concorrência atômica protegida');

    const check = await pool.query('SELECT is_delivered FROM e2ee_messages WHERE id = $1', [msgId]);
    assert.equal(check.rows[0].is_delivered, true);
  });

  // 7. Reinicialização do servidor PostgreSQL
  await t.test('7. Reinicialização do servidor PostgreSQL e sobrevivência física dos dados', async () => {
    const persistCheckId = 'real_pg_reboot_persist_check';
    await pool.query(
      `INSERT INTO e2ee_messages (id, sender_identity, recipient_identity, sender_alias, ciphertext_payload, iv_nonce, is_delivered)
       VALUES ($1, $2, $3, $4, $5, $6, FALSE)`,
      [persistCheckId, 'ALICE_REBOOT', 'BOB_REBOOT', 'Alice', 'CIPHER_REBOOT', 'IV_REBOOT']
    );

    // Encerra pool e mata o servidor PostgreSQL
    await pool.end();
    await stopPostgresServer(pgProcess);

    // Reinicia o servidor PostgreSQL sobre o mesmo diretório de dados
    pgProcess = await startPostgresServer();
    assert.ok(pgProcess, 'PostgreSQL reiniciado');

    // Conecta novo cliente após reinicialização
    const newPool = new Pool({ connectionString: CONNECTION_STRING });
    const restored = await newPool.query('SELECT * FROM e2ee_messages WHERE id = $1', [persistCheckId]);
    assert.equal(restored.rowCount, 1);
    assert.equal(restored.rows[0].ciphertext_payload, 'CIPHER_REBOOT');
    await newPool.end();
  });

  // 8. Recuperação de pendências após reinicialização
  await t.test('8. Recuperação de pendências não entregues após restart do banco', async () => {
    const recoveryPool = new Pool({ connectionString: CONNECTION_STRING });
    const pendingQuery = await recoveryPool.query(
      `SELECT id, sender_identity, recipient_identity, ciphertext_payload 
       FROM e2ee_messages 
       WHERE is_delivered = FALSE 
       ORDER BY created_at ASC`
    );
    assert.ok(pendingQuery.rowCount > 0, 'Deve recuperar mensagens pendentes');
    assert.ok(pendingQuery.rows.some(r => r.id === 'real_pg_msg_001'));
    await recoveryPool.end();
  });

  // 9. Revogação de vínculo mútuo no banco
  await t.test('9. Revogação durável de vínculo mútuo na tabela connections', async () => {
    const connPool = new Pool({ connectionString: CONNECTION_STRING });
    await connPool.query(
      `INSERT INTO connections (identity_a, identity_b, is_mutual) VALUES ($1, $2, TRUE)
       ON CONFLICT (identity_a, identity_b) DO UPDATE SET is_mutual = TRUE`,
      ['PEER_A', 'PEER_B']
    );

    let state = await connPool.query('SELECT is_mutual FROM connections WHERE identity_a = $1 AND identity_b = $2', ['PEER_A', 'PEER_B']);
    assert.equal(state.rows[0].is_mutual, true);

    // Executa revogação
    await connPool.query('UPDATE connections SET is_mutual = FALSE WHERE identity_a = $1 AND identity_b = $2', ['PEER_A', 'PEER_B']);

    state = await connPool.query('SELECT is_mutual FROM connections WHERE identity_a = $1 AND identity_b = $2', ['PEER_A', 'PEER_B']);
    assert.equal(state.rows[0].is_mutual, false);
    await connPool.end();
  });

  // 10. Integridade do esquema SQL
  await t.test('10. Integridade do esquema SQL e índices do PostgreSQL', async () => {
    const schemaPool = new Pool({ connectionString: CONNECTION_STRING });
    const colRes = await schemaPool.query(
      `SELECT column_name, data_type, is_nullable
       FROM information_schema.columns 
       WHERE table_name = 'e2ee_messages'
       ORDER BY ordinal_position`
    );
    const cols = colRes.rows.map(c => c.column_name);
    assert.ok(cols.includes('id'));
    assert.ok(cols.includes('sender_identity'));
    assert.ok(cols.includes('recipient_identity'));
    assert.ok(cols.includes('ciphertext_payload'));
    assert.ok(cols.includes('iv_nonce'));
    assert.ok(cols.includes('is_delivered'));
    assert.ok(cols.includes('created_at'));

    // Verifica índice condicional undelivered
    const idxRes = await schemaPool.query(
      `SELECT indexname, indexdef FROM pg_indexes WHERE tablename = 'e2ee_messages'`
    );
    assert.ok(idxRes.rows.some(i => i.indexname === 'e2ee_messages_pkey'));
    assert.ok(idxRes.rows.some(i => i.indexname.includes('undelivered')));

    await schemaPool.end();
  });

  // Encerra servidor PostgreSQL isolado
  await stopPostgresServer(pgProcess);
});
