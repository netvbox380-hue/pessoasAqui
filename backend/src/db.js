const dns = require('dns');
if (typeof dns.setDefaultResultOrder === 'function') {
  dns.setDefaultResultOrder('ipv4first');
}

const { Pool } = require('pg');

let pool = null;
let isPostgres = false;
let circuitBreakerTripped = false;

// Controle de reconexão automática e recuperação de falha
let isReconnecting = false;
let reconnectTimer = null;
let reconnectAttempts = 0;
const BASE_RECONNECT_INTERVAL_MS = 800;
const MAX_RECONNECT_INTERVAL_MS = 20000;
const onDatabaseRecoveredCallbacks = new Set();

// Armazenamento em memória local (desenvolvimento local sem DATABASE_URL)
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
    code === '57P01' || // Admin shutdown / postmaster exit
    code === '57P02' || // Crash shutdown
    code === '57P03' || // Cannot connect now
    msg.includes('ENETUNREACH') ||
    msg.includes('EHOSTUNREACH') ||
    msg.includes('ECONNREFUSED') ||
    msg.includes('Connection terminated') ||
    msg.includes('password authentication failed') ||
    msg.includes('Tenant or user not found') ||
    msg.includes('timeout')
  );
}

function createPgPool(connectionString) {
  const isSsl = connectionString.includes('supabase') || Boolean(process.env.PG_SSL_CA_PEM);
  return new Pool({
    connectionString,
    connectionTimeoutMillis: 4000,
    idleTimeoutMillis: 30000,
    ssl: isSsl
      ? {
          rejectUnauthorized: true,
          ...(process.env.PG_SSL_CA_PEM ? { ca: process.env.PG_SSL_CA_PEM.replace(/\\n/g, '\n') } : {})
        }
      : false
  });
}

function scheduleAutoRecovery() {
  const connectionString = process.env.DATABASE_URL;
  if (!connectionString) return;
  if (reconnectTimer || isReconnecting) return;

  const delay = Math.min(
    BASE_RECONNECT_INTERVAL_MS * Math.pow(1.5, Math.min(reconnectAttempts, 8)),
    MAX_RECONNECT_INTERVAL_MS
  ) + Math.floor(Math.random() * 300);

  reconnectTimer = setTimeout(() => {
    reconnectTimer = null;
    attemptDatabaseRecovery();
  }, delay);

  if (typeof reconnectTimer.unref === 'function') {
    reconnectTimer.unref();
  }
}

async function attemptDatabaseRecovery() {
  const connectionString = process.env.DATABASE_URL;
  if (!connectionString) return false;
  if (isReconnecting) return false;
  if (isPostgres && !circuitBreakerTripped) return true;

  isReconnecting = true;
  reconnectAttempts++;

  let candidatePool = null;
  try {
    candidatePool = createPgPool(connectionString);
    candidatePool.on('error', () => {}); // Ignora erros durante a sonda

    await candidatePool.query('SELECT 1');

    // Sonda de conectividade real foi bem-sucedida!
    candidatePool.removeAllListeners('error');
    candidatePool.on('error', (err) => {
      if (isFatalDbError(err)) {
        tripCircuitBreaker(err);
      }
    });

    pool = candidatePool;
    circuitBreakerTripped = false;
    isPostgres = true;
    reconnectAttempts = 0;
    isReconnecting = false;
    console.log('[DB] Conexão com PostgreSQL recuperada automaticamente e confirmada via SELECT 1.');

    for (const cb of onDatabaseRecoveredCallbacks) {
      try {
        const res = cb();
        if (res && typeof res.catch === 'function') res.catch(() => {});
      } catch (_) {}
    }
    return true;
  } catch (err) {
    if (candidatePool) {
      try { candidatePool.end().catch(() => {}); } catch (_) {}
    }
    isPostgres = false;
    circuitBreakerTripped = true;
    isReconnecting = false;
    scheduleAutoRecovery();
    return false;
  }
}

function tripCircuitBreaker(err) {
  if (circuitBreakerTripped) return;
  circuitBreakerTripped = true;
  isPostgres = false;
  const reason = err ? (err.code || err.message) : 'Network unreachable';
  console.warn(`[DB] Aviso: Banco externo inacessível (${reason}). Fail-closed ativado.`);
  if (pool) {
    const oldPool = pool;
    pool = null;
    try {
      oldPool.end().catch(() => {});
    } catch (_) {}
  }
  scheduleAutoRecovery();
}

function resetCircuitBreaker() {
  circuitBreakerTripped = false;
  if (!isPostgres && process.env.DATABASE_URL) {
    attemptDatabaseRecovery();
  }
}

async function closeDatabase() {
  if (reconnectTimer) {
    clearTimeout(reconnectTimer);
    reconnectTimer = null;
  }
  isReconnecting = false;
  if (pool) {
    const oldPool = pool;
    pool = null;
    try { await oldPool.end(); } catch (_) {}
  }
  isPostgres = false;
  circuitBreakerTripped = false;
}

function registerDbRecoveredCallback(cb) {
  if (typeof cb === 'function') {
    onDatabaseRecoveredCallbacks.add(cb);
  }
}

function initDatabase() {
  const connectionString = process.env.DATABASE_URL;

  if (connectionString) {
    if (reconnectTimer) {
      clearTimeout(reconnectTimer);
      reconnectTimer = null;
    }
    circuitBreakerTripped = false;
    isReconnecting = false;
    reconnectAttempts = 0;

    console.log('[DB] Verificando conexão com Banco Relacional PostgreSQL (Supabase / Render)...');
    pool = createPgPool(connectionString);
    pool.on('error', (err) => {
      if (isFatalDbError(err)) {
        tripCircuitBreaker(err);
      }
    });

    isPostgres = false;

    pool.query('SELECT 1')
      .then(() => {
        if (!circuitBreakerTripped) {
          isPostgres = true;
          console.log('[DB] Conexão com PostgreSQL verificada e ativa.');
        }
      })
      .catch((err) => {
        tripCircuitBreaker(err);
      });
  } else {
    console.log('[DB] DATABASE_URL não configurada. Operando em modo de Desenvolvimento Local em Memória.');
    isPostgres = false;
  }
}

async function query(sql, params = []) {
  if (isPostgres && pool && !circuitBreakerTripped) {
    try {
      return await pool.query(sql, params);
    } catch (err) {
      if (isFatalDbError(err)) {
        tripCircuitBreaker(err);
        throw err;
      }
      throw err;
    }
  }
  if (process.env.DATABASE_URL) throw new Error('PostgreSQL indisponível; operação não confirmada');
  return { rows: [], rowCount: 0 };
}

async function withTransaction(work) {
  if (!isPostgres || !pool || circuitBreakerTripped) {
    throw new Error('PostgreSQL transacional indisponível');
  }
  const client = await pool.connect();
  try {
    await client.query('BEGIN');
    const result = await work((sql, params = []) => client.query(sql, params));
    await client.query('COMMIT');
    return result;
  } catch (error) {
    if (isFatalDbError(error)) {
      tripCircuitBreaker(error);
    }
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
  resetCircuitBreaker,
  closeDatabase,
  registerDbRecoveredCallback,
  attemptDatabaseRecovery,
  isPostgres: () => isPostgres && !circuitBreakerTripped
};
