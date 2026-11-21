# PESSOASAQUI — ETAPA 29: ADMISSÃO SEGURA DE LIMPEZA R2 (STAGED, SEM GC)

## Base auditada

Antigravity homologou Etapa 28 como **preparatória** no HEAD `553a50ff0c7385e23ea519964180cc2403b482f5`, PostgreSQL 18 isolado em `127.0.0.1:54333`, migrações 004–013. Testes: 8/8 fence PUT, 9/9 create-only PUT, 8/8 gateway, 9/9 registry, 9/9 listener, 2/2 PostgreSQL, 208/208 backend. Cenários de concorrência e bloqueio do PUT tardio descritos no relatório. A auditoria NÃO efetuou PUT/DELETE no R2 real; a alegação de HTTP 403 pelo provedor **não é empiricamente comprovada** pelo banco isolado.

Ponto de restauração conferido: `backup/main-pre-r2-delivery-20261114` (SHA `02b6a487ac8b3ad6c64adf6ce8f6887dbfb53bf9`). Baseline `main` conferida no SHA `553a50f` antes dos commits desta etapa.

## Implementação isolada

- `backend/src/r2DeletionAdmission.js`: `admitQueuedR2Deletion({withTransaction,objectKey,messageId})`. Exige transação PostgreSQL real e confere, com lock na mesma transação:
  1. `message_r2_objects`: vínculo exato `object_key/message_id` e estado `DELETE_QUEUED`.
  2. `message_r2_delete_outbox`: job existente para a mesma chave e mensagem.
  3. `message_delivery_receipts`: recibo terminal `DELIVERED` ou `EXPIRED`. Outros estados, inclusive `CANCELLED`, ficam bloqueados até modelagem específica.
  4. `authorizePhysicalR2Delete` Etapa 28: tombstone selada, última URL PUT expirada e margem de segurança.
- Nenhum client S3, timer, worker, job, delete R2, endpoint público, ou alteração em `server.js`. A função retorna **elegibilidade de banco no instante da transação**, não uma autorização de apagar realmente o arquivo. **Não executar DeleteObject com base apenas nesse retorno**.
- `backend/test/r2DeletionAdmission.test.js`: oito testes unitários com tx injetada, todos precisam de execução pelo Antigravity.

## Pendências deliberadas — ROLLOUT BLOQUEADO

- Etapa 28 criou `sealPutKeyForRetirement`, mas `confirmR2Delivery()` e `expireR2Message()` **ainda não foram modificados** para fazer seal na mesma transação da marcação `DELETE_QUEUED` e enfileiramento. Antes da ativação, será obrigatório incorporar o seal com a ordem de locks compatível com emissão concorrente de PUT, testar com DB 18 real e preservar a regressão. O campo pode ficar `NULL` nos dados de teste e a admissão recusará exclusão como esperado.
- CANCELLED e limpeza de órfãos precisam de protocolo próprio com prova de terminalidade e proibição de novo upload; jamais liberar por ausência de recibo.
- URLs emitidas antes da migração 013 NÃO estão rastreadas. Quarentena de 17 minutos **sozinha** não autoriza excluí-las no gate atual, porque não prova limite de TTL, última emissão nem bloqueio de reemissão. Exigir política explícita de corte e inventário/auditoria antes de qualquer migração. Desconhecidos permanecem bloqueados.
- É necessário worker durável com claim de outbox, `SKIP LOCKED`, lease/fencing, retry/backoff, idempotência, timeout, auditoria de sucesso/falha e finalização pós-I/O. Não manter locks PostgreSQL durante chamada R2. A função desta etapa NÃO implementa esses mecanismos.
- Toda remoção real deve garantir que o ciphertext foi entregue/persistido com ACK autenticado, ou que expirou no prazo de 48h, e que o objeto está marcado `DELETE_QUEUED`. O simples término do HTTP stream nunca é ACK.
- Preservar tombstones de keys de upload; eventual retenção de recibos (30 dias) e avisos de expiração (90 dias) depende das regras existentes e auditoria de privacidade. Não confundir estes prazos com retenção de ciphertext R2.
- A corrente cadeia `recordCommittedRevocation`, listener e gateway de streaming seguem NÃO integrados no `server.js`. R2 e GC desativados.

## Antigravity — auditoria da Etapa 29

1. Conferir `origin/main` e ponto de restauração. Usar worktree isolada, preservar alterações locais em `app/build.gradle.kts`, Antigravity apenas diagnostica.
2. Executar:
```sh
node --test backend/test/r2DeletionAdmission.test.js
node --test backend/test/r2PutRetirementFence.test.js
node --test backend/test/r2CreateOnlyPut.test.js
node --test backend/test/r2StreamingGateway.test.js
node --test backend/test/r2ActiveStreamRegistry.test.js
node --test backend/test/r2RevocationOutbox.test.js
node backend/run-tests.js
```
3. Executar `node --test backend/test/r2ReadAdmission.postgres.test.js` com `PESSOASAQUI_TEST_PG_URL` para PostgreSQL 18 isolado `127.0.0.1:54333`, **zero skips**; migrações 004–013.
4. Executar integração extra em PostgreSQL real com recibo terminal, `DELETE_QUEUED`, job outbox e tombstone selada com prazo expirada. Validar bloqueios: recibo ausente, objeto `ATTACHED`, job ausente, assinatura PUT ainda válida, ledger desconhecida, CANCELLED, rollback e concorrência com emissão/selagem.
5. Auditar especificamente esquema exato do outbox, índices, FK e sequência de locks do worker futuro; apontar deadlocks/TOCTOU, cargas de consultas, níveis de isolamento e necessidade de job claim atômico antes de R2 DELETE.
6. Investigar onde inserir `sealPutKeyForRetirement` em `confirmR2Delivery`, `expireR2Message` e caminhos de cancelamento SEM alterar esses métodos, testando que tombstone seja selada antes do job ser enfileirado na MESMA transação.
7. Verificar que R2 não sofreu DELETE/PUT e nenhum deploy/SQL de produção foi executado. Não tratar autorização DB-only como worker real.
8. Entregar `PESSOASAQUI_ETAPA29_AUDITORIA_ADMISSAO_GC_R2.md`: resultados mock/PG real separados, riscos residuais para Etapa 30.

**STATUS: Etapa 29 implementada para auditoria do gate de exclusão, NÃO homologada, sem GC.**
