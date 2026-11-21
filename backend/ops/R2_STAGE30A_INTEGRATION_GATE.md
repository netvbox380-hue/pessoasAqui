# PESSOASAQUI — ETAPA 30A: INTEGRAÇÃO PREPARATÓRIA DE R2 (STAGED, NÃO HOMOLOGADA)

## Base auditada
Antigravity reportou Etapa 29 homologada como **STAGED** no HEAD `6ebe4eee63e27aa09ce2e125407e9e8a351e3b3e`, PostgreSQL 18.1 isolado 54333, migrações 004–013, 8/8 delete admission, 8/8 PUT fence, 9/9 create-only PUT, 8/8 gateway, 9/9 registry, 9/9 revocation outbox, 2/2 PostgreSQL sem skipped, 208/208 backend e 10 cenários reais. Restauro `backup/main-pre-r2-delivery-20261114` SHA `02b6a487ac8b3ad6c64adf6ce8f6887dbfb53bf9`. Main verificada no SHA Etapa29 antes destas alterações.

**Cuidado:** os testes da Etapa 29 mostram elegibilidade transacional de DB; não demonstram GC real. Alegações de uma "hierarquia global" de locks não podem ser aceitas sem inspecionar os caminhos reais: `r2DeliveryFinalizer` bloqueia mídia antes de mensagem, e `r2DeletionAdmission` faz media → outbox → receipt → PUT ledger. Todas as transações concorrentes devem ser auditadas com a ordem efetiva.

## Implementação 30A

1. `backend/src/r2DeliveryFinalizer.js`: `confirmR2Delivery` e `expireR2Message` passam a executar `sealPutKeyForRetirement({tx, objectKey})` depois de mudar estado para `DELETE_QUEUED` e ANTES da inserção na outbox, tudo na **MESMA transação**. Uma janela de PUT ausente ou falha SQL causa exceção e obriga rollback pelo `withTransaction` real. Não permitir que transações externas façam commit após exceção.
2. `backend/test/r2DeliveryFinalizer.test.js`: mocks ajustados para verificar seal antes da outbox, erro de seal e key legada não listada. Novo `backend/test/r2DeliveryExpiryPutSeal.test.js`: caminhos de expiração e falha no seal.
3. `backend/src/r2StagedDeliveryRuntime.js`: composição **inerte**, criada apenas quando explicitamente chamada. Integra localmente registry (23/26), listener (25), reconcile (26) e gateway (27) com callbacks injetados `connectDedicated` e `withTransaction`. `start()` exige ação explícita; `stop()` cancela downloads. Sem importar pelo `server.js`, sem conexão PostgreSQL real, sem routes R2, sem SDK/R2, sem GC. `runtime.ready` serve como gate para futuro handler.
4. `backend/test/r2StagedDeliveryRuntime.test.js`: checks em mocks de ausência de efeitos no import, início explícito, zero consultas com registry vazio e fail closed em desconexão.

## Bloqueadores importantes antes de ativar R2

- O novo seal da Etapa 30A pode revelar inconsistência de dados legados. Não pode ser removido para "passar teste". Antes de rollout, garantir ledger 013 somente para chaves recém-reservadas.
- A gestão transacional completa `recordCommittedRevocation` ainda NÃO foi integrada às rotas /api/connections/unmark ou cancelamento de mensagens. Deve estar na mesma transação que modifica estados, com provas P-256. Verificar isolamento SQLite/PostgreSQL para não quebrar a API atual.
- O worker DELETE com `SKIP LOCKED`, claim com lease/fencing e finalização pós-I/O continua pendente. `admitQueuedR2Deletion` NÃO é permissão de executar DeleteObject sozinho. Não efetuar nenhuma exclusão física.
- O registry/listener ainda não foram conectados ao `server.js` (deliberadamente). O código não recebeu autorização de deploy.
- Autenticação P-256 e admissão devem resultar de validação backend; objeto `admission` nunca é permitido em JSON Android. GET em R2 precisa de adapter com timeout e cancellation de rede; download para Android precisa validação final.
- Audit and simulate deadlocks: seal no finalizador segura lock em media + message + receipt + ledger; PUT issuance segura ledger. Proibir fluxos que obtenham ledger e depois aguardem media locked se um outro fluxo fizer media → ledger. Checar FKs/triggers.
- Mensagens CANCELLED, órfãos e chaves pré-migração 013 exigem tratamento específico. Quarentena temporária NÃO basta para provar inexistência de URL e não permite apagar chaves desconhecidas automaticamente.
- A ordem de autorização de revogação ainda precisa de estratégias fail-closed e fencing em cada instância. Não afirmar que 30A é implantação completa de backend.
- O gateway pode emitir bytes antes do hash completo: Android só confirma ACK após bytes completos, verificação SHA, descriptografia autenticada e persistência local.
- O upload assinado deve ser persistido no ledger antes de devolver URL, e o emissor de URL deve ser único e controlado.
- Evitar egress excessivo: limitar dispositivos, conexões, tamanho de arquivos, logs e tentativas.
- A versão Android publicada permanece sem alterações, sem Play Store update.

## Antigravity — auditoria 30A antes de qualquer próxima correção

Confirmar SHA novo main e checkpoint, worktree isolada (preservar `app/build.gradle.kts`).
Testar:
```sh
node --test backend/test/r2DeliveryFinalizer.test.js
node --test backend/test/r2DeliveryExpiryPutSeal.test.js
node --test backend/test/r2StagedDeliveryRuntime.test.js
node --test backend/test/r2DeletionAdmission.test.js
node --test backend/test/r2PutRetirementFence.test.js
node --test backend/test/r2RevocationListener.test.js
node --test backend/test/r2ActiveStreamReconciliation.test.js
node --test backend/test/r2StreamingGateway.test.js
node backend/run-tests.js
```
Executar também `r2ReadAdmission.postgres.test.js` PostgreSQL 18.1 isolado `127.0.0.1:54333` com zero skips e migrações 004–013. Execução separada de integração PostgreSQL real: ACK e expiração selam ledger na mesma transação, ausência de ledger provoca rollback completo, concorrência de PUT vs seal, lock wait/deadlock, reemissão após seal é negada, zero jobs de DELETE enfileirados em falha, reconciliação do listener só passa a ready quando checks terminam. Não usar backend de produção.

Avaliar os testes legados que chamam `expireR2Message` e possíveis mocks que precisam refletir a dependência de ledger. Checar schema exato das tabelas e triggers.

Gerar relatório `PESSOASAQUI_ETAPA30A_AUDITORIA_INTEGRACAO_R2_STAGED.md`, distingindo mocks e PG real. Antigravity apenas diagnostica, **não corrige, não commita, não faz deploy, não altera Android nem chama R2/Render/Supabase produção**.

**STATUS: ETAPA 30A IMPLEMENTADA, NÃO HOMOLOGADA; ETAPA 30 INCOMPLETA; R2/GC SEM ROLLOUT.**
