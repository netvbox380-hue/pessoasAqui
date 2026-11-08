#!/usr/bin/env node
'use strict';

/**
 * Ferramenta Administrativa de Verificação e Migração de Esquema do PessoasAqui
 * 
 * DIRETRIZES DE SEGURANÇA:
 * 1. NUNCA executado de forma automática no startup do backend ou no Render.
 * 2. Proteção de credenciais: a senha da connection string NUNCA é exibida em logs.
 * 3. Validação do destino real: bancos remotos ou ambientes de produção exigem
 *    dupla autorização explícita (--confirm-remote-target=<HOST> e --confirm-owner-authorization).
 *    Não depende da presença de substrings como "supabase".
 * 4. Migrações são ordenadas, transacionais e rastreadas na tabela schema_migrations.
 * 
 * Uso:
 *   node backend/src/migrate.js --check
 *   node backend/src/migrate.js --apply --confirm-remote-target=<HOST> --confirm-owner-authorization
 *   node backend/src/migrate.js --rollback <VERSION> --confirm-remote-target=<HOST> --confirm-owner-authorization
 */

const { Pool } = require('pg');
const {
  SCHEMA_STATUS,
  checkSchemaCompatibility,
  getAppliedMigrations,
  runPendingMigrations,
  rollbackMigration
} = require('./migrations');

function sanitizeDbUrl(url) {
  if (!url) return '';
  try {
    const parsed = new URL(url);
    if (parsed.username) {
      parsed.username = '***';
    }
    if (parsed.password) {
      parsed.password = '*****';
    }
    return parsed.toString();
  } catch (_) {
    return url.replace(/(postgres(?:ql)?:\/\/)(?:[^:]+)(?::[^@]+)?(@.+)/i, '$1***:*****$2');
  }
}

function parseTargetInfo(connectionString) {
  try {
    const parsed = new URL(connectionString);
    const hostname = parsed.hostname;
    const port = parsed.port || '5432';
    const dbName = parsed.pathname.replace(/^\//, '');
    const isLocal = ['localhost', '127.0.0.1', '::1'].includes(hostname.toLowerCase());
    const isProductionEnv = (process.env.NODE_ENV === 'production') || Boolean(process.env.RENDER);
    const isRemote = !isLocal;
    return {
      hostname,
      port,
      dbName,
      isLocal,
      isRemote,
      isProduction: isRemote || isProductionEnv,
      sanitizedUrl: sanitizeDbUrl(connectionString)
    };
  } catch (err) {
    return {
      hostname: 'unknown',
      port: '5432',
      dbName: 'unknown',
      isLocal: false,
      isRemote: true,
      isProduction: true,
      sanitizedUrl: sanitizeDbUrl(connectionString)
    };
  }
}

async function main() {
  const args = process.argv.slice(2);
  const isCheck = args.includes('--check') || args.length === 0;
  const isApply = args.includes('--apply');
  const rollbackIdx = args.indexOf('--rollback');
  const isRollback = rollbackIdx !== -1;
  const targetRollbackVersion = isRollback ? args[rollbackIdx + 1] : null;

  const connectionString = process.env.DATABASE_URL;
  if (!connectionString) {
    console.error('ERRO BLOQUEANTE: DATABASE_URL não configurada no ambiente.');
    process.exit(1);
  }

  const targetInfo = parseTargetInfo(connectionString);

  console.log('=========================================================================');
  console.log('PESSOASAQUI — FERRAMENTA ADMINISTRATIVA DE MIGRAÇÃO');
  console.log('=========================================================================');
  console.log(`Destino do Banco:  ${targetInfo.hostname}:${targetInfo.port}/${targetInfo.dbName}`);
  console.log(`Classificação:     ${targetInfo.isProduction ? 'REMOTO / PRODUÇÃO' : 'LOCAL ISOLADO'}`);
  console.log(`URL Sanitizada:    ${targetInfo.sanitizedUrl}`);
  console.log('-------------------------------------------------------------------------');

  const pool = new Pool({ connectionString });

  try {
    // 1. Auditoria e status de compatibilidade
    const queryFn = (sql, params) => pool.query(sql, params);
    const status = await checkSchemaCompatibility(queryFn);
    const appliedSet = await getAppliedMigrations(queryFn);

    console.log(`Status do Esquema:       ${status.status}`);
    console.log(`Compatibilidade Plena:   ${status.isFullyCompatible ? 'SIM' : 'NÃO'}`);
    console.log(`Modo Legado Suportado:   ${status.isLegacySupported ? 'SIM' : 'NÃO'}`);
    console.log(`Bloqueado por Ausência:  ${status.isBlocked ? 'SIM' : 'NÃO'}`);
    console.log(`Migrações Aplicadas:     ${JSON.stringify([...appliedSet])}`);
    console.log(`Migrações Pendentes:     ${JSON.stringify(status.pendingMigrations)}`);

    if (status.missingStructures && status.missingStructures.length > 0) {
      console.log(`Estruturas Ausentes:     ${JSON.stringify(status.missingStructures)}`);
    }
    if (status.unsupportedOperations && status.unsupportedOperations.length > 0) {
      console.log(`Operações em Fail-Closed:${JSON.stringify(status.unsupportedOperations)}`);
    }
    console.log('-------------------------------------------------------------------------');

    // 2. Operação de Aplicação de Migrações (--apply)
    if (isApply) {
      if (targetInfo.isProduction) {
        const confirmFlag = args.find(a => a.startsWith('--confirm-remote-target='));
        const confirmedTarget = confirmFlag ? confirmFlag.split('=')[1] : null;
        const hasOwnerAuth = args.includes('--confirm-owner-authorization');

        const targetMatches = confirmedTarget && (
          confirmedTarget.toLowerCase() === targetInfo.hostname.toLowerCase() ||
          confirmedTarget.toLowerCase() === targetInfo.dbName.toLowerCase()
        );

        if (!targetMatches || !hasOwnerAuth) {
          console.error('\nSEGURANÇA BLOQUEANTE: O banco detectado é um ambiente remoto ou de produção!');
          console.error('Nenhuma migração DDL pode ser executada sem autorização expressa do proprietário.');
          console.error('Para executar conscientemente em produção, informe as duas flags obrigatórias:');
          console.error(`  --confirm-remote-target=${targetInfo.hostname}`);
          console.error('  --confirm-owner-authorization');
          process.exit(2);
        }
      }

      console.log('\n[MIGRATION] Autorização confirmada. Executando migrações pendentes...');
      const result = await runPendingMigrations(queryFn);
      console.log(`[MIGRATION] Sucesso: ${result.applied.length} migrações aplicadas: ${JSON.stringify(result.applied)}`);
    }

    // 3. Operação de Rollback (--rollback <version>)
    if (isRollback) {
      if (!targetRollbackVersion) {
        console.error('ERRO: Informe a versão para rollback. Ex: --rollback 002_add_is_cancelled_to_e2ee_messages');
        process.exit(1);
      }

      if (targetInfo.isProduction) {
        const confirmFlag = args.find(a => a.startsWith('--confirm-remote-target='));
        const confirmedTarget = confirmFlag ? confirmFlag.split('=')[1] : null;
        const hasOwnerAuth = args.includes('--confirm-owner-authorization');

        const targetMatches = confirmedTarget && (
          confirmedTarget.toLowerCase() === targetInfo.hostname.toLowerCase() ||
          confirmedTarget.toLowerCase() === targetInfo.dbName.toLowerCase()
        );

        if (!targetMatches || !hasOwnerAuth) {
          console.error('\nSEGURANÇA BLOQUEANTE: Reversão em ambiente remoto/produção requer autorização expressa do proprietário.');
          console.error(`  --confirm-remote-target=${targetInfo.hostname} --confirm-owner-authorization`);
          process.exit(2);
        }
      }

      console.log(`\n[MIGRATION] Executando reversão da versão: ${targetRollbackVersion}...`);
      await rollbackMigration(queryFn, targetRollbackVersion);
      console.log(`[MIGRATION] Reversão de ${targetRollbackVersion} concluída com sucesso.`);
    }

  } finally {
    await pool.end();
  }
}

if (require.main === module) {
  main().catch(err => {
    console.error('Falha na execução administrativa:', err.message);
    process.exit(1);
  });
}
