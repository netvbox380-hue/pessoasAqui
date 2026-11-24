# PESSOASAQUI — ETAPA 30Y-B: ADMISSÃO ASSINADA DE CONFIRMAÇÃO POR TENTATIVA FÍSICA (PREPARE ONLY)

## Baseline homologada
Proprietário informou homologação 30Y-A no SHA `0e461f3adce570d90f2c39eb0284d81bfdfdaf37`: regressão 208/208, testes dedicados, PostgreSQL 18.1 real, P-256, isolamento de chave, falhas fail-closed. Testes com storage R2 simulado, não Cloudflare físico. GitHub main estava **idêntica** à baseline antes do trabalho, e ponto de restauração `backup/main-pre-r2-delivery-20261114` continua com `02b6a487ac8b3ad6c64adf6ce8f6887dbfb53bf9`.

## Mudanças novas
- `backend/migrations/019_r2_attempt_verification_claims_PREPARE_ONLY.sql`: tabela de claims de verificação por **chave física** `r2_attempt_verification_claims`, FK para `message_r2_upload_attempts.object_key`, PK object_key, UUID único, message_id, identidade, geração física, SHA e bytes cifrados, estado RUNNING/COMPLETED/FAILED e prazo; RLS, REVOKE. NÃO substitui `r2_verification_claims` legado, nem altera tabelas existentes. Não aplicar em Supabase produção.
- `backend/src/r2StagedAttemptConfirmAdmission.js`: `admitAuthenticatedAttemptConfirm` exige prova P-256 **R2_CONFIRM**, com canonicalização existente `generation:1` (versão/protocolo legada), assinando a CHAVE FÍSICA da tentativa. Autenticação SPKI e bilateral, nonce, bloqueio pai lógico `message_r2_objects FOR UPDATE`, tentativa física `message_r2_upload_attempts FOR UPDATE`, checagens de dono/par, estado PUT_ISSUED ou VERIFYING, physical generation 1..5. Na mesma transação: claim single-flight por chave física com descritor SHA/bytes imutável entre retries, débito do orçamento diário 12 verificações/200MiB, transição `PUT_ISSUED→VERIFYING` e ticket INTERNO. UUID claim nunca deverá ser aceito do JSON Android. **Nenhuma leitura GET nem atualização UPLOADED**.
- `backend/test/r2StagedAttemptConfirmAdmission.test.js`: nove testes unitários com chaves ECDSA P-256 reais e simulação SQL; prova cruzada RESERVE/ISSUE_PUT, owner, state, replay, claim, quotas, descriptor, erro PG e invariantes sem UPLOADED. **Ainda não executados pelo ChatGPT.**

## ALERTAS bloqueantes para o próximo passo
1. **Não é a confirmação física nem escolha da vencedora.** 30W ainda usa `message_r2_objects.object_key` legada para GET/estado UPLOADED. 30Y-B só autoriza claim por tentativa. Ainda faltam segurança TOCTOU da mesma chave, imutabilidade de vencedor, ligação transacional de attempts→objeto lógico, consumo READ/ATTACH/GC.
2. A tabela 019 possui FK de objeto físico e validação pela ação 30Y-B, mas ainda não tem trigger própria de imutabilidade do descritor nem FK composto para identidade/generation. Auditoria PostgreSQL deve testar escrita SQL direta, grants, isolamento de roles e avaliar exigência de trigger/constraints adicionais antes do cutover.
3. O campo `generation` CANÔNICO R2_CONFIRM é 1, enquanto a geração da tentativa física pode ser 1..5. Ainda exige revisão do futuro protocolo Android e decisão sobre assinar geração física de modo explícito com versão nova; não interpretar implicitamente.
4. O claim RUNNING tem 180 segundos. A política 30W de selamento e expiração+120s pode exigir retry além desse período, consumindo quota. A etapa seguinte deve refatorar sequenciamento sem retornar suposta confirmação antes de segurança de PUT tardio. **Não afirmar TOCTOU eliminado** por assinatura ou timestamp.
5. `If-None-Match:*` impede overwrite enquanto o objeto existe, mas após DELETE pode permitir criação. GC NÃO pode apagar objeto com capability pendente, incluindo PUT em voo. Bucket Locks ainda dependem de verificação física em bucket descartável autorizado.
6. Não há rota ou import no `server.js`; não há SDK, Cloudflare físico, Render/Supabase produção, Android, Gradle, logos ou assinatura modificados.

## Auditoria Antigravity (somente diagnóstico)
Confirmar HEAD da main e SHA de backup, worktree isolada, PostgreSQL 18.1 local porta 54333 com migrações 001–019 **somente no banco descartável**. Executar:
```sh
node --test backend/test/r2StagedAttemptConfirmAdmission.test.js
node --test backend/test/r2StagedAttemptPutIssuance.test.js
node --test backend/test/r2ImmutableAttemptAllocator.test.js
node --test backend/test/r2StagedConfirmAdmission.test.js
node --test backend/test/r2VerificationAdmission.test.js
node --test backend/test/r2StagedPhysicalConfirm.test.js
node --test backend/test/r2ObjectIntegrity.test.js
node --test backend/test/r2ActionAuth.test.js
node backend/run-tests.js
node --test backend/test/r2ReadAdmission.postgres.test.js
```
Exigir regressão geral **208/208**, 9/9 novos testes e todos os específicos sem skips/falhas. Critérios PostgreSQL real:
1. Integridade migração 019, FK, índices, RLS, escopo backend, unique object_key e claim_id, CHECK hash/tamanho, tipos e compatibilidade 018.
2. Prova canônica R2_CONFIRM assinada para KEY B, nunca KEY A ou chave legada; RESERVE/ISSUE_PUT rejeitados antes de nonce, attempt ou quota.
3. Dois vínculos bilaterais FOR SHARE e SPKI ativa; revogação durante autorização bloqueia, nonce persistido na mesma tx; COMMIT/ROLLBACK de ledger, claim, estado VERIFYING e orçamento.
4. Ordem de locks: connections→message_r2_objects→message_r2_upload_attempts→attempt claim→budget. Testar 2/10/50 workers, deadlocks, tentativas diferentes da mesma mensagem, duas instâncias, QUOTA e clock UTC.
5. PUT_ISSUED válido→VERIFYING, ALLOCATED/VERIFIED/ABANDONED bloqueados; retries apenas se claim antigo FAILED ou expirado, com SHA/bytes idênticos, quota debitada somente após grant. Nunca permitir descritor diferente para mesma chave.
6. GET físico inexistente, UPLOADED nulo, nenhum ACK; falha em UPDATE claim, quota, commit faz rollback completo. Testar DB sem 019.
7. Identificar riscos de SQL direto na 019: FK composto/trigger de imutabilidade insuficiente, acesso indevido de roles, tentativa de criar claim mismatched sem helper. Emitir recomendação mandatória, não alterar código.
8. Analisar bloqueio TOCTOU sobre chave de mesma tentativa, PUT atrasado pós DELETE e necessidade de winner pin atômico. Não declarar que o mock comprova proteção de Cloudflare real.

Entregar `PESSOASAQUI_ETAPA30Y_B_AUDITORIA_ADMISSAO_CONFIRMACAO_TENTATIVAS_R2.md`.

Antigravity apenas diagnostica e testa: NÃO modifica código, commits, flags, deploys, Android, logotipos, Render/Supabase/Cloudflare produção.

**30Y-B IMPLEMENTADA PREPARATORIAMENTE, AINDA NÃO HOMOLOGADA. PRODUÇÃO PERMANECE BLOQUEADA.**
