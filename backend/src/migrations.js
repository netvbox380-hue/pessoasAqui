'use strict';

/**
 * Módulo de Migrações do PessoasAqui
 * 
 * REGRA CRÍTICA:
 * Nunca executar migrações de forma automática e não-supervisionada no Supabase de produção.
 * Em produção (Render -> Supabase), a aplicação roda em modo SOMENTE-LEITURA de esquema,
 * validando a compatibilidade sem executar DDL na subida.
 */

const fs = require('fs');
const path = require('path');

async function checkSchemaCompatibility(queryFn) {
  try {
    const tableRes = await queryFn(`
      SELECT table_name
      FROM information_schema.tables
      WHERE table_schema = 'public'
    `);
    const existingTables = new Set(tableRes.rows.map(r => r.table_name.toLowerCase()));

    const colRes = await queryFn(`
      SELECT table_name, column_name
      FROM information_schema.columns
      WHERE table_schema = 'public' AND table_name IN ('e2ee_messages', 'connections')
    `);
    const colsByTable = new Map();
    for (const r of colRes.rows) {
      const t = r.table_name.toLowerCase();
      if (!colsByTable.has(t)) colsByTable.set(t, new Set());
      colsByTable.get(t).add(r.column_name.toLowerCase());
    }

    const e2eeCols = colsByTable.get('e2ee_messages') || new Set();
    const connCols = colsByTable.get('connections') || new Set();

    const hasIsCancelled = e2eeCols.has('is_cancelled');
    const hasCancelledAt = e2eeCols.has('cancelled_at');
    const hasFromIdentity = connCols.has('from_identity');

    return {
      compatible: true,
      tables: existingTables,
      hasIsCancelled,
      hasCancelledAt,
      hasFromIdentity,
      pendingMigrations: !hasIsCancelled ? ['002_add_is_cancelled_to_e2ee_messages.sql'] : []
    };
  } catch (err) {
    return { compatible: false, error: err.message };
  }
}

async function runPendingMigrations(queryFn) {
  console.log('[MIGRATION] Executando migrações sob comando explícito...');
  const migrationsDir = path.resolve(__dirname, '../migrations');
  if (!fs.existsSync(migrationsDir)) return;

  const files = fs.readdirSync(migrationsDir).filter(f => f.endsWith('.sql')).sort();
  for (const file of files) {
    console.log(`[MIGRATION] Aplicando ${file}...`);
    const sql = fs.readFileSync(path.join(migrationsDir, file), 'utf8');
    // Executa apenas o bloco UP (ignora comentários DOWN)
    const statements = sql
      .split(';')
      .map(s => s.trim())
      .filter(s => s.length > 0 && !s.startsWith('--'));

    for (const stmt of statements) {
      if (stmt.toUpperCase().startsWith('ALTER') || 
          stmt.toUpperCase().startsWith('CREATE') ||
          stmt.toUpperCase().startsWith('INSERT')) {
        await queryFn(stmt);
      }
    }
  }
  console.log('[MIGRATION] Migrações concluídas com sucesso.');
}

module.exports = {
  checkSchemaCompatibility,
  runPendingMigrations
};
