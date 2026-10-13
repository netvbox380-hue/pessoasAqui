/**
 * Testes Automatizados das Rotas e Documentação Legal do PessoasAqui
 */
const http = require('http');
const fs = require('fs');
const path = require('path');
const assert = require('assert');
const { app, server } = require('./src/server');

const TEST_PORT = 3106;

function request(path) {
  return new Promise((resolve, reject) => {
    const req = http.request(
      {
        hostname: '127.0.0.1',
        port: TEST_PORT,
        path,
        method: 'GET'
      },
      (res) => {
        let raw = '';
        res.on('data', chunk => raw += chunk);
        res.on('end', () => {
          resolve({ status: res.statusCode, headers: res.headers, raw });
        });
      }
    );
    req.on('error', reject);
    req.end();
  });
}

async function runTests() {
  console.log('--- Verificando Documentação Fundacional e Rotas Legais ---');

  // 1. Verifica integridade dos 5 arquivos Markdown em docs/legal/
  const legalFiles = [
    'termos_de_uso.md',
    'politica_de_privacidade.md',
    'guia_autoridades_policiais.md',
    'politica_moderacao_denuncias.md',
    'padroes_seguranca_infantil.md'
  ];

  for (const f of legalFiles) {
    const fullPath = path.join(__dirname, '../docs/legal', f);
    assert.ok(fs.existsSync(fullPath), `Arquivo ${f} deve existir no diretório docs/legal/`);
    const stat = fs.statSync(fullPath);
    assert.ok(stat.size > 1000, `Arquivo ${f} deve conter conteúdo substancial (tamanho: ${stat.size} bytes)`);
    console.log(`✓ Arquivo docs/legal/${f} verificado com sucesso (${stat.size} bytes).`);
  }

  // 2. Inicia servidor temporário para testar rotas HTTP
  await new Promise(resolve => server.listen(TEST_PORT, resolve));

  try {
    console.log('[1/6] Testando acesso ao Portal Jurídico (/legal)...');
    const legalRes = await request('/legal');
    assert.strictEqual(legalRes.status, 200);
    assert.ok(legalRes.raw.includes('PessoasAqui'));
    assert.ok(legalRes.raw.includes('Portal Jurídico'));
    assert.ok(legalRes.raw.includes('doc-seguranca-infantil'));

    console.log('[2/6] Testando acesso direto aos Padrões de Segurança Infantil (/child-safety)...');
    const childRes = await request('/child-safety');
    assert.strictEqual(childRes.status, 200);
    assert.ok(childRes.raw.includes('PessoasAqui — Presença & Chat'), 'Deve citar o nome completo do app');
    assert.ok(childRes.raw.includes('fabio aquino'), 'Deve citar o desenvolvedor cadastrado');
    assert.ok(childRes.raw.includes('CSAE'), 'Deve proibir explicitamente CSAE');
    assert.ok(childRes.raw.includes('seguranca-infantil@pessoasaqui.com.br'), 'Deve conter o ponto de contato');

    console.log('[3/6] Testando redirecionamento de Privacidade (/privacy)...');
    const privRes = await request('/privacy');
    assert.strictEqual(privRes.status, 302);
    assert.ok(privRes.headers.location.includes('/legal#privacidade'));

    console.log('[4/6] Testando redirecionamento de Termos (/terms)...');
    const termsRes = await request('/terms');
    assert.strictEqual(termsRes.status, 302);
    assert.ok(termsRes.headers.location.includes('/legal#termos'));

    console.log('[5/6] Testando redirecionamento de Autoridades (/autoridades)...');
    const authRes = await request('/autoridades');
    assert.strictEqual(authRes.status, 302);
    assert.ok(authRes.headers.location.includes('/legal#autoridades'));

    console.log('[6/6] Testando redirecionamento de Exclusão de Dados (/delete-data)...');
    const delRes = await request('/delete-data');
    assert.strictEqual(delRes.status, 302);
    assert.ok(delRes.headers.location.includes('/legal#exclusao'));

    console.log('\n======================================================');
    console.log('TODOS OS TESTES DA DOCUMENTAÇÃO LEGAL PASSARAM! ✅');
    console.log('======================================================\n');
  } finally {
    server.close();
  }
}

runTests().catch(err => {
  console.error('Falha nos testes legais:', err);
  process.exit(1);
});
