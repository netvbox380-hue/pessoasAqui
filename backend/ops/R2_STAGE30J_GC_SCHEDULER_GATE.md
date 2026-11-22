# PESSOASAQUI — ETAPA 30J: SCHEDULER GC ESTAGIADO, SERIAL E COM DRAIN

## Baseline
Antigravity homologou Etapa 30I como preparatória no SHA `c5260e49c5ec30fc2bb9571927312f9a7924b202`: 42/42 testes unitários R2, 208/208 regressão, 2/2 PostgreSQL real e 20/20 cenários de integração. Testes SDK foram feitos com mocks; **não houve acesso Cloudflare R2** nem dependências instaladas. Checkpoint `backup/main-pre-r2-delivery-20261114` está preservado no SHA `02b6a487ac8b3ad6c64adf6ce8f6887dbfb53bf9`. `main` estava idêntica ao SHA auditado antes do desenvolvimento 30J.

## Implementação 30J
- `backend/src/r2StagedGcScheduler.js`: cria um scheduler **inicialmente inerte**, sem `server.js`, sem `require` do SDK, timers ou DB no import. Recebe `runBatch` e `canRun` injetados por backend confiável.
- `start()` apenas agenda primeiro tick após intervalo configurado, default 30s. A cada tick exige `canRun() === true` (future: DB pronto, rollout explicitamente autorizado, kill switch e configuração válida), depois executa **um lote por vez**. Nunca sobrepõe lotes; agenda tick seguinte só após resolver trabalho atual.
- `stop()` cancela timer, impede novos ticks, aguarda o lote atual terminar (graceful drain), retorna status. Operação que não respeite timeout pode impedir término de stop; futura integração precisa deadline global e sinal de shutdown efetivo no batch.
- Intervalo entre ticks de 1–300 segundos; erros em `runBatch` ou `canRun` provocam backoff exponencial até 300s, erros reportados apenas como código sanitizado `R2_GC_BATCH_FAILED`, sem conteúdo secreto. `onResult` pode registrar métricas sanitizadas.
- Os timers usam `unref` quando possível para não manter processo artificialmente vivo.
- Novo teste `backend/test/r2StagedGcScheduler.test.js` com 8 cenários em timers e batches simulados. **Os testes 30J não foram executados pelo ChatGPT.**

## Bloqueadores e cautelas de produção
1. **Ainda não há worker de produção.** Scheduler não está conectado ao `server.js`, não cria cliente R2, não lê Render env, não acessa PostgreSQL, não dispara storage sozinho. Somente callback injetado após `start()` explícito poderá executar operações. Não ativar em produção nem instalar AWS SDK/Smithy ainda.
2. `stop()` aguarda lote em andamento, mas não passa AbortSignal de encerramento ao `processStagedR2GcBatch`. Existe timeout por operação no 30G, porém interrupção global e recuperação em encerramento precisam integração e auditoria; não afirmar graceful shutdown absoluto.
3. Readiness/kill-switch são responsabilidades do futuro `canRun`, não implementadas neste módulo. Um chamador incorreto que forneça `canRun:()=>true` pode iniciar lotes, embora o módulo esteja não importado por `server.js`.
4. Proteções globais por nó/cluster, quotas e telemetria de erros sanitizados, singleton, IAM, migrações produção, rate limiting e adequação do serviço Render ainda pendentes.
5. `recordCommittedRevocation` precisa integração real nas transações de cancelamento e `/api/connections/unmark`. Endpoints autenticados por assinatura ECDSA P-256, leitura streaming segura, cobertura CANCELLED/orphan/legado e Android também pendentes.
6. O desenvolvimento preparatório não é autorização para deploy, chamadas R2 reais, ALTER TABLE Supabase produção nem publicação Play Store.

## Antigravity — auditoria obrigatória 30J, só diagnóstico
Validar HEAD da `main`, backup, worktree isolada, preservação `app/build.gradle.kts`, logo e Android. PostgreSQL 18.1 isolado porta 54333, migrações 004–016, nunca produção.

Executar:
```sh
node --test backend/test/r2StagedGcScheduler.test.js
node --test backend/test/r2StagedS3ClientFactory.test.js
node --test backend/test/r2StagedDeleteAdapter.test.js
node --test backend/test/r2StagedGcBatch.test.js
node --test backend/test/r2DeleteOutcomeCoordinator.test.js
node backend/run-tests.js
node --test backend/test/r2ReadAdmission.postgres.test.js
```

Testar cronologia de fake timers: sem timers no import e antes de `start()`, primeira execução atrasada, rejeitar readiness false, pausa após falha, intervalo exponencial bounded, início idempotente, nenhum overlap, `stop()` enquanto lote em trânsito e após encerrar, restart, callback `onResult` ou `onError` que lança; falhas de PG e R2 simuladas e garantia de ausência de log com segredos. Com PG18 real e S3 fake, executar `scheduler→processStagedR2GcBatch→adapter→finalize` com 2–10 instâncias, leases, concorrência, shutdown sem novos lotes, crash/restart, zero deadlocks e respostas tardias. Sem chamadas Cloudflare real, Render ou Supabase produção. Relatar riscos com lote que ignora cancelamento e stop pendente.

Gerar `PESSOASAQUI_ETAPA30J_AUDITORIA_SCHEDULER_GC_R2.md`. Antigravity NÃO modifica código, commits, server.js, Android, serviços ou dependências.

**ETAPA 30J IMPLEMENTADA, NÃO HOMOLOGADA. ETAPA 30 INCOMPLETA. R2 / GC SEM ROLLOUT.**
