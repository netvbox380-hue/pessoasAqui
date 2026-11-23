# PESSOASAQUI — ETAPA 30Y-A: PUT AUTENTICADO POR CHAVE FÍSICA DE TENTATIVA (PREPARE ONLY)

## Linha de base e ponto de restauração
Antigravity informou homologação preparatória 30X-IMPL no commit `2eaca24e2dad6b25053b3b068011dc50bb1450b5`: 208/208 regressão geral; 9/9 alocador; 15/15 migração 018, 17/17 operações PostgreSQL 18.1 e 10/10 cenários TOCTOU com storage **simulado**. A `main` foi verificada idêntica à baseline antes de 30Y-A, e o snapshot `backup/main-pre-r2-delivery-20261114` segue em `02b6a487ac8b3ad6c64adf6ce8f6887dbfb53bf9`.

## Implementação 30Y-A
Novo `backend/src/r2StagedAttemptPutIssuance.js` exporta `issueAuthenticatedAttemptPut`. Ao contrário do emissor legado `r2StagedPutIssuance.js`, a prova ECDSA P-256 de domínio exclusivo **R2_ISSUE_PUT** assina `messageId` e a **chave física da tentativa** (não a chave lógica legada). Dentro da MESMA transação PostgreSQL:
1. `executeAuthenticatedR2Action` valida chave SPKI ativa, vínculo bilateral FOR SHARE e nonce anti-replay;
2. adquire `message_r2_objects` por `message_id FOR UPDATE` (ordem de locks igual ao alocador 018), exige reserva lógica RESERVED/generation 1/lease ativo e dono/par corretos;
3. adquire `message_r2_upload_attempts` por `object_key+message_id FOR UPDATE`, exige geração de tentativa 1..5, mesmas identidades e estado ALLOCATED ou PUT_ISSUED, nunca chave física igual à lógica;
4. emite URL SigV4 através de signer **injetado pelo backend** usando `issuePutWithDurableRetirementFence`: PUT create-only, `If-None-Match: *` assinado, `Content-Type` assinado, expiração <=900s e ledger durável, monotônico, não selado (migração 013);
5. para a primeira emissão, faz transição `ALLOCATED → PUT_ISSUED` no MESMO COMMIT; reemissão legítima com assinatura nova mantém PUT_ISSUED; retorna URL somente após conclusão de `withTransaction`, isto é, após COMMIT no runner correto.

Novo `backend/test/r2StagedAttemptPutIssuance.test.js`: 9 testes de assinatura real P-256, isolamento de domínio, pair, owner lógico e físico, estado, reemissão, fence, signer, erro PG, rollback e campos inválidos. **Testes não executados pelo ChatGPT**.

## Escopo EXATAMENTE limitado
- Não modifica `server.js`, rotas, flags, migrações já existentes, Android, app/Gradle/logos/assinatura, Render/Supabase/Cloudflare produção ou bucket lock.
- Não altera o emissor legado existente (continua staged e NÃO roteado).
- Não faz PUT físico, não cria cliente SDK, não recebe credenciais, não faz HTTP nem marca mensagens `UPLOADED`.
- **A emissão por chave física não resolve sozinha TOCTOU.** Migração 018 é adicional: confirmação, seleção imutável da tentativa vencedora, anexação, READ, GC, outboxes e cancelamento ainda têm que migrar para referenciar a chave física. Não ativar nada até que TODOS esses fluxos estejam integralmente ligados e homologados.
- URL PUT é capacidade bearer; não registrar em logs. A reemissão da URL pode estender janela ledger e atrasar confirmação. Validar revogação e PUT em voo, sob storage fake e, futuramente, em bucket descartável autorizado.
- Não supor que `If-None-Match: *` impede regravação após DELETE; nunca excluir chave com capability ainda ativa. UUID físico exclusivo por tentativa limita substituição entre tentativas mas não é sozinho uma prova de write-once absoluta.
- A quota diária de RESERVE não limita automaticamente as até 5 emissões de URL por objeto e nem tentativas de presign; rate limit, bytes e budget permanecem itens de cutover.
- A assinatura `R2_ISSUE_PUT` da geração física usa chave física e mensagem; não introduzir geração em canonicalization implicitamente. Android ainda não implementado. Nenhum fallback RESERVE.

## Antigravity — Auditoria 30Y-A: APENAS diagnóstico e testes
Confirmar HEAD main, backup SHA, worktree isolada, PG18.1 local :54333 com migrações 001–018 **somente** em banco descartável. Sem dados/credenciais ou acessos de produção.
Executar:
```sh
node --test backend/test/r2StagedAttemptPutIssuance.test.js
node --test backend/test/r2ImmutableAttemptAllocator.test.js
node --test backend/test/r2StagedPutIssuance.test.js
node --test backend/test/r2PutRetirementFence.test.js
node --test backend/test/r2CreateOnlyPut.test.js
node --test backend/test/r2ActionAuth.test.js
node --test backend/test/r2StagedPhysicalConfirm.test.js
node --test backend/test/r2StagedConfirmAdmission.test.js
node backend/run-tests.js
node --test backend/test/r2ReadAdmission.postgres.test.js
```
Exigir 208/208 geral, 9/9 novos testes e todos os correlatos; **zero falhas e zero skips**.

Em PostgreSQL REAL + signer/mock:
- Assinatura P-256 `R2_ISSUE_PUT` correta para **Key B** após reserva lógica e alocação 018; demonstrar que assinatura da **Key A**, da chave legada ou da mensagem errada não autoriza Key B. RESERVE, CONFIRM e READ não podem emitir PUT.
- Nonce e locks, mídia lógica RESERVED, lease ativo, dono e destinatário em AMBAS as tabelas, tentativa ALLOCATED ou PUT_ISSUED, geração física 1–5; bloquear VERIFIED/VERIFYING/ABANDONED/owner spoof/expiração.
- Emissão após COMMIT: errar signer, ledger 013, UPDATE de estado ou COMMIT e comprovar rollback completo, inclusive nonce. Nenhum URL retornado em erros.
- Reemissão com nonce novo não usa outra chave, não reduz expiration, não renova lease lógico; chave selada bloqueia reemissão e nunca reabre.
- Concorrência de emissão com alocação (2/10/50), mark/unmark e finalização; ordem de FOR UPDATE não permite deadlock nem bypass; verificar SELECT/FOR SHARE.
- Verificar migração 018, RLS e permissões, ausência de código Android/server.js alterado. Nada conecta legacy ATTACH/READ/GC: mapear a próxima integração sem afirmar cutover.
- TOCTOU fake: começar PUT A, gerar Key B, finalizar B e então deixar PUT A concluir; não modificar B. Testar risco residual de PUT de B após DELETE e Bucket Lock como hipótese, não garantia comprovada.
- Se teste falhar, apresentar stack/causa e NÃO corrigir nada.

Gerar `PESSOASAQUI_ETAPA30Y_A_AUDITORIA_PUT_TENTATIVA_FISICA_R2.md`.

Antigravity somente diagnostica e testa: **sem correções, commits, push, deploy, SQL produção, Android, Render/Supabase/Cloudflare produção**.

**30Y-A IMPLEMENTADA E NÃO HOMOLOGADA. ETAPA 30/TOCTOU AINDA BLOQUEADA PARA PRODUÇÃO.**
