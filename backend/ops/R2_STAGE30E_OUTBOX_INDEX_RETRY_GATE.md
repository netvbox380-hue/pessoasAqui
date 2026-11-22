# PESSOASAQUI — ETAPA 30E: Índice de outbox e backoff persistente na descoberta (STAGED)

## Base homologada
Antigravity informou Etapa 30D homologada como **preparatória** no SHA `f0a5fd734cc1fb3d69ca7745a5aa2d7cfa2b2c27`, PostgreSQL 18.1 local porta 54333, migrações 004–015, 37/37 testes unitários R2, 208/208 regressão, 2/2 PostgreSQL zero skips e 16 cenários reais. Alerta: `Nested Loop` com 249.500 comparações em amostra de 500 linhas, sem índice em `message_r2_delete_outbox(message_id)`. Produção não envolvida.

Checkpoint verificado: `backup/main-pre-r2-delivery-20261114` (SHA `02b6a487ac8b3ad6c64adf6ce8f6887dbfb53bf9`). Main estava no SHA Etapa 30D antes das alterações.

## Implementações
1. Migração 016 `backend/migrations/016_r2_gc_outbox_message_lookup_PREPARE_ONLY.sql` (**não aplicada em produção**): índice parcial composto `(message_id, object_key) WHERE state IN ('PENDING','RETRY')` para o EXISTS do scanner. Evita indexar jobs DONE e associa a procura de message_id com a chave exata. A migração 004 já possuía `next_attempt_at` e `idx_r2_delete_retry`; NÃO criar coluna redundante.
2. `backend/src/r2DeleteCandidateDiscovery.js`: para considerar um job, agora exige outbox `PENDING/RETRY` e `next_attempt_at <= clock_timestamp()`. Preserva filtro de recibo terminal, janela PUT selada/expirada, ausência de claim em lease ativo/completed/exhausted, lote limitado 32 e `SKIP LOCKED`.
3. `backend/test/r2DeleteCandidateDiscovery.test.js`: teste unitário do formato SQL, complementando a cobertura existente.

## Limitações e requisitos de integração
- Nenhuma medição de ganho de desempenho foi executada pelo ChatGPT; provar usando `EXPLAIN (ANALYZE, BUFFERS)` isolado com/sem índice, mesma massa de dados e estatísticas atualizadas. Índice parcial pode ser ignorado em tabelas pequenas ou por estimativa do planner.
- **Não há worker agendando o próximo `next_attempt_at`** depois de falha. A filtragem respeita o campo já existente mas não implementa política exponencial com jitter. Claims ainda vencem em 90 s e precisam permanecer reconciliáveis.
- O scanner continua apenas discovery; claim com token+generation (30B) é obrigatória; ausência de worker real, S3 DeleteObject, completude transacional pós-I/O, tratamento CANCELLED/orphan/legacy, outbox de revogação nas rotas reais e HTTP/SDK de R2. Nenhum endpoint, deploy, migração produção ou alteração Android.
- Egress de Supabase Free exige benchmarks pequenos e indexados, evitando cargas massivas ou produção.
- Migrações 004–016 devem ser aplicadas **somente no banco de testes PostgreSQL 18.1**.

## Auditoria obrigatória Antigravity — apenas diagnóstico
Confirmar SHA da `main` e branch de restauração. Worktree isolada; preservar `app/build.gradle.kts`, logotipo, Android e repositório principal.

Executar:
```sh
node --test backend/test/r2DeleteCandidateDiscovery.test.js
node --test backend/test/r2DeleteClaimLedger.test.js
node --test backend/test/r2DeletionAdmission.test.js
node --test backend/test/r2PutRetirementFence.test.js
node --test backend/test/r2StagedDeliveryRuntime.test.js
node backend/run-tests.js
```
Executar `backend/test/r2ReadAdmission.postgres.test.js` com `PESSOASAQUI_TEST_PG_URL` em PG18 isolado `127.0.0.1:54333` e zero skips.

Testar PostgreSQL real:
- Aplicar migração 016 após 004–015. Com massa de 500+ linhas, comparar `EXPLAIN (ANALYZE, BUFFERS)` antes/depois, `ANALYZE` e distribuição de PENDING/RETRY/DONE. Registrar tempos, loops, shared hits e seq/index scan; não inferir melhoria só da presença do índice.
- Jobs DONE e futuros `next_attempt_at` não são selecionados; PENDING/RETRY com vencimento passado são elegíveis se os demais gates estão atendidos. Conferir igualdade no instante do deadline e casos `NULL`/linhas malformadas.
- Concorrência de scanners 2/10 e SKIP LOCKED, cancelamentos e transações concorrentes, claim ainda obrigatória, rollback/COMMIT, zero deadlocks.
- Diferenciar comportamento com índice, sem índice e outbox vazia; avaliar consultas nas outras tabelas (receipt, ledger, claims) e crescimento das indexes.
- Auditar explicitamente que a migração 004 já contém `next_attempt_at`, inclusive sua ordenação e futuro backoff; declarar que nenhum worker ou retry foi ativado.

Entregar `PESSOASAQUI_ETAPA30E_AUDITORIA_INDICE_OUTBOX_BACKOFF_R2.md`, separando mocks e PG real. Antigravity não modifica código, não commita, não executa deploy e não acessa Render, Supabase, Cloudflare de produção nem Android.

**Status: Etapa 30E implementada, NÃO homologada. Etapa 30 incompleta; R2 e GC desativados.**
