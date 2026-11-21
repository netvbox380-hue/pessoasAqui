# PESSOASAQUI — ETAPA 30D: FILTRO DE ELEGIBILIDADE E ÍNDICE FIFO PARA GC (STAGED / INERTE)

## Baseline
Antigravity homologou a Etapa 30C como preparatória no SHA `24dfecf852f3b2a94d0058cfb966a46bbb71adf1`: 43/43 unitários R2, 208/208 backend, 2/2 PG18 real, 14/14 cenários de concorrência e SKIP LOCKED. Os dois alertas de engenharia foram um `Seq Scan` com sort por falta de índice parcial e o risco de hot loop no scanner que voltava a selecionar arquivos ainda inelegíveis.

Backup verificado: `backup/main-pre-r2-delivery-20261114` em `02b6a487ac8b3ad6c64adf6ce8f6887dbfb53bf9`. O HEAD da main correspondia ao SHA 30C antes dos commits desta etapa.

## Alterações preparatórias
1. Migração `015_r2_gc_discovery_fifo_index_PREPARE_ONLY.sql` (**não aplicada em produção**): índice parcial em `message_r2_objects(last_changed_at ASC,object_key ASC) WHERE state='DELETE_QUEUED'`. Não afirmar que elimina todo Seq Scan em tabelas pequenas; validar planos e BUFFERS em volume representativo.
2. `r2DeleteCandidateDiscovery.js`: sem nova rota, worker ou cron. A seleção agora filtra:
   - Outbox que corresponda ao `object_key/message_id`;
   - Recibo terminal `DELIVERED` ou `EXPIRED`;
   - Janela PUT persistente selada e com prazo de validade + 120 segundos já vencido;
   - Ausência de claim concluída, ativa (lease não vencido) ou exaurida (>=20 tentativas).
   Usa lotes de até 32 entradas, `FOR UPDATE OF o SKIP LOCKED`. A claim Etapa 30B ainda revalida **tudo** antes de qualquer futura operação.
3. `r2DeleteCandidateDiscovery.test.js`: teste adicional do SQL contra critérios de terminalidade, PUT e claim.

## Limites de segurança
- Seleção não constitui lease: locks duram só a transação curta; `claimQueuedR2Delete` ainda será obrigatório.
- **Não existe backoff exponencial persistente pós-falha**. Claims expirados podem voltar à discovery após 90 segundos; o gate evita tight loop durante lease e bloqueia permanentemente entradas inelegíveis, mas o worker futuro deverá agendar `next_attempt_at`, registrar erros e oferecer monitoramento. A ausência de worker torna esta lacuna inerte por enquanto.
- Chaves pré-013 sem ledger ficam em quarentena. CANCELLED e órfãs continuam fora da seleção, exigindo outro protocolo.
- Não há S3 DeleteObject, worker, API, server.js, acesso ao Supabase, Render, R2, Android ou alterações Play.
- Não foi concluído o protocolo atômico pós-DELETE de transição de mídia `DELETED` e fechamento da outbox, nem a publicação `recordCommittedRevocation` em transações autoritativas, nem o gateway HTTP/SDK real com ECDSA P-256.
- **A Etapa 30 como um todo ainda não está concluída.**

## Auditoria obrigatória Antigravity
Verificar `main` no SHA desta etapa, checkpoint, worktree isolada, migrações 004–015 só em PG18 local `127.0.0.1:54333`, preservação `app/build.gradle.kts`.

Executar:
```sh
node --test backend/test/r2DeleteCandidateDiscovery.test.js
node --test backend/test/r2DeleteClaimLedger.test.js
node --test backend/test/r2DeletionAdmission.test.js
node --test backend/test/r2PutRetirementFence.test.js
node --test backend/test/r2StagedDeliveryRuntime.test.js
node backend/run-tests.js
```
Executar `PESSOASAQUI_TEST_PG_URL=... node --test backend/test/r2ReadAdmission.postgres.test.js` com zero skipped.

Cenários PostgreSQL reais:
- Com e sem índice parcial: EXPLAIN ANALYZE BUFFERS com dados isolados em volumes representativos, confirmar planos indexados quando apropriado.
- Validar que discovery **exclui** entradas com receipt CANCELLED/ausente, ledger ausente/não selada/não vencida, claim ativa, claim concluída ou tentativa exaurida.
- Retorno de claim após lease vencido e tentativas <20, AND caso sem claim; concorrência de 2/10 scanners, SKIP LOCKED, COMMIT/ROLLBACK, claim simultânea, outbox ausente, custo ocioso, sem duplicidade e zero deadlocks.
- Identificar índices adicionais necessários para `EXISTS` e `NOT EXISTS`, penalidade de clock_timestamp, custo de locks; não afirmar índice ótimo sem medição.
- Verificar ausência de regressão e de atividade em Render/Supabase/Cloudflare/Android.

Gerar `PESSOASAQUI_ETAPA30D_AUDITORIA_PERFORMANCE_GC_R2.md`, diferenciando mocks de PG real. Antigravity só diagnostica, não altera código nem cria commits ou deploy.

**ETAPA 30D IMPLEMENTADA, AGUARDANDO AUDITORIA. R2 E GC PERMANECEM DESATIVADOS.**
