# PESSOASAQUI — ETAPA 21B: correção SQL 42725 (AGUARDANDO REHOMOLOGAÇÃO)

**Motivo:** auditoria do Antigravity em 16/11/2026 ao commit `d03b952` identificou erro real de PostgreSQL 18 (`42725 operator is not unique: unknown - unknown`) na cláusula `reserved_bytes <= $4 - $2` de `backend/src/r2ReadAdmission.js`. 8/8 testes unitários com mocks não detectaram o problema. A etapa 21 **não** estava aprovada.

## Correção na main

- Alterado somente o predicado de quota para `reserved_bytes <= ($4::bigint - $2::bigint)`. Mantém atomicidade do UPDATE, limites de 24 pedidos / 300 MiB e não altera o controle E2EE/autenticação.
- Atualizado `backend/test/r2ReadAdmission.test.js` para exigir o SQL tipado.
- Adicionado `backend/test/r2ReadAdmission.postgres.test.js` para executar a **consulta SQL real extraída do módulo** no PostgreSQL isolado. Ele recusa conexão a host que não seja loopback porta 54333, não lê DATABASE_URL e usa ROLLBACK em todos os cenários.
- **Crítico:** caso `PESSOASAQUI_TEST_PG_URL` não esteja configurada, o teste real é explicitamente **SKIPPED**. Um PASS dos testes unitários ou do teste de extração NÃO equivale a validação do PostgreSQL. O Antigravity deve fornecer a URL apenas da instância isolada com migração 011 aplicada e confirmar que o teste de banco foi realmente executado, sem SKIP.

## Comandos para Antigravity — somente diagnóstico

1. Confirmar HEAD atual, `main` e checkpoint `backup/main-pre-r2-delivery-20261114`.
2. `node --test backend/test/r2ReadAdmission.test.js`
3. Em PostgreSQL 18 isolado, migrações 004–011 e sem tocar produção:
   `PESSOASAQUI_TEST_PG_URL='postgresql://USER:PASS@127.0.0.1:54333/NOME_BANCO_TESTE' node --test backend/test/r2ReadAdmission.postgres.test.js`
   - Adaptar comando ao shell Windows sem expor senha no relatório.
   - Mostrar `PASS` real do caso com SQL exato e **0 SKIP**.
4. `node --test backend/test/r2RecipientReadAuth.test.js`
5. `node --test backend/test/r2ActionAuth.test.js`
6. `node backend/run-tests.js`
7. Reexecutar cenários 1, 6, 8, 9, 10, 11 e 13 da auditoria Etapa 21 **com o código commitado**, não com patch local temporário.
8. Testar 24a/25a tentativa, 300 MiB exatos/acima do limite, replay, rollback, concorrência entre instâncias, mudança UTC e falha do PostgreSQL.
9. Auditar se o parser de SQL real aceitou o comando parametrizado e registrar código de erro se houver, sem mascarar falhas com mocks.

**Proibido ao Antigravity editar, commitar, fazer deploy, acessar Supabase/Render/R2 de produção ou ativar APIs.**

**Situação:** bug corrigido em código, aguardando testes reais. R2 permanece desativado.
