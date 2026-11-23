# PESSOASAQUI — ETAPA 30X-IMPL: REGISTRO IMUTÁVEL DAS TENTATIVAS FÍSICAS DE UPLOAD

## Baseline e decisão de segurança
O usuário forneceu a homologação **DIAGNÓSTICA/PREPARATÓRIA** 30X sobre `5d3aba2526949738663a5b21535f675b6e736ff1`: 10/10 cenários TOCTOU simulados, PostgreSQL 18.1 isolado, storage R2 SIMULADO. A auditoria recomenda chaves por tentativa, PUT create-only assinado, Bucket Locks com atenção ao GC. O resultado **NÃO PROVA** comportamento físico de Cloudflare, nem elimina TOCTOU no código existente que continua mapeando `message_r2_objects.message_id UNIQUE` a um objeto físico. `main` foi verificada idêntica à baseline e ponto de restauração `backup/main-pre-r2-delivery-20261114` confirmado no SHA `02b6a487ac8b3ad6c64adf6ce8f6887dbfb53bf9`.

## Implementação aditiva, não ativa
1. Nova migração **`backend/migrations/018_r2_upload_attempts_PREPARE_ONLY.sql`**: `public.message_r2_upload_attempts` é um registro adicional, referenciado por `message_id` único da tabela lógica preexistente `message_r2_objects` (tabela 005). Cada objeto físico tem `object_key` PK e índice `UNIQUE(message_id,generation)`. IDs opacos têm formato UUID v4 e prefixo correspondente aos bytes do UUID. Identidade remetente/destinatário 16 hex, estados `ALLOCATED,PUT_ISSUED,VERIFYING,VERIFIED,ABANDONED`, geração 1–5. Trigger bloqueia DELETE e reatribuições de object key/message/generation/identities/timestamp; transições de estado permitidas somente em ordem estrita. RLS ativa e REVOKE PUBLIC/anon/authenticated. **Sem backfill, sem switch de chaves anteriores, sem alteração de existing constraints, triggers ou regras GC.**
2. Novo **`backend/src/r2ImmutableAttemptAllocator.js`**: `allocateImmutableR2Attempt({withTransaction,messageId,senderIdentity,recipientIdentity,randomUUID})` gera chave UUID v4 SOMENTE NO BACKEND; consulta a reserva lógica em `message_r2_objects` com FOR UPDATE para serializar gerações interinstância e confirma dono, destinatário, estado RESERVED, lease ativo e geração lógica 1. Calcula `MAX(generation)+1` no banco, limite conservador 5 tentativas e efetua INSERT num ÚNICO `withTransaction`. Garante chave física diferente da chave lógica já registrada. Sem fallback RAM, sem PUT URL, sem mudança de UPLOADED/ATTACHED/claims. A função randomUUID injetável é EXCLUSIVA de testes, jamais de requisição HTTP.
3. Novo **`backend/test/r2ImmutableAttemptAllocator.test.js`**: nove testes unitários de alocação, chave nova, geração incremental, owner, estados, id inválido, collision com chave lógica, limite 5, SQL fail-closed e rollback. **Não executados pelo ChatGPT**.

## Limitações CRÍTICAS ainda bloqueantes
- **Etapa 30X-IMPL implementa SOMENTE a camada de registro/alocação.** Nenhuma rota HTTP importa este código. `r2StagedPutIssuance.js` ainda consulta somente `message_r2_objects`; `r2StagedPhysicalConfirm.js`, `r2UploadLeaseRegistry.js`, `r2MediaRegistry.js`, ledger PUT e GC ainda usam a chave lógica antiga. **A integração da escolha de uma tentativa VERIFICADA e ligação atômica à mensagem ainda precisa ser projetada e homologada.** Nunca presumir compatibilidade até atualizar TODOS os consumidores de objectKey e as transições.
- A tabela antiga tem `message_id UNIQUE`. A migração 018 propositalmente NÃO remove essa restrição: usa relação pai-lógica + várias tentativas, evitando duplicar mensagens E2EE, mas a migração de metadados no cutover precisará determinar qual chave física é a escolhida sem permitir rebinding pós-verificação.
- O PUT `If-None-Match: *` previne sobrescrita de objeto existente, mas **não bloqueia gravação após o objeto ter sido deletado**. Uma URL ainda válida ou um PUT em voo não pode ter permissão de recriar a mesma chave após remoção. Não ativar GC físico até comprovar fencing de PUT tardio.
- Bucket Locks são opcionais e exigem avaliação oficial de compatibilidade com uploads/GC, prefixos e retenção. Nenhuma política foi aplicada; nenhum teste físico R2 foi realizado. Locks longos no prefixo pending/ podem impedir GC de órfãos.
- A regra `UNIQUE(message_id,generation)` e a row lock no pai evitam concorrência entre alocações via helper; **testar efeito de INSERT fora do helper e permissões do papel backend**. Nenhuma reserva nova pode surgir após finalização, revogação, lease expirado ou quota excedida; o helper exige reserva lógica, mas ainda não cobra quota distinta por retry. Limite 5 apenas preparatório, sujeito à auditoria contra egress/DoS.
- Geração física 1–5 convive com `upload_generation=1` no modelo lógico; são domínios distintos. Não reinterpretar geração física como geração lógica sem evolução explícita do protocolo ECDSA-P256, signed descriptor e operações R2_CONFIRM/ATTACH.
- Sem S3 SDK ativo, migrações 004–018 não aplicadas ao Supabase produção, sem alteração de Render/Android/Gradle/logos/Play. Nada novo ativado em `server.js`.

## Antigravity — HOMOLOGAÇÃO 30X-IMPL (apenas diagnóstico)
Confirmar HEAD `main`, backup checkpoint, worktree isolada. Usar PostgreSQL 18.1 descartável local `127.0.0.1:54333`, aplicar migrações **004–018** exclusivamente no banco de testes (incluindo 018), sem tocar produção.
Executar:
```sh
node --test backend/test/r2ImmutableAttemptAllocator.test.js
node --test backend/test/r2CreateOnlyPut.test.js
node --test backend/test/r2PutRetirementFence.test.js
node --test backend/test/r2StagedPutIssuance.test.js
node --test backend/test/r2StagedPhysicalConfirm.test.js
node --test backend/test/r2ObjectIntegrity.test.js
node --test backend/test/r2UploadLeaseRegistry.test.js
node backend/run-tests.js
node --test backend/test/r2ReadAdmission.postgres.test.js
```
Exigir **208/208** baseline, **9/9** testes novos, todos os específicos aprovados, 0 falhas/0 skips. Com PostgreSQL real:
- Validar RLS, permissões backend, CHECKs, trigger de imutabilidade/DELETE, FK e UNIQUE de chave e geração, namespace UUID e rejeição de path traversal.
- Criar reserva lógica assinada R2_RESERVE com ECDSA P-256 via fluxo existente; alocar tentativas independentes 1,2,3,4,5 dentro de transações distintas: objetos distintos; 6ª rejeitada sem INSERT; nenhuma URL PUT, GET, mudança UPLOADED ou ACK.
- Concorrência 2,10,50 workers para uma só mensagem: jamais duas tentativas com a mesma geração; resultado máximo 5. Falhas COMMIT, downtime, rollback, identidade falsa, lease expirado, state ATTACHED e mensagem sem ligação devem ser fail closed.
- Testar inclusão simultânea de tentativa física que reutiliza chave do pai, INSERT/UPDATE/DELETE SQL malicioso, owner spoof com role backend, índices, deadlocks, lock timeout, leak de recursos.
- Auditar todos os consumidores antigos (PUT, CONFIRM, ATTACH, READ, GC, deletion, outboxes, streaming, replay de prova): identificar EXACTAMENTE onde separar `logicalMessageId` e `physicalAttemptKey` e como vincular a tentativa escolhida sem rebinding. Reportar lacunas sem modificar código.
- Simular in-flight PUT após URL expirar; tentativa abandonada escreve depois, mas em chave distinta da escolhida; verificar que não altera checksum da chave vencedora; testar exclusão e eventual reuso da chave (GC) para expor risco residual.
- Verificar composição final com `If-None-Match: *` sem declarar TOCTOU resolvido por mock. Mapear Bucket Lock e custo/GC sem acesso real à Cloudflare.

Relatório `PESSOASAQUI_ETAPA30X_IMPL_AUDITORIA_TENTATIVAS_IMUTAVEIS_R2.md`.
Antigravity SOMENTE diagnostica e testa; NÃO corrige/comita/push, NÃO aplica migração produção, NÃO altera Android, Render, Supabase, Cloudflare, endpoints nem workers.

**STATUS: 30X diagnóstico homologado; 30X-IMPL somente camada aditiva implementada, NÃO HOMOLOGADA. CUTOVER R2 BLOQUEADO.**
