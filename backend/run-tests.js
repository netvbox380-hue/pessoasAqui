'use strict';

// Executa somente as suítes mantidas em tests/, sem invocar o teste E2E
// legado que usa identidades não autenticadas e já não reflete a API.
const fs = require('node:fs');
const path = require('node:path');
const { spawnSync } = require('node:child_process');

const testsDirectory = path.join(__dirname, 'tests');
const suites = fs.readdirSync(testsDirectory)
  .filter((name) => name.endsWith('.test.js'))
  .sort()
  .map((name) => path.join(testsDirectory, name));

if (suites.length === 0) {
  console.error('Nenhuma suíte moderna foi encontrada em backend/tests.');
  process.exit(1);
}

// Testes devem partir da raiz do workspace. Executar com cwd=backend faria
// dotenv carregar backend/.env e poderia conectar os testes locais ao Supabase.
// DATABASE_URL é removida explicitamente: suítes que precisam de PostgreSQL
// criam e configuram seu próprio banco isolado durante o teste.
const testEnv = { ...process.env };
delete testEnv.DATABASE_URL;
delete testEnv.CONNECTION_REVISIONS_ENABLED; // suites existentes usam esquema legado isolado

const result = spawnSync(process.execPath, ['--test', '--test-concurrency=1', ...suites], {
  cwd: path.resolve(__dirname, '..'),
  env: testEnv,
  stdio: 'inherit'
});

if (result.error) {
  console.error('Falha ao iniciar testes:', result.error.message);
  process.exit(1);
}
process.exit(result.status === null ? 1 : result.status);
