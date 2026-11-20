# PESSOASAQUI — ETAPA 26: Reconciliação autoritativa de streams R2 (STAGED, SEM ROLLOUT)

## Base aprovada

Antigravity homologou a Etapa 25 no commit `8e2ad0df6938deba2338d85bc004c462dc082fa0`: 9/9 testes do listener, 9/9 outbox, 8/8 registro, 8/8 preflight, 2/2 PostgreSQL 18 real (zero skips), 208/208 backend, 14 cenários listener inclusive duas sessões de PG 18 isoladas. **A homologação é somente preparatória**, sem streaming real, sem publicação de eventos das rotas existentes. O checkpoint `backup/main-pre-r2-delivery-20261114` foi verificado antes de avançar.

## Implementação de Etapa 26

1. `backend/src/r2ActiveStreamRegistry.js` agora oferece `abortAll()`, `listActive()` (snapshot congelado sem sockets/AbortController) e `cancelToken()` (identificador opaco interno, exclusivo do processo). Cada registro armazena opcionalmente objectKey + SHA-256 + bytes do ciphertext oriundos da admissão no backend, para futura reconciliação. **Registros antigos sem esses dados são encerrados pela reconciliação, sem fallback permissivo.**
2. `backend/src/r2ActiveStreamReconciliation.js`: `reconcileR2ActiveStreams({registry,withTransaction})` inspeciona até 1024 referências ativas e executa `revalidateR2ReadAtStreamStart` para cada stream em transação PostgreSQL curta. Conferem-se ambas direções da conexão, vínculo exato de mensagem e objectKey, estado `ATTACHED/PENDING`, expiração, cancelamento, recibo terminal, SHA-256 e quantidade de bytes. Em qualquer dúvida, referência incompleta ou erro PostgreSQL: **abortAll()** e propagar erro para o listener não voltar a `ready=true`.
3. `backend/test/r2ActiveStreamReconciliation.test.js`: oito novos testes unitários para revalidação válida, revogação, cancelamento, expiração, terminal, alterações de descritores, falha de banco, snapshot completo, idempotência e operação com registry vazio. **Não executados pelo ChatGPT; Antigravity deve homologar.**

## LIMITES — AINDA NÃO CONECTADO AO SERVIDOR

- Etapa 26 fornece rotina `reconcile` para injeção futura no listener Etapa 25, mas **não chama `start()` e não modifica server.js**. A conexão `pg.Client` dedicada da Etapa 25 ainda exige bootstrap seguro. O ciclo de vida real e o vínculo exato com sockets HTTP não foram verificados.
- `abortAll` só encerra streams **deste processo**. A futura estratégia de falha deverá fornecê-lo como callback síncrono `failClosed` e impedir registro de novas sessões enquanto `listener.ready!==true`.
- **Não foi ligado `recordCommittedRevocation` às rotas reais de desvínculo bilateral / cancelamento de mensagens**. Fazer isso sem revisão do fluxo transacional e sem testes isolados seria arriscar mudanças no serviço já publicado. É requisito para Etapas 27–30, ainda bloqueado.
- O objeto `admission` fabricado dentro do reconciliador é montado **apenas** a partir de um snapshot privado do servidor, que deverá ter sido criado por admissão criptográfica P-256 validada. Não aceitar referências nem admissão via JSON do Android. A implementação ainda depende desse contrato de integração.
- Snapshot não é fencing: há janela entre capturar sessões, consultar PostgreSQL e iniciar bytes. O gateway futuro deve registrar stream **antes** do último preflight, revalidar depois do registro e impedir escrita se `listener.ready!==true`, inclusive após awaits. Evitar transação SQL segurando rede. Não prometer revogação instantânea/retroativa.
- Listar todos os streams e emitir uma consulta por stream é adequado apenas em boot/reconexão/erro **com até 64 streams no máximo por processo**, não em polling contínuo. Este limite não substitui quota global ou validação de egress.
- Ainda não é realizado cancelamento persistente no cliente Android após socket interrompido. Mensagens não devem ter recibo `DELIVERED` antes de persistência íntegra.
- Outbox `LISTEN/NOTIFY` não substitui estado autoritativo. Se reconciliador falha, todos os downloads locais devem ser abortados antes de novos bytes.
- Não executar migration 012 em produção, não ativar gateway, R2, GC, Render nem Play Store sem autorização explícita.

## Homologação obrigatória do Antigravity

- Fazer `git fetch origin main`; confirmar HEAD correto, `backup/main-pre-r2-delivery-20261114`, criar worktree separada, preservar `app/build.gradle.kts`.
- Executar:
```sh
node --test backend/test/r2ActiveStreamReconciliation.test.js
node --test backend/test/r2ActiveStreamRegistry.test.js
node --test backend/test/r2RevocationListener.test.js
node --test backend/test/r2RevocationOutbox.test.js
node --test backend/test/r2StreamStartGate.test.js
node --test backend/test/r2ReadAdmission.test.js
node backend/run-tests.js
```
- `PESSOASAQUI_TEST_PG_URL=... node --test backend/test/r2ReadAdmission.postgres.test.js` com PostgreSQL 18 isolado `127.0.0.1:54333`, **zero skips**, migrações 004–012.
- **Integração real**: criar streams locais com referências COMPLETAS derivadas de admissão P-256 e registrar em registry. Alterar estado no PostgreSQL real antes de `reconcileR2ActiveStreams`: revogar conexão A/B, cancelar e expirar mensagens, terminar recibo, remover associação, alterar hash via teste apropriado de integridade (respeitar triggers), desativar conexão banco e verificar `abortAll` sem vazamento. Verificar concorrência de revogação durante SELECT, lock `FOR SHARE`, sessão encerrada durante snapshot e reconciliação sem streams (zero SELECT).
- Examinar se novas sessões podem escapar entre snapshot e `listener.ready`, comportamento se `abortAll` ou callback de fail-closed falhar, crescimento de listeners, cota de conexões PG e impacto de egress. Classificar estes riscos como pendentes de integração, e **não** como homologados por mocks.
- Diagnosticar (sem editar) caminhos reais em backend para `unmark`, cancelamento de mensagem e marcação de entrega, com campos, transações e pontos adequados à futura outbox. Não realizar sua alteração nesta etapa.
- **Antigravity não altera código, não commita, não faz deploy, não toca Render/Supabase/Cloudflare/Android/Play Store e não habilita GC.**

**Status: Etapa 26 PREPARADA para homologação, mas ainda NÃO HOMOLOGADA. Rollout R2 desativado.**
