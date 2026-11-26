# PESSOASAQUI — ETAPA 30Y-D1-FIX-IMPL / MIGRAÇÃO 023

**PREPARE ONLY — NÃO HOMOLOGADA — CUTOVER R2 BLOQUEADO**

## Contexto auditado
Antigravity apontou SQLSTATE 23514 no PostgreSQL 18.1 em D1: migration 008 só aceita pai RESERVED→UPLOADED→ATTACHED e CHECK exige content_sha256/content_bytes/uploaded_at no pai; etapa 30X+ deixa esses metadados exclusivamente na tentativa física vencedora e pai RESERVED. Antigravity não modificou código. Antes da implementação main confirmada igual a `fc3bd630981c52e77e2f43b55a64cdf77f2f25ec` e checkpoint histórico `02b6a487ac8b3ad6c64adf6ce8f6887dbfb53bf9` íntegro. **Novo checkpoint** verificado `backup/main-pre-r2-d1-fix-20261126` → `fc3bd630981c52e77e2f43b55a64cdf77f2f25ec`.

## Alterações efetuadas na main
1. `backend/migrations/023_r2_allow_parent_winner_attach_PREPARE_ONLY.sql`: adiciona boolean NOT NULL DEFAULT FALSE `r2_winner_attach_mode`; substitui CHECK `message_r2_objects_completed_fields_chk` preservando toda regra do modo legado, com exceção rigorosamente discriminada para *somente* pai `ATTACHED` em modo winner, sem SHA/size/uploaded_at redundantes e com `attached_at` exigido. `CREATE OR REPLACE FUNCTION pessoasaqui_guard_media_state_transition` preserva ramificações da migration 008 (todas as transições lineares, órfão e delete outbox), e admite nova `RESERVED→ATTACHED` com mudança única `FALSE→TRUE` de mode sob verificação ativa de winner, attempt VERIFIED, claim COMPLETED, E2EE PENDING com FK física, sem receipt terminal, identidades corretas, pai não expirado e imutabilidade de chave/identidade/metadata/lease/geração. Depois de ATTACHED em modo winner, bloqueia alteração de modo e QUALQUER transição posterior do pai enquanto GC seguro por chave física estiver pendente. Isso evita enviar a chave lógica legada para o GC.
2. `backend/src/r2StagedWinningMediaAttach.js`: troca exclusivamente o `UPDATE` do pai para `SET state='ATTACHED',r2_winner_attach_mode=TRUE` em uma operação no mesmo COMMIT de `e2ee_messages`. Não altera assinatura P-256, extração winner, idempotência, verificação E2EE ou quota.
3. `backend/test/r2WinnerParentStateMigration.test.js`: seis testes estáticos de compatibilidade e nova proteção 023.

Nenhum código legado `r2MediaRegistry.js`, READ, DELETE ou GC foi alterado. Sem migração de produção, Render, R2 Cloudflare, Android, Gradle, keystore e logotipos.

## Reauditoria obrigatória Antigravity (SOMENTE TESTES)
Verificar commit HEAD e backup, worktree isolada, PostgreSQL 18.1 descartável porta 54333, aplicar 001–023 **somente no banco descartável**. Executar:
```powershell
node --test backend/test/r2WinnerParentStateMigration.test.js
node --test backend/test/r2StagedWinningMediaAttach.test.js
node --test backend/test/r2MediaAttach.test.js
node --test backend/test/r2LeasedMediaAttach.test.js
node --test backend/test/r2MediaRegistry.test.js
node --test backend/test/r2WinnerCompletedStateGate.test.js
node --test backend/test/r2WinningAttemptMigration.test.js
node --test backend/test/r2ActionAuth.test.js
node --test backend/test/r2StagedAttemptWinnerFinalizer.test.js
node backend/run-tests.js
```

**SQL REAL**, além dos testes de mock:
- Aplicar 001–023 do zero em PG18, confirmar CHECK, DEFAULT e função/trigger como compilados (pg_get_functiondef, pg_get_constraintdef, pg_trigger). Confirmar que reservas/texto/legado recebem default false e inserts maliciosos winner-mode em RESERVED/ORPHAN/DELETE são recusados.
- Criar reserva e winner válidos com tentativa real VERIFIED, claim COMPLETED, e2ee_messages apontando pela FK composta à winner; fazer UPDATE pai `RESERVED→ATTACHED, mode=true` no mesmo COMMIT da inserção E2EE. Confirmar sucesso, sem SHA/bytes/uploaded_at no pai, `attached_at` definido. Repetir assinatura R2_ATTACH verdadeira no entrypoint (ECDSA P256) e demonstrar nonces + idempotência.
- Negativos: tente apenas `RESERVED→ATTACHED` sem flag; flag sem transição; flag em INSERT; mudança de key/message/identities/gen/lease/hash; pai expirado; claim RUNNING/FAILED; attempt VERIFYING/ABANDONED; winner de outra msg; E2EE com referência errada, cancelada, entregue, ausente; receipt terminal; hash/bytes errados; trocas diretas SQL. **Todo bypass deve falhar 23514/23503/23505** conforme aplicável, sem desabilitar triggers. Registrar SQLSTATE exato.
- Legacy transitions `RESERVED→UPLOADED→ATTACHED→DELETE_QUEUED→DELETED` ainda passam com metadados e outbox, e continuam rejeitadas quando critérios não satisfeitos. Caminhos ORPHAN_QUEUED/ORPHAN_DELETED permanecem compatíveis com 008. **No winner mode, ATTACHED→DELETE_QUEUED é bloqueada deliberadamente**, porque GC legado apagaria a chave lógica em vez da física.
- Concurrência 2,10,50 (inclusive duas ATTACH simultâneas idênticas e conflitantes). Exigir uma mensagem E2EE por ID, uma vencedora, 0 deadlocks, retry `duplicate=true` sem alteração de timestamps, rollback em erro de INSERT/UPDATE/COMMIT. Auditar lock-order (key/pair, parent, winner, claim, E2EE).
- Confirmar testes antigos R2 sem regressão; backend/run-tests.js 208/208; novos 6/6; ATTACH 8/8. Nenhum ACK, nenhum GET, nenhum endpoint/worker ou produção ativado.
- Inspecionar DDL, segurança PostgreSQL: role app com DML direto ainda pode forjar transições e estado; exigir plano SEC DEFINER com privilégios mínimos. Testar se função trigger sem SELECT lock próprio é suficiente contra mutações paralelas; staging outbox/winner caveats. Respeitar a prova física Cloudflare ausente, PUT tardio/If-None-Match, Bucket Lock e retention como bloqueadores.
- Critérios de validação global: 0 falhas, 0 skips, não mascarar 23514 com permissões ampliadas ou triggers OFF. Relatar casos não testados, sem inventar resultados.

**Relatório:** `PESSOASAQUI_ETAPA30Y_D1_FIX_AUDITORIA_MIGRACAO023_R2.md`.
**Veredito:** somente preparatório caso passe; NÃO desbloquear READ/GC ou produção.

## Limitações e decisões explícitas
- O discriminador e seu CHECK não autorizam READ nem o GC. Em modo winner, processo de delete **precisa** usar winner.object_key, não o object_key lógico, e definir separadamente retenção e tombstones sem permitir re-upload.
- Um trigger verifica estado SQL no momento do UPDATE, não prova permanência física, WORM, autenticidade criptográfica do conteúdo ou força eventual de um PUT já iniciado. Apenas o backend que executou GET físico válido fornece evidência adequada. Direct DML por roles privilegiadas é bloqueador para cutover.
- Não alterar `r2ActionAuth.js`: geração do PROTOCOLO 1 é distinta da geração física 1–5; a chave física entra no objeto assinado.
- As quotas READ no código são 24/dia e 300 MiB/dia, não 50/2GiB; aguardar decisão de produto.
