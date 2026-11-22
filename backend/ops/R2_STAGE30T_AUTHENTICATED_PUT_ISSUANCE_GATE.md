# PESSOASAQUI — ETAPA 30T: EMISSÃO PREPARATÓRIA DE PUT CREATE-ONLY COM PROVA P-256 E FENCE DURÁVEL

## Baseline
Antigravity relatou homologação integral de 30S em `03583d7c567ff0710e872c92c52618c67e7892f7`: 208/208 regressão, 63/63 testes específicos, 13/13 testes connectionRevocation, 3/3 VM, 20/20 cenários PG18.1 real + Express isolado, zero skips. Quota de 24 novas reservas por identidade/dia UTC (migração 017) aprovada apenas em testes; **não aplicada em Supabase produção**. Antes das alterações 30T, main idêntica à baseline. Backup `backup/main-pre-r2-delivery-20261114` preservado em `02b6a487ac8b3ad6c64adf6ce8f6887dbfb53bf9`.

## Implementação 30T — staged, sem rota
- `backend/src/r2StagedPutIssuance.js`: novo `issueAuthenticatedR2Put({withTransaction,identity,peerIdentity,messageId,objectKey,proof,bucket,signPutUrl,now})`. Recebe callback de assinatura SigV4 confiável **injetado exclusivamente no backend** e transação real PostgreSQL, sem SDK, credenciais, rede, env ou `server.js` no import.
- Reutiliza **prova nova R2_RESERVE** assinada em ECDSA P-256 com novo nonce para a etapa de emissão, vinculando par, mensagem e objeto. **A reutilização de R2_RESERVE para PUT issuance é solução de composição PREPARATÓRIA; exige revisão de separação de operações criptográficas antes de produção, especialmente no Android.** Não aceita nonce antigo.
- No mesmo `withTransaction`: valida chave P-256 ativa da identidade, vínculo bilateral com FOR SHARE, insere nonce, lê `message_r2_objects` com `FOR UPDATE`, exige estado RESERVED, upload_generation=1, lease SQL ativo, dono/destinatário corretos; então chama o `issuePutWithDurableRetirementFence` pré-existente (Etapa 28), usando wrapper que executa seu callback no **MESMO tx** (sem abrir nova transação). O fence confere chave não selada, assinatura local sem I/O, TLS URL canônica, headers `If-None-Match: *` e `Content-Type: application/octet-stream` assinados, validade <=900s, expiração alinhada ao clock PostgreSQL e registro monotônico no ledger 013 antes do COMMIT. A capacidade só sai da função externa após `withTransaction` retornar, ou seja, após COMMIT num runner correto.
- Nega chave sem reserva, vínculo unilateral, lease expirado, owner mismatch, nonce reutilizado, documento inexistente e chave selada. Falhas de assinatura, schema ou SQL propagam e devem causar rollback; **não capturar nem registrar URL bearer**.
- Novo `backend/test/r2StagedPutIssuance.test.js`: 9 testes unitários, mocks de SQL e assinatura P-256 real; **não executados pelo ChatGPT**.

## Riscos bloqueadores e precauções
1. **Esta etapa não registra rota HTTP e não entrega URL a Android em produção.** Não há presigner AWS SDK v3/Smithy real, nem client GET/PUT real, nem credenciais configuradas; toda assinatura nos testes é fake, sem autenticidade SigV4 física.
2. A operação assina como `R2_RESERVE` porque o contrato imutável existente suporta RESERVE/CONFIRM/ATTACH/READ. Um novo domínio `R2_ISSUE_PUT`, separado de RESERVE, exigiria nova versão de assinatura Android; decidir em auditoria antes de publicar, sem aproveitar uma assinatura de reserva para emitir PUT por acidente.
3. O `r2PutRetirementFence` pode rejeitar a assinatura se prazo/data/endereço/headers não corresponderem. A URL tem natureza bearer e não deve ser registrada, cacheada, compartilhada ou incluída em logs. O POST de autorização HTTP com proof ainda **não foi criado**.
4. O teste SQL mock não comprova COMMIT/ROLLBACK. Testar `issueAuthenticatedR2Put` numa transação real PG18.1 com 013 e 017, travamento, falha no INSERT do ledger, exigência de rollback de nonce e isolamento de concorrência, inclusive duas instâncias emitindo simultaneamente a mesma chave e cancelamento do par.
5. A janela de validade de PUT (<=15min) e o lease de reserva (60min) têm semânticas diferentes. O DELETE físico depende do fence selado e expiração+120s; conferir um PUT tardio após lease, retry e limpeza órfã. Não assumir que o fim do lease revoga URL já emitida.
6. A origem da chave opaca ainda não foi consolidada em namespace temporal estrito. Quota de RESERVE não limita tentativas HTTP inválidas/duplicadas, nem bytes, nem custo agregado. Rate limit e quotas de storage continuam pendentes.
7. Emissão de URL não comprova upload: ainda faltam R2_CONFIRM com GET de verificação SHA256 real e claim/quota, R2_ATTACH com digest E2EE assinado, tratamento órfãos/CANCELLED e integração Android.
8. Migracões 004–017, AWS SDK, flags e endpoints inativos em produção; NÃO ativar Render, Supabase, Cloudflare R2, workers, cron ou Play Store.

## Auditoria obrigatória Antigravity — 30T
Confirmar HEAD main, backup, worktree isolada, PostgreSQL 18.1 local 127.0.0.1:54333, migrações 004–017 apenas no banco de testes; Android/gradle/logos/keystore não alterados. Rodar:
```sh
node --test backend/test/r2StagedPutIssuance.test.js
node --test backend/test/r2CreateOnlyPut.test.js
node --test backend/test/r2PutRetirementFence.test.js
node --test backend/test/r2StagedReserveAdmission.test.js
node --test backend/test/r2StagedReserveHttpHandler.test.js
node --test backend/test/r2ReserveDailyBudget.test.js
node --test backend/test/r2ActionAuth.test.js
node --test backend/test/r2UploadLeaseRegistry.test.js
node backend/run-tests.js
node --test backend/test/r2ReadAdmission.postgres.test.js
```
Exigir 208/208 na geral e todos os testes novos, sem falhas nem skips. Com PG real e signer fake isolado:
- assinar prova P-256 válida, nonce exclusivo, vínculo bilateral e objeto reservado, PUT URL só após COMMIT, ledger 013 persistido;
- sem schema, chave revogada, nonce reutilizado, par unilaterial, lease expirado, objeto ausente/UPLOADED/ORPHAN_QUEUED/ATTACHED ou sealed, nenhum URL;
- falha no COMMIT, falha signer/ledger, rollback do nonce/ledger e ausência de URL;
- dois e dez trabalhadores concorrentes emitindo para mesma chave, nunca contornar locks/monotonicidade do ledger; UNMARK concorrente;
- validar semanticamente reutilização da assinatura RESERVE como operação de PUT: possíveis riscos de privilege confusion e necessidade de nova operação no protocolo Android;
- URL fake somente HTTPS R2 canônico, método PUT, headers assinados, expiry<=900s, proibir vazamento em logs; executor sem credenciais reais;
- não confundir retorno do teste fake com SigV4 legítimo ou PUT físico. Nenhum Cloudflare real ou produção.
Entregar `PESSOASAQUI_ETAPA30T_AUDITORIA_ISSUANCE_PUT_R2.md`.
Antigravity somente diagnostica/testa, não modifica código, commits, flags, Android, Render/Supabase/Cloudflare.

**STATUS: ETAPA 30T IMPLEMENTADA, NÃO HOMOLOGADA. ETAPA 30 INCOMPLETA; R2 SEM CUTOVER.**
