# PESSOASAQUI — ETAPA 30P: GET R2 CIFRADO COM CANCELAMENTO ATÉ O FIM DO STREAM (PREPARE ONLY)

## Baseline homologada
O Antigravity informou homologação da Etapa 30O no commit `8a1abe065b7d0256900dcc03792d87d294d209de`, com 208/208 testes gerais, 42/42 unitários R2, 2/2 PostgreSQL 18.1 e 20/20 cenários Express/PG18 com armazenamento simulado; 0 falhas e 0 skips. **Nenhum teste real em Cloudflare R2 foi executado.** Antes da Etapa 30P a main foi verificada idêntica à baseline. Checkpoint `backup/main-pre-r2-delivery-20261114` no SHA `02b6a487ac8b3ad6c64adf6ce8f6887dbfb53bf9`.

## Objetivo e implementação
- `backend/src/r2StagedGetTransport.js`: `createStagedR2GetTransport({client,GetObjectCommand,bucket})` constrói callback `openCiphertext({objectKey,expectedBytes,abortSignal})` compatível com `r2StreamingGateway`. A fábrica é inerte no import; nenhuma chamada de rede, credencial, rota ou SDK é carregado automaticamente.
- Acesso R2 somente por objeto opaco validado, bucket configurado no backend, e sinal de cancelamento obrigatório. Limite de 25 MiB e igualdade de `ContentLength` e `expectedBytes`; sem digest/ACK automático: streaming gateway verifica SHA-256 e tamanho no EOF.
- Usa `fetchCiphertext` existente, encaminhando `abortSignal` ao `S3Client.send(new GetObjectCommand(...),{abortSignal})`. Rejeita GET se já abortado, destrói `Body` tardio após cancelamento ou com tamanho indevido. Exige `Readable` Node.js cancelável e instala listener de abort que **permanece até close/end/error do stream**, evitando a lacuna de cancelamento entre `GetObject` e o fim do download.
- Não registra HTTP no `server.js` e não disponibiliza URL pré-assinada ao cliente. Não toca Android nem DB. A interface do adaptador é transporte: identidade e permissão são obrigatórias na cadeia 30O / gateway antes de seu uso.
- `backend/test/r2StagedGetTransport.test.js`: 10 cenários simulados para inércia, bucket/chave/sinal, tamanhos, body inválido, cancelamento em trânsito, resposta tardia e erro de SDK. **Ainda não executados pelo ChatGPT.**

## Bloqueadores antes de produção
1. Dependências AWS SDK v3/Smithy ausentes no pacote principal; não há composição real de `GetObjectCommand` com cliente de produção, nem teste de egress, socket timeout ou credenciais R2.
2. `Promise.race` não interrompe SDK que ignore o sinal. A verificação pós-`client.send` e a destruição do Body impedem transmissão tardia no caminho auditado, mas testes físicos do transporte continuam necessários.
3. O caminho R2_READ ainda não está registrado no Express, não há lifecycle real de `LISTEN` dedicado na produção nem migrações 004–016 em Supabase. A chave R2 nunca deverá constar no Android.
4. Antes do rollout é necessário testar comportamento de streams destruídos com erro, consumo de eventos e desconexão de cliente (não deixar erro `unhandled`), backpressure, limitadores, checksum, quota, head-of-line blocking e revogação entre chunks.
5. O backend ainda precisa de endpoints autenticados para RESERVE, CONFIRM, ATTACH, regras de PUT create-only, limitação de custos e fluxo de limpeza órfãos/CANCELLED; após isso, integração Android e testes controlados com até três aparelhos.
6. Nenhuma autorização para ativar endpoints, SDK, migrar Supabase, modificar Render ou acessar R2 real foi dada.

## Auditoria Antigravity — somente diagnóstico
Confirmar SHA da main, checkpoint, worktree isolada e PostgreSQL 18.1 `127.0.0.1:54333`, migrações 004–016. Preservar Android, `app/build.gradle.kts`, logos e a assinatura Play. Não acessar produção.

Executar:
```sh
node --test backend/test/r2StagedGetTransport.test.js
node --test backend/test/r2StagedReadHttpHandler.test.js
node --test backend/test/r2StreamingGateway.test.js
node --test backend/test/r2StagedDeliveryRuntime.test.js
node --test backend/test/r2ReadAdmission.postgres.test.js
node --test backend/test/r2ActionAuth.test.js
node backend/run-tests.js
```

Cenários de homologação:
- Import e factory não abrem socket; GET apenas após invocação autorizada com `objectKey` opaco, bucket confiável, `expectedBytes` e `AbortSignal`.
- `S3Client.send` recebe a chave exata e `abortSignal`; cliente que omite ou ignora abort; stream GET que continua após resposta SDK; cancelamento antes da requisição, durante `send`, depois de `send`, entre chunks e durante backpressure.
- Respostas S3 com ContentLength ausente, 0, menor ou maior que o descritor; bytes reais maiores/menores apesar do ContentLength correto; payload SHA-256 divergente no EOF; chunks inválidos; erro de stream sem listener, ordenação de eventos de erro/end/close.
- PostgreSQL real e Express isolado, provas ECDSA P-256, replay de nonce, quota, pair unmark e revogação mid-stream, listener indisponível, R2 mock e zero acesso real à Cloudflare.
- Teste sob concorrência e uso de memória O(1), liberação de listeners/socket após erro/aborto, sem vazamento de mensagens cifradas/credenciais em logs, nenhum ACK até confirmação Android.
- Distinguir claramente mock SDK de testes com real Node stream e PG18. **Não usar AWS/Cloudflare real**, nem atribuir confirmação física ao mock.

Entregar `PESSOASAQUI_ETAPA30P_AUDITORIA_GET_TRANSPORT_R2.md`. Antigravity somente diagnostica, não corrige, não comita, não publica, não realiza deploy.

**STATUS: ETAPA 30P IMPLEMENTADA, NÃO HOMOLOGADA. ETAPA 30 E CUTOVER R2 AINDA INCOMPLETOS.**
