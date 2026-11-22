# PESSOASAQUI — ETAPA 30G: orquestração manual de lote GC R2 (PREPARE ONLY)

## Baseline homologada
Antigravity informou Etapa 30F homologada no SHA `380cc793319c61b100cb7641910356ef40322773`: 53/53 testes unitários R2, 208/208 backend, 2/2 PG18 sem skips e 20/20 cenários PostgreSQL reais, inclusive outbox DONE → media DELETED → claim completed na mesma transação, rollback, fencing e backoff. A simulação de DeleteObject + crash PostgreSQL **não constitui teste de R2 real**; respostas SDK/HTTP não foram exercitadas. A main foi conferida idêntica à baseline antes de alterações. Backup `backup/main-pre-r2-delivery-20261114` conferido no SHA `02b6a487ac8b3ad6c64adf6ce8f6887dbfb53bf9`.

## Escopo 30G implementado
- `backend/src/r2StagedGcBatch.js`: `processStagedR2GcBatch` **explicitamente invocado**, não importado por `server.js`, sem timer periódico, scheduler ou credencial. Máximo 8 entradas por chamada (padrão 4), e timeout de armazenamento 100–30.000ms (padrão 15s), inferior ao lease de 90s.
- Usa `discoverQueuedR2DeleteCandidates` 30E e `claimQueuedR2Delete` 30B em transações distintas e curtas; **somente após claim transacional persistida** invoca um callback backend `deleteObject({objectKey,signal})`, injetado. O futuro adapter precisará usar AWS SDK/S3 e respeitar de fato `AbortSignal`, timeout de socket, TLS, destino e credenciais; Android nunca poderá fornecer este callback, nem o estado de sucesso.
- Resposta estrita `{deleted:true,objectKey}` com chave exata antes de `finalizeTrustedR2Delete` 30F. Erro/timeout de armazenamento agenda RETRY via 30F, com jitter injetado 0–30. Se o storage já retornou sucesso e PostgreSQL falha, a falha é propagada **sem classificar como erro de delete**: deve recuperar via reexecução idempotente depois do lease.
- Não mantém locks PostgreSQL durante I/O. Não executa nenhum storage por import. Não há loops automáticos, credenciais, endpoints ou deploy. Os timeouts de 30s são apenas timeout local; **Promise.race + AbortController não garante interrupção de SDK que ignora o sinal**. O futuro adapter deve ter cancelamento efetivo e monitoramento de requests tardias.
- Testes `backend/test/r2StagedGcBatch.test.js`: oito unitários com banco e storage simulados, **ainda não executados pelo ChatGPT**.

## Bloqueadores para produção
1. Adapter R2/S3 real não está integrado, e `deleteObject` pode ser chamado se um backend confiável invocar manualmente a função. Essa função não está conectada a `server.js`, nem está autorizada para produção; evitar passar cliente R2 real antes de finalizar auditoria e rollout explícito.
2. Não há scheduler, shutdown coordenado multiinstância, rate limit global, quotas de recursos e configuração operacional; futura chamada deve restringir concurrency entre nós, implementar métricas sanitizadas e erros classificados. Ainda há risco de operações que ultrapassem o lease por SDK que ignore abort.
3. Fluxos de revogação reais, HTTP autenticado ECDSA P-256, orphan/CANCELLED/legacy, migrações produção e aplicativo Android permanecem pendentes. Nunca inferir completude da Etapa 30.
4. O primeiro lote pode selecionar candidatos que percam a elegibilidade; claim DB revalida e falha fechada.
5. `recordDeleteClaimCompleted` (30B) é API legado preparatória separada: não chamar junto com `finalizeTrustedR2Delete` sem revisão da única autoridade de conclusão.

## Auditoria Antigravity 30G (somente diagnóstico)
- Confirmar SHA `main` e backup; worktree isolada, preservar `app/build.gradle.kts`, logo e todo Android. PostgreSQL 18.1 em 54333, migrações 004–016, nunca Supabase real.
- Executar:
```sh
node --test backend/test/r2StagedGcBatch.test.js
node --test backend/test/r2DeleteOutcomeCoordinator.test.js
node --test backend/test/r2DeleteClaimLedger.test.js
node --test backend/test/r2DeleteCandidateDiscovery.test.js
node --test backend/test/r2DeliveryFinalizer.test.js
node backend/run-tests.js
node --test backend/test/r2ReadAdmission.postgres.test.js
```
- PG18 real e mock S3: lote vazio; claim concorrente; 2–10 trabalhadores em transações; falha storage; deadline e AbortSignal; SDK simulando ignorar abort; sucesso storage seguido de falha de finalização no DB; relançamento após lease, geração antiga recusada, tombstone preservada; RETRY backoff; tentativas 20; idempotência; ordem lock e `SKIP LOCKED`; shutdown simulado; verificação que o módulo não cria requests em import.
- Investigar **segurança do `Promise.race` com callback que ignora sinal**, garantindo ausência de efeitos tardios não auditados. Nenhuma chamada R2 real.
- Diagnosticar pontos de integração no `server.js` e revogação distribuída, sem executar ou modificar. Benchmark de batch e DB sem custos de Supabase real.
- Gerar `PESSOASAQUI_ETAPA30G_AUDITORIA_ORQUESTRACAO_GC_R2.md` com distinção mocks/PostgreSQL e bloqueadores restantes. Não corrigir, commitar, ativar R2, fazer deploy, alterar Android nem usar infra produção.

**STATUS: ETAPA 30G IMPLEMENTADA, NÃO HOMOLOGADA; ETAPA 30 INCOMPLETA; R2/GC SEM ROLLOUT.**
