# PESSOASAQUI — ETAPA 30Y-C2: PIN DA TENTATIVA VENCEDORA APÓS GET/SHA-256 (PREPARE ONLY)

## Baseline
Proprietário encaminhou auditoria 30Y-C1 sobre `24e0c40816f1e27463df2927564f6db989e73c1c` com 208/208 geral, 66/66 específicas, 32/32 PostgreSQL 18.1 dedicado, sem falhas. **BLOQUEADOR** confirmado: SQL INSERT em `message_r2_winning_attempts` aceitava claim RUNNING porque FK não prova claim COMPLETED. Antes de iniciar C2, GitHub `main` verificada idêntica ao SHA auditado e backup `backup/main-pre-r2-delivery-20261114` íntegro no SHA `02b6a487ac8b3ad6c64adf6ce8f6887dbfb53bf9`.

## Mudanças — C2 PREPARATÓRIA
1. `backend/migrations/021_r2_winner_completed_state_gate_PREPARE_ONLY.sql`: adiciona trigger BEFORE INSERT na winner para exigir claim COMPLETED+completed_at, tentativa física VERIFIED, identity/recipient/message/key/generation/sha/bytes correspondentes e reserva lógica pai RESERVED com lease ativo. Rejeita diretamente inserção com claim RUNNING/FAILED. Não reescreve tabelas existentes, não faz backfill. **Não foi aplicada ao Supabase produção**.
2. `backend/src/r2StagedAttemptWinnerFinalizer.js`: orquestrador interno `verifyAndSelectStagedAttemptWinner` que executa:
   - admitAuthenticatedAttemptConfirm (30Y-B: prova ECDSA P-256, par bilateral, nonce, orçamento de verificação, claim RUNNING por key física, attempt VERIFYING);
   - preflightAttemptWinner: nova tx, reconfere P-256 ativa, par bilateral, objeto lógico RESERVED, attempt física VERIFYING, claim RUNNING, ausência de winner; sela janela PUT física e exige última expiração +120s antes do GET (fail-closed). **Uma URL válida nunca resulta em GET/COMPLETED.**
   - verifyStoredCiphertext: GET de ciphertext com stream limitado <=25MiB, timeout default 30s, SHA-256 do conteúdo recuperado; **fora de SQL**. Não confiar em ETag, HEAD ou JSON Android.
   - settleVerifiedAttemptWinner: nova tx, reconfere credencial/pair/parent/attempt/claim/ledger e evidência do GET; atualiza claim COMPLETED, tentativa VERIFIED e INSERT no winner em **um COMMIT**; se qualquer etapa ou COMMIT falhar, rollback integral. Sem dupla vencedora, sem ACK, sem atualizar o `message_r2_objects.object_key` legado.
3. `backend/test/r2StagedAttemptWinnerFinalizer.test.js`: 10 cenários simulados, incluindo ordem de locking, PUT ainda ativo, mismatch SHA/bytes, revogação, claim expirado, rollback de claim/attempt/winner e falhas de BD. `backend/test/r2WinnerCompletedStateGate.test.js`: quatro verificações estáticas de SQL. **Nenhuma nova suíte executada pelo ChatGPT; todos os testes estão pendentes de homologação com Antigravity e PG real.**

## GARANTIAS QUE AINDA NÃO TEMOS — CUTOVER BLOQUEADO
- O gatilho 021 impede INSERT com claim RUNNING **mas não comprova que a transição para COMPLETED foi causada por GET físico real**; uma role privilegiada com UPDATE claim/attempt + INSERT winner ainda consegue forjar estado. Exigir papéis de banco de menor privilégio, SQL procedures restritas e GRANTs mínimos antes de ativar.
- A função de baixo nível `settleVerifiedAttemptWinner` aceita um objeto `verification` interno para testes; não expor função ou campo `verified:true` em rota, plugin, SDK ou Android. O **único orquestrador de produto** deve usar `verifyStoredCiphertext` de dentro do backend sem injetar sucesso do cliente. A auditoria deve identificar riscos de bypass por futuro wiring/DI.
- **Uma URL PUT que expirou não demonstra conclusão de uploads HTTP já iniciados.** A barreira temporal de expiração+120s permanece e o claim inicial de 180s pode expirar antes de vencida a URL. O fluxo intencionalmente retorna `R2_WINNER_PUT_WINDOW_ACTIVE` sem GET, exigindo novo R2_CONFIRM após esgotar janela e consumindo quota. Isso não é experiência final aceitável. Propor garantia de transporte/write-once que permita confirmação rápida sem TOCTOU.
- `If-None-Match:*` só impede overwrite enquanto o objeto existe; após DELETE prematuro, PUT atrasado poderá recriá-lo. Portanto **GC físico e Cloudflare bucket real NÃO ativados**.
- Mesmo com winner pin válida, **ATTACH, READ, Delete/GC, delivery outbox e HTTP continuam consumindo o modelo legado** `message_r2_objects.object_key`. O vínculo vencedor permanece INERTE, sem mudança para UPLOADED no pai legado. Não considerar mídia disponível até esses caminhos serem atualizados e homologados. Sem rota `server.js` nova, sem Android/Gradle/keystore/logos alterados, sem migração em produção, sem SDK AWS/Cloudflare novo.
- Um SHA-256 do ciphertext não comprova E2EE semanticamente; Android deve validar envelope criptográfico após download. Nenhum ACK de entrega é produzido pelo finalizador.

## Antigravity — HOMOLOGAÇÃO 30Y-C2
Apenas diagnosticar e testar. Validar SHA exato na main e backup, worktree isolada, PostgreSQL 18.1 em 127.0.0.1:54333 com migrações 001–021 SOMENTE banco descartável. Executar:
```sh
node --test backend/test/r2WinnerCompletedStateGate.test.js
node --test backend/test/r2StagedAttemptWinnerFinalizer.test.js
node --test backend/test/r2WinningAttemptMigration.test.js
node --test backend/test/r2StagedAttemptConfirmAdmission.test.js
node --test backend/test/r2StagedAttemptPutIssuance.test.js
node --test backend/test/r2ObjectIntegrity.test.js
node --test backend/test/r2PutRetirementFence.test.js
node --test backend/test/r2ActionAuth.test.js
node backend/run-tests.js
node --test backend/test/r2ReadAdmission.postgres.test.js
```
Exigir 208/208 geral, 10/10 finalizer simulados, 4/4 testes SQL, correlações sem falha e sem skips (contagens ajustadas ao que se executou de fato).
Com PostgreSQL real:
1. Migrações 001–021 aplicáveis. INSERT winner sobre claim RUNNING ou FAILED deve falhar 23514; COMPLETED sem completed_at deve falhar. Tentativa ALLOCATED/PUT_ISSUED/VERIFYING não pode virar vencedora. Dados compostos falsificados recusados. Comparar efeito dos triggers C1/C2.
2. Emissão PUT por chave B após reserva: claim CONFIRM por B, selamento de B no ledger. Janela PUT aberta exige retry-later sem GET. Não falsificar relógio do Supabase; para fixtures usar DB isolado transacional.
3. Após janela de B expirada +120s, GET com stream de ciphertext real simulado (Buffer/Readable) fora de transação; comparar SHA/bytes e limites, interrupções, timeout, objeto ausente, erro R2; qualquer falha mantém claim/attempt/winner sem COMPLETED/VERIFIED.
4. Em uma transação, claim RUNNING→COMPLETED, attempt VERIFYING→VERIFIED e winner INSERT, com FK composta e trigger 021. Injetar falha em UPDATE, INSERT, trigger, COMMIT: rollback completo de tudo. Nenhum segundo winner por 23505. Testar concorrência 2,10,50 workers de 2 tentativas A/B; 1 vencedora, sem deadlock, sem falso ACK.
5. Girar SPKI, revogar vínculo bilateral, vencer lease lógico, vencer claim, abandonar tentativa ou selecionar winner por outra worker DURANTE o GET: revalidação deve impedir seleção tardia. Verificar ordem de locks e nenhum lock durante GET.
6. Verificar policy de DB roles, acesso direto a UPDATE claim/attempt e INSERT winner; se um papel comum de app puder forjar COMPLETED, classificar como **bloqueador de produção**. Avaliar se trigger 021 cobre o ataque de C1 sem confundir com prova de GET físico.
7. Critério **crítico TOCTOU**: PUT em voo após URL expirar e depois da margem, PUT após DELETE, Object Lock/Bucket Lock e write-once real; storage simulado não é prova física. Documentar janela de 17 minutos/claim 180s e opções para reduzir latência de maneira comprovável.
8. CONFIRM canonical operation assina geração de protocolo 1 enquanto generation da tentativa física é 1..5. Garantir não haver domínio misturado. Não ativar nenhum endpoint, GC, Android ou dependência.
9. Verificar `main`, checkpoint, Android/Gradle/logos/keystore, Render/Supabase/Cloudflare produção intocados. Não mudar código.

Relatório `PESSOASAQUI_ETAPA30Y_C2_AUDITORIA_WINNER_FINALIZER_R2.md` com resultados exatos e bloqueadores remanescentes.

**Etapa 30Y-C2 somente preparatória, NÃO homologada. Cloudflare TOCTOU, SQL direct-state forgery, ATTACH/READ/GC permanecem bloqueadores de produção.**
