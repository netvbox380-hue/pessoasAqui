const dns = require('dns');
if (typeof dns.setDefaultResultOrder === 'function') {
  dns.setDefaultResultOrder('ipv4first');
}

const { Pool } = require('pg');

let pool = null;
let isPostgres = false;
let circuitBreakerTripped = false;

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
  moderationReports: []
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
    msg.includes('Connection terminated') ||
    msg.includes('password authentication failed') ||
    msg.includes('Tenant or user not found') ||
    msg.includes('timeout')
  );
}

function tripCircuitBreaker(err) {
  if (circuitBreakerTripped) return;
  circuitBreakerTripped = true;
  isPostgres = false;
  const reason = err ? (err.code || err.message) : 'Network unreachable';
  console.warn(`[DB] Aviso: Banco externo inacessível no container (${reason}).`);
  console.warn('[DB] Circuit Breaker ativado: operando 100% em Memória de Alta Velocidade sem interrupções.');
  if (pool) {
    const oldPool = pool;
    pool = null;
    try {
      oldPool.end().catch(() => {});
    } catch (_) {}
  }
}

function initDatabase() {
  const connectionString = process.env.DATABASE_URL;

  if (connectionString) {
    console.log('[DB] Verificando conexão com Banco Relacional PostgreSQL (Supabase / Render)...');
    pool = new Pool({
      connectionString,
      connectionTimeoutMillis: 4000,
      idleTimeoutMillis: 30000,
      ssl: connectionString.includes('supabase') ? { rejectUnauthorized: false } : false
    });
    pool.on('error', (err) => {
      if (isFatalDbError(err)) {
        tripCircuitBreaker(err);
      }
    });

    // Inicia como false até que a sonda confirme conectividade real (evita ENETUNREACH nas requisições iniciais)
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
    console.log('[DB] (Para conectar ao Supabase, defina DATABASE_URL no arquivo .env)');
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
        return { rows: [], rowCount: 0 };
      }
      throw err;
    }
  }
  return { rows: [], rowCount: 0 };
}

module.exports = {
  initDatabase,
  query,
  localStore,
  isPostgres: () => isPostgres && !circuitBreakerTripped
};

