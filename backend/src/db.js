'use strict';

const dns = require('dns');
if (typeof dns.setDefaultResultOrder === 'function') {
  dns.setDefaultResultOrder('ipv4first');
}

const { Pool } = require('pg');

let pool = null;
let isPostgres = false;
let circuitBreakerTripped = false;
let lastProbeTime = 0;
const PROBE_COOLDOWN_MS = 10000;
let isProbing = false;

// Armazenamento em memória local (alta velocidade + fallback resiliente em nuvem)
const localStore = {
  identities: new Map(),
  devices: new Map(),
  presences: new Map(),
  connections: new Map(),
  familyRelationships: new Map(),
  recoveryAuthorizations: new Map(),
  invites: new Map(),
  messages: [],
  moderationReports: [],
  supportTickets: []
};

function isFatalDbError(err) {
  if (!err) return false;
  const code = String(err.code || '');
  const msg = String(err.message || '');
  return (
    code === 'ENETUNREACH' ||
    code === 'EHOSTUNREACH' ||
    code === 'ECONNREFUSED' ||
    code === 'ETIMEDOUT' ||
    code === 'ENOTFOUND' ||
    code === 'EAI_AGAIN' ||
    code === '28P01' || // Auth failed
    code === '3D000' || // Database does not exist
    code === 'XX000' || // Tenant/pooler error
    msg.includes('ENETUNREACH') ||
    msg.includes('EHOSTUNREACH') ||
    msg.includes('ECONNREFUSED') ||
    msg.includes('password authentication failed') ||
    msg.includes('Tenant or user not found')
  );
}

function attachPoolErrorListener(p) {
  p.on('error', (err) => {
    // Erros em conexões ociosas (idle clients) são esperados em pools com PgBouncer/Supabase.
    // O driver pg remove automaticamente o cliente inativo do pool.
    // NÃO destruir o pool nem desativar permanentemente o banco de dados.
    console.warn('[DB] Conexão ociosa reciclada pelo servidor de banco:', err.message);
  });
}

function createPool(connectionString) {
  if (!connectionString) return null;
  const isLocal = connectionString.includes('localhost') || connectionString.includes('127.0.0.1');
  const p = new Pool({
    connectionString,
    connectionTimeoutMillis: 10000,
    idleTimeoutMillis: 20000,
    max: 10,
    keepAlive: true,
    keepAliveInitialDelayMillis: 10000,
    ssl: isLocal ? false : { rejectUnauthorized: false }
  });
  attachPoolErrorListener(p);
  return p;
}

async function checkDatabaseHealth() {
  const connectionString = process.env.DATABASE_URL;
  if (!connectionString) return false;
  if (isPostgres && pool) return true;

  const now = Date.now();
  if (now - lastProbeTime < PROBE_COOLDOWN_MS || isProbing) {
    return isPostgres && pool !== null;
  }
  lastProbeTime = now;
  isProbing = true;

  try {
    if (!pool) {
      pool = createPool(connectionString);
    }
    if (pool) {
      await pool.query('SELECT 1');
      isPostgres = true;
      circuitBreakerTripped = false;
      console.log('[DB] Conexão com PostgreSQL ativa e verificada.');
      return true;
    }
  } catch (err) {
    isPostgres = false;
    circuitBreakerTripped = true;
    console.warn(`[DB] Sonda de conexão ao PostgreSQL falhou (${err.code || err.message}). Operando em modo resiliente.`);
  } finally {
    isProbing = false;
  }
  return false;
}

function initDatabase() {
  const connectionString = process.env.DATABASE_URL;

  if (connectionString) {
    console.log('[DB] Verificando conexão com Banco Relacional PostgreSQL (Supabase / Render)...');
    pool = createPool(connectionString);
    isPostgres = false;

    pool.query('SELECT 1')
      .then(() => {
        isPostgres = true;
        circuitBreakerTripped = false;
        console.log('[DB] Conexão com PostgreSQL verificada e ativa.');
      })
      .catch((err) => {
        isPostgres = false;
        circuitBreakerTripped = true;
        console.warn(`[DB] Aviso: Conexão inicial com PostgreSQL falhou (${err.code || err.message}). Sonda periódica tentará restabelecer.`);
      });

    // Inicia sonda periódica em segundo plano para autorrecuperação automática
    const probeTimer = setInterval(() => {
      if (!isPostgres || !pool) {
        checkDatabaseHealth().catch(() => {});
      }
    }, 20000);
    if (typeof probeTimer.unref === 'function') {
      probeTimer.unref();
    }
  } else {
    console.log('[DB] DATABASE_URL não configurada. Operando em modo de Desenvolvimento Local em Memória.');
    console.log('[DB] (Para conectar ao Supabase, defina DATABASE_URL no arquivo .env)');
    isPostgres = false;
  }
}

async function query(sql, params = []) {
  if (!process.env.DATABASE_URL) {
    return { rows: [], rowCount: 0 };
  }

  if (!isPostgres || !pool) {
    await checkDatabaseHealth();
  }

  if (isPostgres && pool) {
    try {
      return await pool.query(sql, params);
    } catch (err) {
      if (isFatalDbError(err)) {
        isPostgres = false;
        circuitBreakerTripped = true;
        console.warn(`[DB] Erro de rede na consulta (${err.code || err.message}). Ativando modo resiliente.`);
        return { rows: [], rowCount: 0 };
      }
      throw err;
    }
  }
  return { rows: [], rowCount: 0 };
}

async function withTransaction(work) {
  if (!process.env.DATABASE_URL) {
    throw new Error('PostgreSQL transacional indisponível');
  }
  if (!isPostgres || !pool) {
    await checkDatabaseHealth();
  }
  if (!isPostgres || !pool) {
    throw new Error('PostgreSQL transacional indisponível');
  }
  const client = await pool.connect();
  try {
    await client.query('BEGIN');
    const result = await work((sql, params = []) => client.query(sql, params));
    await client.query('COMMIT');
    return result;
  } catch (error) {
    try { await client.query('ROLLBACK'); } catch (_) {}
    throw error;
  } finally {
    client.release();
  }
}

module.exports = {
  initDatabase,
  query,
  withTransaction,
  localStore,
  checkDatabaseHealth,
  isPostgres: () => isPostgres && !circuitBreakerTripped
};
