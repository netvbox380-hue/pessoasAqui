# PESSOASAQUI — ETAPA 30Y-D1: ATTACH DO ENVELOPE E2EE À CHAVE FÍSICA VENCEDORA

**ESTADO: PREPARE ONLY — IMPLEMENTADA, NÃO TESTADA/HOMOLOGADA. NÃO ATIVAR R2.**

## Fundamentação
O proprietário forneceu diagnóstico 30Y-D de 24/11/2026 sobre o commit `f65a72e872f0b375623350c3546e0a5c2f544e62`: 208/208 backend; 147/147 R2; 25/25 attach legado. Incompatibilidade: `r2MediaRegistry.js` e `r2RecipientReadAuth.js` buscam `message_r2_objects.object_key`, enquanto C2 armazena chave física única em `message_r2_winning_attempts.object_key` e deixa pai `RESERVED`. A main estava idêntica à baseline antes da implementação; ponto de restauração `backup/main-pre-r2-delivery-20261114` preservado em `02b6a487ac8b3ad6c64adf6ce8f6887dbfb53bf9`.

## Implementação nova (não muda rotas nem o código legado)
- `backend/migrations/022_r2_e2ee_winner_reference_PREPARE_ONLY.sql`: adiciona coluna opcional `e2ee_messages.r2_winning_object_key` (texto legado preservado); UNIQUE(message_id,object_key) na winner e FK **composta** `(e2ee_messages.id,e2ee_messages.r2_winning_object_key)` → `(winner.message_id,winner.object_key)` (não aceita vencedor pertencente a outra mensagem), índice UNIQUE não-NULL para impedir associação física duplicada e trigger que proíbe posterior troca ou remoção da chave física. Não altera mensagem de texto com NULL, não faz backfill, não foi aplicada em produção.
- `backend/src/r2StagedWinningMediaAttach.js`: novo entrypoint `attachAuthenticatedWinningR2Media` (**staged; não roteado**). Valida objeto/chave, envelope `ENC_ECDH:`, tamanho 16–65536 bytes, hash SHA256 do descritor UTF-8, metadados e `generation=1` do protocolo P-256. Executa `executeAuthenticatedR2Action('R2_ATTACH')` com domínio específico, chave ativa, par bilateral e nonce persistente. Na MESMA transação, bloqueia pai lógico por `message_id FOR UPDATE`, nunca procura a winner pela chave do pai; exige vencedor imutável com EXACT physical key, metadados, attempt VERIFIED, claim COMPLETED; verifica recibo terminal; detecta colisões e retries bit-a-bit idênticos; insere e2ee_messages PENDING com vínculo FK à winner e faz `RESERVED→ATTACHED` no pai **no mesmo COMMIT**. Falhas provocam rollback da mensagem e estado. Retries idênticos não reescrevem timestamps. Não tem ACK ou GET, não expõe plaintext.
- `backend/test/r2StagedWinningMediaAttach.test.js`: oito testes simulados incluindo prova, ordem de locks, vencedor exato, digest criptográfico, idempotência e rollback. **Ainda não executados** neste ambiente; exigir audit PostgreSQL 18.1 real.

## Divergências detectadas no relatório (VALIDAR ANTES DE D2)
1. O diagnóstico 30Y-D menciona 50 READs/2GB por dia; o código ATUAL `r2ReadAdmission.js` define **24 READs/dia e 300 MiB/dia**. NÃO alterar cota por inferência; usuário aprova política depois.
2. `r2ActionAuth.js` só aceita `generation:1` na assinatura P-256 `R2_ATTACH`, enquanto a geração física da tentativa é 1..5. O novo ATTACH assina o KEY físico + descritor e lê geração física exclusivamente do banco, **não aceita geração física como generation na prova**. Alteração de contrato criptográfico precisa de protocolo v2 explícito e testes Android; não mudar silenciosamente canonicalização.
3. O relatório afirma que trigger 021 garante claim COMPLETED e attempt VERIFIED pela simples existência da winner. Gatilho 021 verifica estado **no INSERT**, não funciona como atestado perpetuamente atualizado; novo ATTACH reconsulta claim e attempt sob FOR SHARE. Não concluir imutabilidade física do bucket a partir desses estados.
4. A transição direta do pai RESERVED→ATTACHED precisa ser auditada contra TODO histórico de triggers/constraints antigos (005–021), ledger de DELETE, receipts, GC e `e2ee_messages` real. Se alguma trigger impedir, NÃO desabilitar sem avaliar com owner.
5. `r2MediaRegistry.js` e `r2RecipientReadAuth.js` legados permanecem INTACTOS para compatibilidade. D2 deverá acrescentar/adaptar READ isolado, inclusive `r2StreamStartGate` e `r2StreamingGateway` em transações independentes, nunca autorizar legacy key via fallback.
6. **CUTOVER BLOQUEADO:** nenhum PUT físico Cloudflare, Bucket Lock, prova anti-TOCTOU em storage real, garantia de PUT tardio pós-DELETE, papéis DB de menor privilégio, READ/GC, Android, fluxo E2EE completo ou ACK assinado foi ativado. O ATTACH não representa entrega ao aparelho.

## Antigravity — homologação obrigatória 30Y-D1 (somente diagnóstico e teste)
Confirmar HEAD em main, backup, worktree isolada, PostgreSQL 18.1 descartável :54333, migrações 001–022 SOMENTE TESTES, nada de serviços de produção.
```sh
node --test backend/test/r2StagedWinningMediaAttach.test.js
node --test backend/test/r2MediaAttach.test.js
node --test backend/test/r2LeasedMediaAttach.test.js
node --test backend/test/r2MediaRegistry.test.js
node --test backend/test/r2StagedAttemptWinnerFinalizer.test.js
node --test backend/test/r2ActionAuth.test.js
node --test backend/test/r2WinningAttemptMigration.test.js
node --test backend/test/r2WinnerCompletedStateGate.test.js
node backend/run-tests.js
```
Exigir 8/8 novos, 208/208 geral e todos os legados e R2, 0 falhas/skips.
1. Aplicar migrações 001–022 no PG isolado. Verificar FK composta id/key, UNIQUE física, trigger UPDATE, mensagens de texto com NULL e não alteração de contratos antigos. Tentar FK de vencedor de OUTRA mensagem, chave inexistente, UPDATE/DELETE vinculado, duplicar objectKey, alterar binding em SQL direto; reportar SQLSTATE.
2. Prova R2_ATTACH real P-256 de chave física B, mismatch assinatura A/B, RESERVE/ISSUE_PUT/CONFIRM, nonce replay, SPKI rotacionada, unilateral, revogação concorrente, fraude de alias/descriptor e troca de contentSha256.
3. Pai RESERVED e lease ativo, winner B imutável, attempt VERIFIED e claim COMPLETED. Forçar ausência, owner/sender incorreto, vencedor A (não B), wrong SHA/bytes, estado FAILED/RUNNING, mídia expirada.
4. Identificar lock order e deadlocks 2/10/50 workers. Dois ATTACH idênticos retornam apenas uma inserção e uma mudança de estado, segundo duplicate:true sem refresh de timestamp. ATTACH conflitante não altera a mensagem. Receipts terminais sempre impedem ressurreição mesmo em retry. COMMIT/INSERT/UPDATE falham: rollback TOTAL.
5. Testar com schema real se `message_r2_objects.state='RESERVED' → 'ATTACHED'` satisfaz ou conflita com triggers anteriores. Não desligar triggers nem improvisar fallback; se bloquear, declarar 30Y-D1 NÃO homologada.
6. Compatibilidade E2EE `ENC_ECDH:`, digest UTF-8 de descritor, limite; não logar dados, não tratar hash como decriptação. Prova physical generation x protocol generation diferenciada.
7. Auditar o que ocorre em DELETE de e2ee_messages após ACK: FK reversa não impede deletar linha mas tombstone do winner permanece. Analisar riscos de GC, reconstituição, histórico retido e privacidade.
8. Mostrar ausência de import em server.js, inexistência de R2 físico, ausência de endpoint/ACK e ausência de alterações em Android/Gradle/keystore/logos/Render/Supabase/Cloudflare produção.
9. Auditar privilégios PostgreSQL que poderiam inserir ATTACHED ou alterar e2ee_messages diretamente; bloquear CUTOVER até mitigar SQL privileged-state forgery.
10. Registrar divergência de READ quota (relatório 50/2GB vs código 24/300MiB), sem mudança.

Gerar `PESSOASAQUI_ETAPA30Y_D1_AUDITORIA_ATTACH_VENCEDORA_R2.md`.
**Antigravity não corrige, não faz commit/push/deploy, não acessa produção.**
