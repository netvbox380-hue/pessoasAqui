#!/usr/bin/env node
'use strict';

/**
 * Ferramenta CLI de Verificação e Migração de Esquema do PessoasAqui
 * 
 * Uso:
 *   node backend/src/migrate.js --check
 *   node backend/src/migrate.js --apply --confirm-production (somente sob autorização expressa)
 */

const { Pool } = require('pg');
const { checkSchemaCompatibility, runPendingMigrations } = require('./migrations');

async function main() {
  const args = process.argv.slice(2);
  const isApply = args.includes('--apply');
  const isConfirm = args.includes('--confirm-production');
  const connectionString = process.env.DATABASE_URL;

  if (!connectionString) {
    console.error('ERRO: DATABASE_URL não configurada.');
    process.exit(1);
  }

  const pool = new Pool({ connectionString });
  try {
    const status = await checkSchemaCompatibility((sql, params) => pool.query(sql, params));
    console.log('--- Status de Compatibilidade do Esquema ---');
    console.log(`Compatível: ${status.compatible}`);
    console.log(`Possui is_cancelled: ${status.hasIsCancelled}`);
    console.log(`Possui cancelled_at: ${status.hasCancelledAt}`);
    console.log(`Migrações pendentes: ${JSON.stringify(status.pendingMigrations)}`);

    if (isApply) {
      if (connectionString.includes('supabase') && !isConfirm) {
        console.error('\nSEGURANÇA BLOQUEANTE: O banco detectado é o Supabase de produção!');
        console.error('Migrações no Supabase exigem aprovação expressa do proprietário e a flag --confirm-production.');
        process.exit(2);
      }
      await runPendingMigrations((sql, params) => pool.query(sql, params));
      console.log('Migrações aplicadas com sucesso.');
    }
  } finally {
    await pool.end();
  }
}

if (require.main === module) {
  main().catch(err => {
    console.error('Falha na execução:', err);
    process.exit(1);
  });
}
