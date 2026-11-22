# PessoasAqui — Etapa 30K: cancelamento de shutdown GC (STAGED / INERTE)

## Baseline homologada
Antigravity reportou Etapa 30J integralmente aprovada como PREPARATÓRIA no SHA `03b483560f10e039148c0031141a1167e5d2e8b0`: 50/50 testes unitários R2, 208/208 backend, 2/2 PostgreSQL 18 real e 20/20 cenários integrados em ambiente isolado, sem skipped ou deploy. O relatório apontou que `stop()` aguardava a execução ativa sem propagar cancelamento. Antes da implementação, `main` = 30J e backup `backup/main-pre-r2-delivery-20261114` preservado em `02b6a487ac8b3ad6c64adf6ce8f6887dbfb53bf9`.

## Alterações 30K
- `backend/src/r2StagedGcScheduler.js`: após `canRun()`, cria `AbortController` interno e passa `{signal}` ao `runBatch`. Quando `stop()` é chamado, interrompe a operação ativa antes de aguardar o drain, impedindo novos ticks. Continua inerte no import e não faz qualquer trabalho antes de `start()`.
- `backend/src/r2StagedGcBatch.js`: novo `signal` opcional e confiável, sem acesso Android. Se abortado antes de iniciar, zero acesso ao banco. Antes de cada claim para de iterar se houve shutdown. Para claim já adquirida, combina timeout por operação e abort do scheduler para abortar o `AbortController` enviado ao callback do storage. Guard adicional impede chamar o storage após interrupção detectada. Marca erro `R2_DELETE_ABORTED`, agendando RETRY durável apenas quando há lease ainda válido; não marca `DELETED` sem confirmação. Resposta tardia do SDK ignorando abort não pode finalizar o DB pelo fluxo desta Promise.
- Testes: 1 cenário adicional no scheduler e 3 novos casos no lote para pré-abort, abort durante I/O e resultado tardio. **Ainda não executados pelo ChatGPT**.

## Limitações de segurança e produção
1. `Promise.race` + AbortSignal não garante que requisições que ignoram abort parem fisicamente; o resultado do SDK tardio pode excluir remotamente e a operação ser repetida, mas PUT tombstone e idempotência de DeleteObject mitigam. Exigir testes reais de transporte em infraestrutura descartável antes de ativar; jamais confiar em mocks como prova de interrupção de socket.
2. Um `stop()` chamado enquanto `canRun()` está pendente depende do retorno de canRun; não há deadline global do próprio readiness. `stop()` só drena o batch corrente; testes de travamento e inspeção de início/reinício concorrentes são obrigatórios. Um driver DB que trave durante discovery/claim/retry/finalização ainda precisa `statement_timeout`, timeouts de conexão e shutdown global com política de erro.
3. O worker ainda não está importado pelo `server.js`. Não há permissão de rollout, credenciais, AWS SDK no package.json, kill switch robusto, quotas globais multiinstância, SIGTERM/SIGINT ligados ao processo, migrações em produção, revogações transacionais, endpoints P-256, tratamento CANCELLED/orphan/legado ou integração Android.
4. Nenhuma requisição real ao R2, Render, Supabase ou Play; nenhum deploy e nenhuma alteração Kotlin/gradle/logo. Não confundir aprovação desta etapa com conclusão de Etapa 30.

## Auditoria Antigravity — sem correções
Confirmar HEAD da main (commit 30K), backup, worktree isolada, PostgreSQL 18.1 `127.0.0.1:54333`, migrações 004–016. Preservar código principal, Android, `app/build.gradle.kts` e logos.

Executar:
```sh
node --test backend/test/r2StagedGcScheduler.test.js
node --test backend/test/r2StagedGcBatch.test.js
node --test backend/test/r2StagedDeleteAdapter.test.js
node --test backend/test/r2StagedS3ClientFactory.test.js
node --test backend/test/r2DeleteOutcomeCoordinator.test.js
node --test backend/test/r2DeleteClaimLedger.test.js
node backend/run-tests.js
node --test backend/test/r2ReadAdmission.postgres.test.js
```
Testar em PG real e S3 fake: parada antes do primeiro poll, `stop()` durante canRun, discovery e claim, I/O em trânsito, SDK ignorando abort, timeout próximo do abort, DB falhando durante RETRY, DB falhando após resultado de DELETE bem-sucedido, lease 90s, contrato de retry, encerramento multiinstância 2/10 workers, contagem de timers, nenhum deadlock, sem mutações após stop. Não fazer chamadas R2 reais nem acessar produção.

Relatório `PESSOASAQUI_ETAPA30K_AUDITORIA_SHUTDOWN_ABORT_GC_R2.md`. Antigravity somente diagnostica e testa, jamais modifica código ou comita.

**STATUS: ETAPA 30K IMPLEMENTADA, AGUARDANDO HOMOLOGAÇÃO. ETAPA 30 INCOMPLETA.**
