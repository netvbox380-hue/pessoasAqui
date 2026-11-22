# PESSOASAQUI — ETAPA 30Q: GET R2 CIFRADO COM CANCELAMENTO ATÉ O FIM DO STREAM (PREPARE ONLY)

## Baseline homologada
O Antigravity informou homologação da Etapa 30P no commit `f734afb276573e7849214ccef063634e79415a71`, com 208/208 testes gerais, 41/41 testes específicos R2 reportados, 2/2 PostgreSQL 18.1 e 20/20 cenários Express/PG18 com armazenamento simulado; zero falhas e zero skips. A validação **não é teste de Cloudflare R2 real**. Antes da Etapa 30Q a main estava idêntica à baseline. Checkpoint `backup/main-pre-r2-delivery-20261114` permanece no SHA `02b6a487ac8b3ad6c64adf6ce8f6887dbfb53bf9`.

## Implementação 30Q (continuidade e auditoria aprofundada de GET, sem ativação)
O transporte GET `backend/src/r2StagedGetTransport.js` e o teste `backend/test/r2StagedGetTransport.test.js` foram introduzidos nesta fase após 30P e permanecem **somente preparatórios**. A factory `createStagedR2GetTransport({client,GetObjectCommand,bucket})` prepara `openCiphertext({objectKey,expectedBytes,abortSignal})` compatível com o gateway. Não importa SDK, não instancia cliente, não lê credenciais ou env, não registra rota no `server.js` e não faz I/O em import.

- Valida chave opaca de objeto, bucket de configuração confiável, AbortSignal e tamanho esperado 1–25 MiB.
- Utiliza `fetchCiphertext` com o sinal repassado a `client.send(...,{abortSignal})` e confere `ContentLength`; não transmite stream incompatível.
- Exige corpo Node.js assíncrono e cancelável. Mantém escuta do abort até o `end`/`close`/`error` do stream; o abort destrói o corpo sem repassar exceção potencialmente não observada pela camada do consumidor.
- Gateway já homologado na 30O/30P confere comprimento e SHA-256 no EOF, cancela fluxo após revogação e não emite ACK antes de persistência/validação no Android. O transporte em si **não autentica identidade** e somente pode ser passado ao gateway após R2_READ autorizada no backend.
- Testes novos do transporte: casos de inércia, integração fake-S3, validação de chave, limites, cancelamento pre/send/post/stream, corpo inválido e falhas. **Não executados pelo ChatGPT**.

## Bloqueadores de produção
1. A existência de `r2StagedGetTransport` não implementa cliente real AWS SDK v3 para GET, credenciais, rede, lifecycle ou egress; nada novo registrado no Express. Não configurar R2 de produção.
2. SDK/HTTP físico que ignore AbortSignal pode continuar em segundo plano; verificar transporte efetivo em ambiente descartável com timeout de socket/cliente, destruição de Body tardio, no unhandled error, sem vazamento de listener. O resultado fake é insuficiente para certificar operação física.
3. Não confundir validação de `ContentLength` com integridade: SHA-256 só é conferido no EOF; o Android deverá descartar downloads parciais e não confirmar entrega antes da persistência.
4. Ainda faltam rotas autenticadas R2_RESERVE, R2_CONFIRM e R2_ATTACH, composição real de cliente com GET/PUT e presença de migrações 004–016 no ambiente de rollout, segurança de revogação e orfandade, implantação controlada e homologação Android. Não declarar Etapa 30 completa.
5. Qualquer futura ligação no `server.js` deve permanecer bloqueada por rollout explícito e verificação do banco autoritativo. Nenhum deploy/SQL produção nesta etapa.

## Auditoria Antigravity 30Q (somente testes/diagnóstico)
Confirmar HEAD da main, SHA de restauração, worktree isolada, PostgreSQL 18.1 em 127.0.0.1:54333 (migrações 004–016). Android/gradle/logos preservados. Executar:
```sh
node --test backend/test/r2StagedGetTransport.test.js
node --test backend/test/r2StagedReadHttpHandler.test.js
node --test backend/test/r2StreamingGateway.test.js
node --test backend/test/r2StagedDeliveryRuntime.test.js
node --test backend/test/r2ReadAdmission.postgres.test.js
node --test backend/test/r2ActionAuth.test.js
node backend/run-tests.js
```
Comprovar regressão geral **208/208**. Auditar casos de abort antes do GET, durante client.send, após recebimento do stream, durante iteração e com SDK que ignore abort; stream.destroy sem 'error' listener, ausência de `uncaughtException` e vazamento de listener/handles; ContentLength discrepante, tamanho efetivo diferente, SHA-256 diferente, operações concorrentes, backpressure, cancelamento por cliente e revogação, listener indisponível, nonce e quota contra PG18 real, GET fake, sem chamada R2 real. Diferenciar dados mock do PostgreSQL real, não inferir comprovação física de R2/Cloudflare.

Entregar `PESSOASAQUI_ETAPA30Q_AUDITORIA_GET_ABORT_E_STREAM_R2.md`; Antigravity NÃO corrige, NÃO comita, NÃO faz deploy ou modifica Render/Supabase/Cloudflare de produção.

**STATUS: ETAPA 30Q IMPLEMENTADA, NÃO HOMOLOGADA. ETAPA 30 INCOMPLETA; NÃO HÁ NOVO ENDPOINT R2 EM PRODUÇÃO.**
