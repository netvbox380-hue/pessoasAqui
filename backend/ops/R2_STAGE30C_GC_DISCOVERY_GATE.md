# PESSOASAQUI — ETAPA 30C: descoberta em lotes para GC R2 (STAGED, INERTE)

## Baseline

O Antigravity informou a homologação preparatória da Etapa 30B no commit `747ad11ddd8ec6ea193e7fa51d6cc1f7b5e07035`: 54/54 testes unitários R2, 208/208 regressão, 2/2 PostgreSQL sem skips e 16 cenários reais em PostgreSQL 18.1 isolado, migrações 004–014. Nenhum worker, R2 DeleteObject ou deploy foi ativado.

A branch de restauração `backup/main-pre-r2-delivery-20261114` permanece no SHA `02b6a487ac8b3ad6c64adf6ce8f6887dbfb53bf9`. A `main` correspondia ao SHA auditado antes de iniciar 30C.

## Implementação da Etapa 30C

- `backend/src/r2DeleteCandidateDiscovery.js`: função `discoverQueuedR2DeleteCandidates({withTransaction,limit})`, máximo 32 registros por lote (padrão 16), com `SELECT` em `message_r2_objects` restrito a `DELETE_QUEUED`, somente se job correspondente existir em `message_r2_delete_outbox`. Seleção usa `FOR UPDATE OF o SKIP LOCKED` e ORDER BY, sem carregar ciphertext. Transação é curta; bloqueios são liberados antes de qualquer futura operação de R2. Resultado validado, sem tokens ou credenciais, congelado e limitado.
- `backend/test/r2DeleteCandidateDiscovery.test.js`: seis testes de contrato para candidatos, fila vazia, limite, invalidação, idempotência de formato e falhas de banco. **Não foram executados pelo ChatGPT.**
- Nenhum cron, timer, polling, DeleteObject, worker, tarefa assíncrona ou rota. Nada é importado por `server.js`.

## Limitações e revisão obrigatória

**O resultado da discovery NÃO é uma claim, nem dá direito a executar DELETE.** Após a seleção, o futuro worker deverá, para cada par, chamar `claimQueuedR2Delete()` da Etapa 30B, que revalida estado terminal, receipt, tombstone PUT expirada e lease. `SKIP LOCKED` evita bloquear scanners concorrentes durante o SELECT, mas as locks da discovery terminam no COMMIT e **não garantem exclusividade** até a claim.

Pontos ainda abertos da Etapa 30:
1. Finalização **atômica pós-I/O** de `message_r2_objects.state='DELETED'`, controle de status na outbox e `r2_delete_claims.completed_at`. Exigir claim token+generation atual, política para DELETE idempotente e crashes entre S3 e DB. O banco precisa ser validado conforme constraints/triggers.
2. Orquestração do worker com retries com backoff, limitação de operações, controle de egress e alarmes. Não executar chamadas reais nem agendar cron nesta etapa.
3. Publicação de `recordCommittedRevocation` dentro das transações reais de cancelamento de mensagens e `/api/connections/unmark`, após autenticação autoritativa. O listener em si está somente staged.
4. Integração HTTP/SDK com autenticação existente **ECDSA P-256**, não trocar por Ed25519 por inferência. Adapter deve impor timeouts, cancellation e bounds. Nenhum endpoint público ativado.
5. Mensagens CANCELLED, chaves órfãs e legadas pré-migração 013 são bloqueadas; não forçar cleanup nem criar tombstones retroativas sem auditoria.
6. O levantamento em lote pode gerar consultas sem fim se sempre retornar as mesmas linhas não elegíveis, pois apenas filtra `DELETE_QUEUED` + outbox; qualquer scheduler futuro terá que evitar hot loop com backoff persistente.
7. Analisar índices e custo no PostgreSQL: `ORDER BY o.last_changed_at,o.object_key` e `EXISTS` sobre outbox podem requerer índice específico e análise de plano `EXPLAIN (ANALYZE, BUFFERS)` em dataset isolado; evitar consultas de alta frequência no Supabase Free.
8. O projeto ainda não garante que downloads R2 em andamento sejam interrompidos por cancelamento real: rotas de mutação e backend precisam ser integradas e testadas.
9. Retenção de ciphertext e expiração 48h só são garantidas pelos caminhos terminalizados; o worker real e a cobertura de todos os estados ainda não estão concluídos.
10. Não avançar para a Play Store alegando media pronta. Android ainda deve validar SHA-256, AEAD/E2EE, persistência e só depois ACK.

## Antigravity — auditoria 30C

Trabalhar em worktree isolada. Confirmar `origin/main` no commit desta etapa e SHA do backup. Preservar `app/build.gradle.kts`, logotipo e todo código Android. **Não corrigir, não commitar, não fazer deploy, não consultar ambientes de produção**.

Executar:
```sh
node --test backend/test/r2DeleteCandidateDiscovery.test.js
node --test backend/test/r2DeleteClaimLedger.test.js
node --test backend/test/r2DeletionAdmission.test.js
node --test backend/test/r2DeliveryFinalizer.test.js
node --test backend/test/r2DeliveryExpiryPutSeal.test.js
node --test backend/test/r2StagedDeliveryRuntime.test.js
node backend/run-tests.js
```
Executar `backend/test/r2ReadAdmission.postgres.test.js` contra PostgreSQL 18.1 isolado 54333, migrações 004–014, zero skipped.

Integração PostgreSQL REAL: 2 e 10 scanners concorrentes; `FOR UPDATE SKIP LOCKED` efetivo, sem deadlock; restrições de objetos `DELETE_QUEUED` com e sem outbox; reaparecimento após COMMIT; lote 1/16/32; tipos de retorno; falhas e ROLLBACK; concorrência scanner vs claim, ACK, expiração e PUT; índices/EXPLAIN sem produção; certificação de ausência de deletes físicos. Provar que discovery não substitui claim de lease.

Inspecionar sem modificar código os esquemas/triggers existentes para sugerir protocolo de finalização pós-I/O com token/generation, e priorizar lacunas de revogação real para o restante da Etapa 30.

Entregar `PESSOASAQUI_ETAPA30C_AUDITORIA_DESCOBERTA_GC_R2.md`, separando testes mock vs PostgreSQL real, indicando quaisquer falhas e riscos residuais.

**Status: 30C PREPARADA E AGUARDANDO HOMOLOGAÇÃO; ETAPA 30 INCOMPLETA; R2 E GC DESATIVADOS.**
