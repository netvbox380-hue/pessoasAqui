'use strict';

/**
 * Módulo de Migrações e Auditoria de Esquema do PessoasAqui
 * 
 * DIRETRIZES CRÍTICAS:
 * 1. NUNCA executar migrações de forma automática e não-supervisionada no Supabase de produção.
 * 2. Em produção, a aplicação roda em modo SOMENTE-LEITURA de esquema,
 *    validando a compatibilidade sem executar DDL na subida.
 * 3. A função checkSchemaCompatibility() não retorna compatibilidade plena
 *    quando estruturas obrigatórias estiverem ausentes.
 * 4. Migrações administrativas manuais devem ser versionadas e rastreadas via tabela schema_migrations.
 */

const fs = require('fs');
const path = require('path');

const SCHEMA_STATUS = {
  COMPATIBLE: 'COMPATIBLE',
  MIGRATION_PENDING: 'MIGRATION_PENDING',
  BLOCKING_INCOMPATIBLE: 'BLOCKING_INCOMPATIBLE'
};

// Tabelas essenciais requeridas para a operação básica do backend
const ESSENTIAL_TABLES = [
  'identities',
  'connections',
  'e2ee_messages',
  'identity_key_bindings',
  'recovery_delegations',
  'recovery_proof_nonces',
  'recovery_grants'
];

// Colunas essenciais por tabela
const REQUIRED_COLUMNS = {
  e2ee_messages: ['id', 'sender_identity', 'recipient_identity', 'ciphertext_payload', 'iv_nonce', 'is_delivered'],
  connections: ['from_identity', 'to_identity', 'is_mutual'],
  identities: ['id', 'public_key_ed25519', 'pin_salted_hash']
};

/**
 * Verifica a compatibilidade do esquema existente no banco de dados de forma estritamente somente-leitura.
 * Não executa nenhum comando DDL (CREATE, ALTER, DROP, TRUNCATE).
 */
async function checkSchemaCompatibility(queryFn) {
  try {
    const tableRes = await queryFn(`
      SELECT table_name
      FROM information_schema.tables
      WHERE table_schema = 'public'
    `);
    const existingTables = new Set(tableRes.rows.map(r => r.table_name.toLowerCase()));

    // 1. Verifica se há tabelas essenciais ausentes
    const missingTables = ESSENTIAL_TABLES.filter(t => !existingTables.has(t));
    if (missingTables.length > 0) {
      return {
        status: SCHEMA_STATUS.BLOCKING_INCOMPATIBLE,
        compatible: false,
        isFullyCompatible: false,
        isLegacySupported: false,
        isBlocked: true,
        tables: existingTables,
        missingStructures: missingTables.map(t => `tabela: ${t}`),
        pendingMigrations: ['001_initial_schema.sql', '002_add_is_cancelled_to_e2ee_messages.sql']
      };
    }

    // 2. Consulta colunas das tabelas fundamentais
    const colRes = await queryFn(`
      SELECT table_name, column_name, data_type, is_nullable
      FROM information_schema.columns
      WHERE table_schema = 'public' AND table_name IN ('e2ee_messages', 'connections', 'identities')
    `);
    const colsByTable = new Map();
    for (const r of colRes.rows) {
      const t = r.table_name.toLowerCase();
      if (!colsByTable.has(t)) colsByTable.set(t, new Map());
      colsByTable.get(t).set(r.column_name.toLowerCase(), {
        dataType: r.data_type,
        isNullable: r.is_nullable
      });
    }

    // 3. Verifica colunas essenciais
    const missingCols = [];
    for (const [tbl, cols] of Object.entries(REQUIRED_COLUMNS)) {
      const existingCols = colsByTable.get(tbl) || new Map();
      for (const reqCol of cols) {
        if (!existingCols.has(reqCol)) {
          missingCols.push(`${tbl}.${reqCol}`);
        }
      }
    }

    if (missingCols.length > 0) {
      return {
        status: SCHEMA_STATUS.BLOCKING_INCOMPATIBLE,
        compatible: false,
        isFullyCompatible: false,
        isLegacySupported: false,
        isBlocked: true,
        tables: existingTables,
        missingStructures: missingCols.map(c => `coluna essencial: ${c}`),
        pendingMigrations: ['001_initial_schema.sql']
      };
    }

    const e2eeCols = colsByTable.get('e2ee_messages') || new Map();
    const connCols = colsByTable.get('connections') || new Map();

    const hasIsCancelled = e2eeCols.has('is_cancelled');
    const hasCancelledAt = e2eeCols.has('cancelled_at');
    const hasFromIdentity = connCols.has('from_identity');

    // 4. Se estruturas fundamentais existem, mas as colunas da migração aditiva faltam:
    if (!hasIsCancelled || !hasCancelledAt) {
      return {
        status: SCHEMA_STATUS.MIGRATION_PENDING,
        compatible: false, // NÃO É compatibilidade plena!
        isFullyCompatible: false,
        isLegacySupported: true,
        isBlocked: false,
        tables: existingTables,
        hasIsCancelled,
        hasCancelledAt,
        hasFromIdentity,
        pendingMigrations: ['002_add_is_cancelled_to_e2ee_messages.sql'],
        unsupportedOperations: ['CANCELLATION_VERIFICATION', 'UNFILTERED_PENDING_DRAIN']
      };
    }

    // 5. Compatibilidade plena
    return {
      status: SCHEMA_STATUS.COMPATIBLE,
      compatible: true,
      isFullyCompatible: true,
      isLegacySupported: false,
      isBlocked: false,
      tables: existingTables,
      hasIsCancelled: true,
      hasCancelledAt: true,
      hasFromIdentity: true,
      pendingMigrations: []
    };
  } catch (err) {
    return {
      status: SCHEMA_STATUS.BLOCKING_INCOMPATIBLE,
      compatible: false,
      isFullyCompatible: false,
      isLegacySupported: false,
      isBlocked: true,
      error: err.message
    };
  }
}

/**
 * Garante a criação da tabela de rastreamento de migrações schema_migrations
 */
async function ensureSchemaMigrationsTable(queryFn) {
  await queryFn(`
    CREATE TABLE IF NOT EXISTS schema_migrations (
      version VARCHAR(64) PRIMARY KEY,
      applied_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    )
  `);
}

/**
 * Retorna as migrações já registradas no banco
 */
async function getAppliedMigrations(queryFn) {
  try {
    await ensureSchemaMigrationsTable(queryFn);
    const res = await queryFn(`SELECT version FROM schema_migrations ORDER BY version ASC`);
    return new Set(res.rows.map(r => r.version));
  } catch (_) {
    return new Set();
  }
}

/**
 * Extrai comandos SQL da seção UP de um arquivo de migração
 */
function parseUpSql(content) {
  const lines = content.split('\n');
  const upLines = [];
  let inUp = false;
  let inDown = false;

  for (const line of lines) {
    const trimmed = line.trim();
    if (trimmed.startsWith('-- UP:') || trimmed.startsWith('-- =========================================================================') && !inDown) {
      inUp = true;
    }
    if (trimmed.startsWith('-- DOWN:')) {
      inDown = true;
      inUp = false;
      break;
    }
    if (inUp && !trimmed.startsWith('--')) {
      upLines.push(line);
    }
  }

  // Se não houver marcação explícita UP/DOWN, pega todo o conteúdo antes de qualquer bloco DOWN
  if (upLines.length === 0) {
    for (const line of lines) {
      if (line.trim().startsWith('-- DOWN:')) break;
      if (!line.trim().startsWith('--')) upLines.push(line);
    }
  }

  return upLines.join('\n');
}

/**
 * Extrai comandos SQL da seção DOWN de um arquivo de migração
 */
function parseDownSql(content) {
  const lines = content.split('\n');
  const downLines = [];
  let inDown = false;

  for (const line of lines) {
    const trimmed = line.trim();
    if (trimmed.startsWith('-- DOWN:')) {
      inDown = true;
      continue;
    }
    if (inDown && !trimmed.startsWith('--')) {
      downLines.push(line);
    }
  }

  return downLines.join('\n');
}

/**
 * Executa migrações pendentes em ordem estrita dentro de transação, registrando em schema_migrations.
 * NUNCA chamado no fluxo normal de inicialização do backend.
 */
async function runPendingMigrations(queryFn, { withTransactionFn } = {}) {
  const migrationsDir = path.resolve(__dirname, '../migrations');
  if (!fs.existsSync(migrationsDir)) return { applied: [] };

  await ensureSchemaMigrationsTable(queryFn);
  const appliedSet = await getAppliedMigrations(queryFn);

  const files = fs.readdirSync(migrationsDir).filter(f => f.endsWith('.sql')).sort();
  const newlyApplied = [];

  for (const file of files) {
    const version = path.basename(file, '.sql');
    if (appliedSet.has(version)) {
      continue;
    }

    console.log(`[MIGRATION] Aplicando versão: ${version}...`);
    const sqlContent = fs.readFileSync(path.join(migrationsDir, file), 'utf8');
    const upSql = parseUpSql(sqlContent);

    const statements = upSql
      .split(';')
      .map(s => s.trim())
      .filter(s => s.length > 0);

    const executeInTx = async (tx) => {
      for (const stmt of statements) {
        await tx(stmt);
      }
      await tx(`INSERT INTO schema_migrations (version) VALUES ($1)`, [version]);
    };

    if (typeof withTransactionFn === 'function') {
      await withTransactionFn(executeInTx);
    } else {
      await queryFn('BEGIN');
      try {
        await executeInTx(queryFn);
        await queryFn('COMMIT');
      } catch (err) {
        await queryFn('ROLLBACK').catch(() => {});
        throw err;
      }
    }

    newlyApplied.push(version);
    console.log(`[MIGRATION] Versão ${version} aplicada e registrada com sucesso.`);
  }

  return { applied: newlyApplied };
}

/**
 * Executa rollback de uma migração específica registrada em schema_migrations.
 */
async function rollbackMigration(queryFn, targetVersion, { withTransactionFn } = {}) {
  const migrationsDir = path.resolve(__dirname, '../migrations');
  const file = `${targetVersion}.sql`;
  const filePath = path.join(migrationsDir, file);

  if (!fs.existsSync(filePath)) {
    throw new Error(`Arquivo de migração não encontrado para versão: ${targetVersion}`);
  }

  const sqlContent = fs.readFileSync(filePath, 'utf8');
  const downSql = parseDownSql(sqlContent);
  if (!downSql.trim()) {
    throw new Error(`Migração ${targetVersion} não possui instruções de reversão (-- DOWN:)`);
  }

  console.log(`[MIGRATION] Revertendo versão: ${targetVersion}...`);
  const statements = downSql
    .split(';')
    .map(s => s.trim())
    .filter(s => s.length > 0);

  const executeInTx = async (tx) => {
    for (const stmt of statements) {
      await tx(stmt);
    }
    await tx(`DELETE FROM schema_migrations WHERE version = $1`, [targetVersion]);
  };

  if (typeof withTransactionFn === 'function') {
    await withTransactionFn(executeInTx);
  } else {
    await queryFn('BEGIN');
    try {
      await executeInTx(queryFn);
      await queryFn('COMMIT');
    } catch (err) {
      await queryFn('ROLLBACK').catch(() => {});
      throw err;
    }
  }

  console.log(`[MIGRATION] Versão ${targetVersion} revertida com sucesso.`);
  return { rolledBack: targetVersion };
}

module.exports = {
  SCHEMA_STATUS,
  checkSchemaCompatibility,
  ensureSchemaMigrationsTable,
  getAppliedMigrations,
  runPendingMigrations,
  rollbackMigration
};
