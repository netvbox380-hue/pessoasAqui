const { Pool } = require('pg');

let pool = null;
let isPostgres = false;

// Armazenamento em memória local (para testes imediatos sem banco externo)
const localStore = {
  identities: new Map(),
  devices: new Map(),
  presences: new Map(),
  connections: new Map(),
  familyRelationships: new Map(),
  recoveryAuthorizations: new Map(),
  messages: [],
  moderationReports: []
};

function initDatabase() {
  const connectionString = process.env.DATABASE_URL;

  if (connectionString) {
    console.log('[DB] Conectando ao Banco Relacional PostgreSQL (Supabase / Render)...');
    pool = new Pool({
      connectionString,
      ssl: connectionString.includes('supabase') ? { rejectUnauthorized: false } : false
    });
    isPostgres = true;
    console.log('[DB] Conexão com PostgreSQL ativa.');
  } else {
    console.log('[DB] DATABASE_URL não configurada. Operando em modo de Desenvolvimento Local em Memória.');
    console.log('[DB] (Para conectar ao Supabase, defina DATABASE_URL no arquivo .env)');
    isPostgres = false;
  }
}

async function query(sql, params = []) {
  if (isPostgres && pool) {
    return await pool.query(sql, params);
  }
  return { rows: [] };
}

module.exports = {
  initDatabase,
  query,
  localStore,
  isPostgres: () => isPostgres
};
