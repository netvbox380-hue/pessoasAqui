# PESSOASAQUI — ETAPA 27: Gateway de streaming E2EE do backend (STAGED / INERTE)

## Base homologada

Antigravity relatou **Etapa 26 homologada como preparatória** no SHA `3f74c1041e73bf5834d1472dd7c7856f5e0b8374`, worktree isolada PostgreSQL 18 na porta 54333 (migrações 004–012): 8/8 reconciliador, 8/8 registry, 9/9 listener, 9/9 outbox, 8/8 preflight, 8/8 admissão, 2/2 PostgreSQL real sem skips, 208/208 regressão e 14 cenários funcionais. O ponto de restauração `backup/main-pre-r2-delivery-20261114` permaneceu disponível.

## Implementação da Etapa 27

- Novo `backend/src/r2StreamingGateway.js`, `streamAuthenticatedR2Ciphertext`. **Sem importações por server.js, sem rota HTTP, sem cliente S3 e sem credenciais:** requer injeção de objetos de confiança em ambiente de teste.
- Ordem: conferir resultado de admissão R2_READ **servidor** (Etapa 21) + listener.ready; registrar a transferência no registry (Etapas 23/26) **ANTES** do preflight SQL (Etapa 22); bloquear quando conexão/mensagem/objeto foram revogados; checar listener/handle novamente antes de abrir R2 e antes de cada escrita; enviar somente ciphertext via resposta HTTP injetada.
- `openCiphertext({objectKey,expectedBytes,abortSignal})` precisa ser implementado futuramente pelo adapter R2 com `client.send(GetObjectCommand, {abortSignal})` e timeout; o gateway não fornece URLs públicas nem chaves E2EE.
- Mantém limite absoluto de **25 MiB**, valida ContentLength, comprimento acumulado e SHA-256 do ciphertext; cancela upstream/downstream em revogação, erro ou timeout global configurado até 120 segundos. O código em si aguarda o SDK/stream e não ativa conexões reais.
- Novo `backend/test/r2StreamingGateway.test.js`: oito testes em Node.js com streams injetados: sucesso, admissão/ready, revogação no banco, mensagem cancelada, revogação durante abertura, comprimento divergente, hash divergente, falha PostgreSQL. **Não executados por ChatGPT**.

## AVISOS DE SEGURANÇA FUNDAMENTAIS

1. **O gateway ainda NÃO está ligado ao listener da Etapa 25, ao reconciliador da Etapa 26, nem ao servidor real.** Nenhum `start()` automático foi inserido e nenhuma rota foi registrada. `recordCommittedRevocation` continua não integrado às rotas reais de cancelamento e desvínculo. A revogação distribuída não pode ser considerada operacional.
2. O parâmetro `admission` deve ser a saída produzida internamente por `admitAuthenticatedR2Read` na MESMA requisição autenticada; `listener`, `registry` e callbacks SQL também devem ser componentes backend privados. As validações de formato do gateway **NÃO fazem prova ECDSA**. Nunca aceitar `admission` fornecida pelo Android.
3. O SHA-256 do ciphertext **só é conhecido ao chegar ao último byte**. Em streaming, bytes possivelmente corrompidos podem ter sido transmitidos antes de detectar o erro. O backend derruba o socket e **o Android TEM que guardar em arquivo temporário, conferir tamanho/hash, decifrar/autenticar localmente e só depois promover a mídia e confirmar entrega**. Sem esta integração Android, nenhum rollout.
4. Um `AbortController.abort()` não retira bytes já entregues pela rede. Revogação não é retroativa nem necessariamente instantânea em todos os nós.
5. O registro antes do preflight e a verificação síncrona pré-escrita reduzem a janela TOCTOU, mas não resolvem o cenário de notificações atrasadas/perdidas após COMMIT. Os eventos de revogação precisam ser emitidos na mesma transação autoritativa e recebidos por cada instância. Reavaliar fence de geração por conexão e autorização no protocolo final.
6. O futuro handler HTTP deve tratar erro **antes de emitir cabeçalhos**. O registry atualmente `destroy()` a resposta na revogação, inclusive durante o preflight, portanto pode não ser possível devolver JSON HTTP com erro. Garantir contrato do cliente: conexão abortada equivale a download incompleto, não a entrega, e nenhuma tentativa parcial gera ACK. Não tentar escrever após destroy.
7. `openCiphertext` deve obedecer ao AbortSignal e destruir corpos de respostas tardias. O SDK/R2 real, limites de socket, pedidos Range, custos de Egress, detecção de desconexão Android, controle global de bytes/concurrency, event notifications, replay e callbacks de falha ainda exigem testes isolados/revisão.
8. DELETE→PUT com URLs antigas de upload e coleta automática de lixo continuam bloqueados; não ativar GC.
9. `timeoutMs` mede desde o registro até a transferência, mas o encerramento depende de adapter R2 e do AbortSignal. Uma dependência que ignora cancelamento precisa de deadline independente (`Promise.race`) e destruição de corpo tardio antes de produção.
10. A implementação atual usa `crypto.createHash` ao longo do fluxo e não armazena payloads completos em RAM, mas pode transmitir bytes cuja integridade só será confirmada no final; o cliente não deve tratar buffers parciais como mídia utilizável.

## Diagnóstico obrigatório Antigravity (sem correções)

Confirmar `main` HEAD e checkpoint; usar worktree separada e preservar `app/build.gradle.kts`.

Executar:
```sh
node --test backend/test/r2StreamingGateway.test.js
node --test backend/test/r2ActiveStreamReconciliation.test.js
node --test backend/test/r2ActiveStreamRegistry.test.js
node --test backend/test/r2RevocationListener.test.js
node --test backend/test/r2RevocationOutbox.test.js
node --test backend/test/r2StreamStartGate.test.js
node --test backend/test/r2ReadAdmission.test.js
node backend/run-tests.js
```
Executar teste `r2ReadAdmission.postgres.test.js` com `PESSOASAQUI_TEST_PG_URL` no PostgreSQL 18 local `127.0.0.1:54333` e **zero skipped**.

### Cenários obrigatórios
- Fluxo de ciphertext real em memória e limites 25 MiB; conexão bilateral e objeto associados; cancelamento antes do primeiro byte, durante espera por I/O e durante transmissão com backpressure; hash adulterado ou tamanho errôneo; cliente desconectado; tempo limite; memória, listeners e socket leak.
- Testar comportamento com `openCiphertext` que **ignora** `AbortSignal`, com stream travado que nunca fecha, com `response.write()` retornando false e listener `ready` oscilando. Documentar travamentos e TOCTOU residuais mesmo com testes automáticos aprovados.
- PostgreSQL real isolado: revogação concorrente com preflight, emissão de NOTIFY com outbox e simulação de duas instâncias, atraso no despacho, erro de banco, ausência de comunicação com R2 real. Não ativar migrations em produção.
- Auditar especificamente como acoplar `recordCommittedRevocation` às transações autoritativas das rotas `/api/connections/unmark` e cancelamento de mensagens, **sem editá-las ainda**.
- Demonstrar que SHA-256 concluído no backend NÃO equivale à integridade/persistência local comprovada no Android. Mensagem não se torna DELIVERED só porque `response.end()` ocorreu.
- Diferenciar testes de mocks, PostgreSQL isolado e testes não executados no R2 real. Nenhuma homologação geral de produção.

**Antigravity não altera código, não commita, não faz deploy, não acessa produção e não modifica Android.**

**Status: ETAPA 27 IMPLEMENTADA PARA AUDITORIA, AINDA NÃO HOMOLOGADA. R2 de produção rigorosamente desativado.**
