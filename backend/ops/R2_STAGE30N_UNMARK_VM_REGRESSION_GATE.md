# PESSOASAQUI — ETAPA 30N: CORREÇÃO DA REGRESSÃO VM EM UNMARK (PREPARE ONLY)

## Auditoria recebida e diagnóstico
Antigravity relatou Etapa 30M com 35/35 testes unitários do gate e revogação, 2/2 PostgreSQL, 20/20 cenários Express real com ECDSA P-256, mas somente **204/208** testes na suíte geral `backend/run-tests.js`. As 4 falhas pertencem a `backend/tests/connectionRevocation.test.js`: `ReferenceError: require is not defined`, pois o commit 30M colocou `require('./r2UnmarkRevocationGate')` dentro do corpo da rota, cuja seção é extraída e executada no `vm.runInNewContext` sem o `require` global. Não foi defeito de segurança observado na rota HTTP real, mas a regressão de teste é BLOQUEANTE e não deve ser ignorada.

## Baseline e preservação
Antes da correção, `main` correspondia exatamente a `48764548cc57931e364568f131f87e4daac197dd`. Branch de restauração `backup/main-pre-r2-delivery-20261114` no SHA `02b6a487ac8b3ad6c64adf6ce8f6887dbfb53bf9`.

## Implementação 30N
1. `backend/src/server.js`: mover o `require('./r2UnmarkRevocationGate')` para as importações comuns, acima de todas as rotas; remover `require` no corpo da rota `/api/connections/unmark`. Gate consultado localmente por:
```js
const r2RevocationEnabled = (typeof r2RevocationBridgeEnabled === 'function')
  ? r2RevocationBridgeEnabled()
  : false;
```
No runtime real do servidor, o gate é carregado obrigatoriamente pela importação comum. Somente a sandbox legada VM, que omite imports, faz fallback R2-desabilitado. A mudança não altera a autorização, SQL de marcação/remarcação, ordem DELETE/UPDATE/NOTIFY, flags de rollout, validação de esquema ou tratamento de COMMIT/ROLLBACK.
2. `backend/test/r2UnmarkVmRegression.test.js`: testes para import na posição correta, ausência de require no handler extraído, execução do UNMARK real em contexto VM sem require e fallback de gate. **Ainda não foram executados pelo ChatGPT.**
3. Migração 012 continua AUSENTE no Supabase produção; `PESSOASAQUI_R2_REVOCATION_TX_ENABLED` e `PESSOASAQUI_R2_STAGE_ROLLOUT_APPROVED` continuam AUSENTES/desativadas. Nenhum deploy, alteração de app Android, APK ou credenciais R2.

## Auditoria Antigravity 30N — reparação obrigatória, não presumir 100%
Verificar SHA `main`, checkpoint, worktree isolada; nunca tocar Render/Supabase/Cloudflare de produção, Android, gradle nem logotipos. PostgreSQL 18.1 `127.0.0.1:54333`, migrações 004–016.

Executar obrigatoriamente:
```sh
node --test backend/test/r2UnmarkVmRegression.test.js
node --test backend/test/r2UnmarkRevocationGate.test.js
node --test backend/test/r2StagedRevocationBridge.test.js
node --test backend/tests/connectionRevocation.test.js
node --test backend/test/r2RevocationOutbox.test.js
node --test backend/test/r2RevocationListener.test.js
node backend/run-tests.js
node --test backend/test/r2ReadAdmission.postgres.test.js
```
O critério de liberação é **208/208 na suíte geral, com ZERO skips**, além da bateria nova. Se qualquer falha continuar, reportar pilha e origem sem correção, sem declarar homologação.

Testar com HTTP Express + PostgreSQL isolado: flags 0/0 sem migração 012 e sem SQL R2; flags 1/0 e 0/1 inertes; 1/1 sem migração 012 bloqueia ANTES de DELETE/UPDATE; 1/1 com migração 012 valida mensagem pendente vs entregue, COMMIT/ROLLBACK, revisão, autenticação P-256, listener revogando streams e múltiplos trabalhadores, idempotência, SQL do route. Verificar que import de gate no topo é puramente inerte (sem timers, DB nem AWS SDK); nada ativado por acidente.
Analisar potencial risco da sandbox VM sem gate quando flags estão true no contexto simulado: o fallback é reservado ao teste legado e não substitui a validação com servidor real. Em servidor real o import deve sempre funcionar.
Relatório `PESSOASAQUI_ETAPA30N_AUDITORIA_REGRESSAO_VM_UNMARK_R2.md`. Antigravity só testa/diagnostica; não corrige, commita, publica ou habilita flags.

**STATUS 30N IMPLEMENTADA, NÃO HOMOLOGADA; 30M COM 4 FALHAS DE REGRESSÃO PENDENTES DE REEXECUÇÃO. ETAPA 30 INCOMPLETA.**
