# PESSOASAQUI — ETAPA 30B: Claims duráveis de GC R2 (STAGED / INERTE)

## Baseline

Etapa 30A homologada como **PREPARATÓRIA / INERTE** pelo Antigravity no SHA `d7456203fec944a7c802069794cbafb5fee82e13`. Relatório: `PESSOASAQUI_ETAPA30A_AUDITORIA_INTEGRACAO_R2_STAGED.md`. Testes relatados: 8/8 delivery finalizer, 4/4 expiry seal, 4/4 staged runtime, 8/8 deletion admission, 8/8 PUT fence, 9/9 listener, 8/8 reconciliation, 8/8 streaming, 2/2 PG18 real (zero skips), 208/208 regressão, 12 cenários PostgreSQL. Etapa 30 permanece incompleta.

Restauro: `backup/main-pre-r2-delivery-20261114` = `02b6a487ac8b3ad6c64adf6ce8f6887dbfb53bf9`. Antes de começar, `main` era idêntica a `d7456203fec944a7c802069794cbafb5fee82e13`.

## Implementação 30B

- Migração `backend/migrations/014_r2_delete_claims_PREPARE_ONLY.sql` (NÃO APLICADA): tabela `r2_delete_claims`, claim token UUID, geração monotônica, lease_until, número máximo de tentativas, completed_at, índice de tentativas, RLS e revoke para usuários públicos. Referência persistente `object_key` não deve ser apagada.
- `backend/src/r2DeleteClaimLedger.js` (INERTE):
  - `claimQueuedR2Delete()`: transação curta, media `DELETE_QUEUED` `FOR UPDATE`, outbox correspondente `FOR UPDATE`, receipt `DELIVERED/EXPIRED`, PUT tombstone sealed e prazo vencido (+120s); apenas depois registra/adquire claim persistente com `INSERT ... ON CONFLICT ... WHERE lease_until < clock_timestamp()`. TTL de lease 90s, teto de 20 tentativas, incremento de `generation` para cercar workers antigos. Sem retenção de locks durante I/O.
  - `recordDeleteClaimCompleted()`: registra **somente** evidência interna de sucesso de um worker, exigindo token, geração e lease vigentes; NÃO atualiza `message_r2_objects`, NÃO retira job da outbox e NÃO chama R2. `trustedStorageDeleteCompleted` é contrato interno, **não prova** de resultado e jamais pode vir de JSON cliente.
- `backend/test/r2DeleteClaimLedger.test.js`: dez testes unitários simulados, ainda NÃO executados pelo ChatGPT.

## LIMITES E BLOQUEADORES DE PRODUÇÃO

1. **Não existe worker que chame S3 DeleteObject**, scheduler, start em `server.js`, endpoint público ou deploy. A função claim é somente uma preparação para uma futura execução independente; não autoriza sozinho exclusão física.
2. **Completion não faz a transição terminal `state='DELETED'` nem finaliza outbox**, intencionalmente. Antes de ativar, um protocolo transacional pós-I/O deverá conciliar claims COMPLETED com media/outbox mediante prova de resultado do SDK e proteção de geração, considerando crash após DELETE e antes do COMMIT.
3. **GC sem disparo automático:** claim é por objeto exato, não enumera jobs. O scheduler futuro deve percorrer lotes com índices, `SKIP LOCKED` e controle de egress, sem consultas quentes nem locks abertos durante rede. O módulo 30B ainda não implementa `SKIP LOCKED`.
4. **Expiração de lease durante DELETE** pode permitir que outro worker receba uma nova geração. R2 DeleteObject é idempotente para mesma chave, mas ainda exigir destino immutable, tombstone nunca reemitida, bounded timeout e que completion de geração antiga seja rejeitada.
5. **Caminhos de cancelamento** (ex. receipt CANCELLED) e mídias órfãs precisam política/worker separados; são rejeitados, nunca incluídos por fallback.
6. Chaves legadas sem janela 013 continuam bloqueadas. "Quarentena de 17 minutos" isolada NÃO prova segurança.
7. `recordCommittedRevocation` continua não integrado às transações autoritativas de cancelar mensagem / `/api/connections/unmark`. É obrigatório para streaming multiinstância e precisa ser auditado com assinaturas P-256 existentes. **O relatório do Antigravity sugere Ed25519, mas a cadeia implementada nas Etapas 20–21 é ECDSA P-256; não trocar algoritmo sem análise de compatibilidade.**
8. O streaming, listener e reconcile continuam fora de `server.js`. Rotas de mídia, callbacks e autenticação de Android NÃO foram ativados. Não aplicar migração 014 em Supabase de produção.
9. **Não afirmar que a Etapa 30 está completa.** Este é um incremento 30B, sujeito a auditoria do Antigravity e trabalhos adicionais antes de qualquer rota R2, GC ou Android.

## Antigravity — auditoria obrigatória SEM CORREÇÃO

1. `git fetch origin main`, confirmar o HEAD, backup e criar worktree isolada. Preservar `app/build.gradle.kts` e código Android.
2. Executar:
```sh
node --test backend/test/r2DeleteClaimLedger.test.js
node --test backend/test/r2DeletionAdmission.test.js
node --test backend/test/r2DeliveryFinalizer.test.js
node --test backend/test/r2DeliveryExpiryPutSeal.test.js
node --test backend/test/r2PutRetirementFence.test.js
node --test backend/test/r2StagedDeliveryRuntime.test.js
node --test backend/test/r2RevocationOutbox.test.js
node backend/run-tests.js
```
3. PostgreSQL 18.1 isolado `127.0.0.1:54333`, migrações 004–014 (**aplicar 014 somente localmente**); `r2ReadAdmission.postgres.test.js` com `PESSOASAQUI_TEST_PG_URL`, zero skips.
4. **Testes reais de claims**: INSERT e atribuição de lease; concorrência de 2–10 workers pela mesma key; um e somente um claim; retry só após vencimento; token/generation antigos recusados; completed não reclamável; recusa de outbox/receipt/tombstone inválidos; 20 tentativas; ROLLBACK; falha de PG e deadlocks com ACK/expiry/PUT issuance; verificar constraints, RLS e índices.
5. Verificar que completion não altera `state` ou retira outbox. Auditar esquema e locks reais, risco de claim fora de transação, timeouts e idempotência. Exigir relatório claro de lacunas: ausência de SKIP LOCKED e de worker completo, necessidade de finalize durável pós-I/O.
6. **Não executar DeleteObject ou chamadas ao Cloudflare R2 real, nem executar deploy ou SQL em Render/Supabase produção.** Não modificar qualquer código.

Entregar `PESSOASAQUI_ETAPA30B_AUDITORIA_CLAIMS_GC_R2.md`.

**STATUS: ETAPA 30B IMPLEMENTADA / AGUARDANDO AUDITORIA; ETAPA 30 INCOMPLETA; R2/GC BLOQUEADOS.**
