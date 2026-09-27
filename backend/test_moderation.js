/**
 * Testes Automatizados do Sistema de Moderação, Petições e Painel Administrativo
 */
const http = require('http');
const assert = require('assert');
const { app, server } = require('./src/server');

const TEST_PORT = 3105;

function request(method, path, body = null, headers = {}) {
  return new Promise((resolve, reject) => {
    const dataString = body ? JSON.stringify(body) : null;
    const reqHeaders = {
      'Content-Type': 'application/json',
      ...headers
    };
    if (dataString) {
      reqHeaders['Content-Length'] = Buffer.byteLength(dataString);
    }

    const req = http.request(
      {
        hostname: '127.0.0.1',
        port: TEST_PORT,
        path,
        method,
        headers: reqHeaders
      },
      (res) => {
        let rawData = '';
        res.on('data', chunk => rawData += chunk);
        res.on('end', () => {
          try {
            const parsed = rawData ? JSON.parse(rawData) : null;
            resolve({ status: res.statusCode, headers: res.headers, data: parsed, raw: rawData });
          } catch (e) {
            resolve({ status: res.statusCode, headers: res.headers, data: null, raw: rawData });
          }
        });
      }
    );

    req.on('error', reject);
    if (dataString) req.write(dataString);
    req.end();
  });
}

async function runTests() {
  console.log('--- Iniciando Testes de Moderação e Painel Administrativo ---');

  await new Promise((resolve) => {
    server.listen(TEST_PORT, () => {
      console.log(`[TEST] Servidor de teste ouvindo na porta ${TEST_PORT}`);
      resolve();
    });
  });

  try {
    // 1. Teste de Acesso à página inicial do /admin
    console.log('[1/10] Testando rota estática /admin...');
    const adminPage = await request('GET', '/admin');
    assert.strictEqual(adminPage.status, 200, 'Página /admin deve retornar status 200');
    assert.ok(adminPage.raw.includes('PessoasAqui'), 'Página /admin deve conter o título PessoasAqui');

    // 2. Teste de Login Inválido
    console.log('[2/10] Testando login com credenciais incorretas...');
    const badLogin = await request('POST', '/admin/api/login', { username: 'admin', password: 'wrongpassword' });
    assert.strictEqual(badLogin.status, 401, 'Login incorreto deve retornar 401');

    // 3. Teste de Login Válido
    console.log('[3/10] Testando login de administrador...');
    const goodLogin = await request('POST', '/admin/api/login', { username: 'admin', password: 'pessoasaqui2026' });
    assert.strictEqual(goodLogin.status, 200, 'Login correto deve retornar 200');
    assert.ok(goodLogin.data.token, 'Deve retornar token de autenticação');
    const adminToken = goodLogin.data.token;
    console.log('       ✓ Token obtido com sucesso');

    // 4. Teste de Proteção de Rota sem Token
    console.log('[4/10] Testando proteção de rota restrita sem token...');
    const noAuth = await request('GET', '/admin/api/stats');
    assert.strictEqual(noAuth.status, 401, 'Acesso sem token deve retornar 401');

    // 5. Teste de Estatísticas com Token
    console.log('[5/10] Testando métricas em tempo real (/admin/api/stats)...');
    const stats = await request('GET', '/admin/api/stats', null, { Authorization: `Bearer ${adminToken}` });
    assert.strictEqual(stats.status, 200);
    assert.ok('onlineSockets' in stats.data, 'Deve conter contagem de sockets');
    assert.ok('pendingReports' in stats.data, 'Deve conter contagem de denúncias pendentes');

    // 6. Teste de Envio de Denúncia pelo App
    console.log('[6/10] Testando envio de denúncia formal (/api/reports)...');
    const reportPayload = {
      reporterIdentity: 'user_denunciante_123',
      accusedIdentity: 'user_infrator_456',
      violationType: 'SCAM_FRAUD',
      reason: 'Tentou aplicar golpe do falso pix',
      evidenceMessages: [
        { senderAlias: 'Golpista', text: 'Me manda 100 reais no pix agora' }
      ]
    };
    const reportRes = await request('POST', '/api/reports', reportPayload);
    assert.strictEqual(reportRes.status, 200);
    assert.ok(reportRes.data.success);
    const reportId = reportRes.data.reportId;
    console.log(`       ✓ Denúncia criada com ID: ${reportId}`);

    // 7. Teste de Listagem de Denúncias no Painel
    console.log('[7/10] Testando listagem e ação de banimento do infrator...');
    const reportsList = await request('GET', '/admin/api/reports', null, { Authorization: `Bearer ${adminToken}` });
    assert.strictEqual(reportsList.status, 200);
    const foundReport = reportsList.data.reports.find(r => r.id === reportId);
    assert.ok(foundReport, 'Denúncia criada deve constar na lista');
    assert.strictEqual(foundReport.status, 'PENDING');

    // Ação de BAN
    const banAction = await request(
      'POST',
      `/admin/api/reports/${reportId}/action`,
      { action: 'BAN', notes: 'Evidência clara de golpe financeiro.' },
      { Authorization: `Bearer ${adminToken}` }
    );
    assert.strictEqual(banAction.status, 200);
    assert.strictEqual(banAction.data.status, 'BANNED');

    // Verifica estatísticas pós-banimento
    const statsAfterBan = await request('GET', '/admin/api/stats', null, { Authorization: `Bearer ${adminToken}` });
    assert.ok(statsAfterBan.data.bannedIdentitiesCount >= 1, 'Deve registrar o banimento efetuado');
    console.log('       ✓ Infrator banido com sucesso e registrado no sistema');

    // 8. Teste de Petição / Suporte do Cliente
    console.log('[8/10] Testando abertura de chamado de suporte (/api/tickets)...');
    const ticketPayload = {
      authorIdentity: 'user_cliente_789',
      authorAlias: 'Fabio Teste',
      category: 'RECUPERACAO',
      subject: 'Ajuda com troca de aparelho',
      message: 'Não consigo transferir meu PIN familiar, preciso de auxílio.'
    };
    const ticketRes = await request('POST', '/api/tickets', ticketPayload);
    assert.strictEqual(ticketRes.status, 200);
    assert.ok(ticketRes.data.ticketId);
    const ticketId = ticketRes.data.ticketId;
    console.log(`       ✓ Petição registrada: ${ticketId}`);

    // 9. Resposta do Moderador ao Chamado
    console.log('[9/10] Testando resposta da moderação ao chamado...');
    const replyRes = await request(
      'POST',
      `/admin/api/tickets/${ticketId}/reply`,
      { reply: 'Olá Fábio! Verificamos seu caso. Solicite que seu familiar confirme o QR Code de recuperação.' },
      { Authorization: `Bearer ${adminToken}` }
    );
    assert.strictEqual(replyRes.status, 200);

    // Consulta de chamados pelo usuário
    const userTickets = await request('GET', `/api/tickets/my?identity=user_cliente_789`);
    assert.strictEqual(userTickets.status, 200);
    const myTicket = userTickets.data.tickets.find(t => t.id === ticketId);
    assert.ok(myTicket, 'Usuário deve receber seu ticket atualizado');
    assert.strictEqual(myTicket.status, 'ANSWERED');
    assert.ok(myTicket.moderator_reply.includes('Fábio'), 'Resposta do moderador deve estar presente');
    console.log('       ✓ Resposta oficial entregue com sucesso ao cliente');

    // 10. Teste de Reversão de Banimento (Desbanir)
    console.log('[10/10] Testando reversão de banimento (/admin/api/users/:id/unban)...');
    const unbanRes = await request('POST', `/admin/api/users/user_infrator_456/unban`, {}, { Authorization: `Bearer ${adminToken}` });
    assert.strictEqual(unbanRes.status, 200);
    console.log('       ✓ Usuário desbanido com sucesso');

    console.log('\n======================================================');
    console.log('TODOS OS 10 TESTES DE MODERAÇÃO E GESTÃO PASSARAM COM SUCESSO! ✅');
    console.log('======================================================\n');
  } finally {
    server.close();
  }
}

runTests().catch(err => {
  console.error('Falha nos testes:', err);
  process.exit(1);
});
