# PESSOASAQUI — ETAPA 30I: COMPOSIÇÃO RESTRITA DO CLIENTE S3 R2 (STAGED / INERTE)

## Baseline
O Antigravity informou a homologação da Etapa 30H no SHA `b5b49b52ad189f31807d46daa9938fb190abbd36`: 42/42 testes unitários R2, 2/2 PostgreSQL 18.1, 208/208 backend e 20 cenários reais PostgreSQL com respostas S3 **simuladas**. O Cloudflare R2 real não foi contatado. A `main` estava idêntica à baseline e `backup/main-pre-r2-delivery-20261114` preservada no SHA `02b6a487ac8b3ad6c64adf6ce8f6887dbfb53bf9`.

## Implementação
- Novo `backend/src/r2StagedS3ClientFactory.js`, factory explícita `createStagedR2S3DeleteClient`. Não carrega `.env`, SDK, credenciais nem conexões por import; `server.js` não importa o módulo. Não há scheduler, processo, polling ou R2 real.
- O código **aceita apenas construtores injetados** `S3Client`, `DeleteObjectCommand` do SDK v3 e `NodeHttpHandler` Smithy e valores confiáveis de configuração do backend, nunca do Android.
- Exige `accountId` exatamente 32 hex minúsculos, bucket S3 conservador e credenciais não vazias. Forma exclusivamente `https://<account_id>.r2.cloudflarestorage.com`. Não aceita endpoint alternativo, substituição de TLS ou configuração de retries.
- Configura `forcePathStyle: true`, `region: 'auto'`, `maxAttempts: 1`, `https.Agent({rejectUnauthorized:true,keepAlive:true,maxSockets:4})`, `NodeHttpHandler({connectionTimeout:5000,requestTimeout:10000,httpsAgent})`.
- Retorna somente callback `deleteObject` já validado pela 30H e `close()`; nenhuma credencial é retornada pela API. O callback encaminha AbortSignal e requer HTTP 204 após DeleteObject. **Importante:** a factory configura o cliente, mas não implementa credenciais Render, ativação, client singleton, verificações de permissão no provedor, política IAM, lifecycle ou integração a nenhuma rota.
- Novo `backend/test/r2StagedS3ClientFactory.test.js`: 8 testes simulados com construtores falsos, **não executados pelo ChatGPT**.
- Nenhum pacote npm foi alterado nem dependência instalada. Para executar futuramente o módulo contra SDK real, será necessário planejar dependências `@aws-sdk/client-s3` e `@smithy/node-http-handler` com versões fixas, lockfile, auditoria de supply chain, testes de timeout no transporte e orçamento de recursos antes do rollout. Não carregar em produção nesta etapa.

## Cuidados técnicos
- Timeout de request 10s não substitui deadline do orquestrador 30G (15s default) e AbortSignal; verificar na versão do SDK se `NodeHttpHandler` encerra a conexão no prazo sob falhas de rede reais. Um mock não prova capacidade de cancelamento físico no Cloudflare.
- `maxAttempts:1` impede retries automáticos SDK; retries precisam permanecer controlados pela outbox e claim, inclusive falha após HTTP 204 e antes do COMMIT SQL.
- Credenciais de teste e valores de configuração não devem ser impressos em logs. Ambiente Render precisa separar variável secreta do env público e isolar conta/bucket; nenhuma chave deve ir ao APK.
- **Nenhuma medição de egress ou chamada de produção foi executada.** A configuração mínima ainda precisa integração e revisão do operador. Não existe worker periódico, graceful shutdown, revogações autoritativas, endpoints ECDSA P-256, cobertura CANCELLED/orphan/legacy, nem integração Android.
- A futura etapa de ativação deve confirmar explicitamente instrução do proprietário, migrações em Supabase aplicadas em janela de manutenção e backup independente, rollout progressivo com chave de kill-switch e observabilidade de custo.

## Auditoria 30I — Antigravity apenas diagnostica
Confirmar SHA da main, branch de restore, worktree isolada; preservar `app/build.gradle.kts`, logos e Android. PostgreSQL 18.1 isolado 54333, migrações 004–016, nenhuma produção.

Executar:
```sh
node --test backend/test/r2StagedS3ClientFactory.test.js
node --test backend/test/r2StagedDeleteAdapter.test.js
node --test backend/test/r2StagedGcBatch.test.js
node --test backend/test/r2DeleteOutcomeCoordinator.test.js
node --test backend/test/r2DeleteClaimLedger.test.js
node backend/run-tests.js
node --test backend/test/r2ReadAdmission.postgres.test.js
```
Testar sem acesso R2 real:
1. Inércia de imports, nenhum SDK/credencial de produção ou inicialização de rede.
2. Endpoint fixo HTTPS com account_id, path style, região auto, maxAttempts 1, TLS `rejectUnauthorized:true`, `Agent` maxSockets4.
3. Timeouts 5000ms/10000ms e AbortSignal real propagado na cadeia `S3Client.send()`.
4. Testes de valores inválidos (id, bucket, segredo, constructors), controle de erros sem vazamento de segredos.
5. Integração real local com SDK v3 + `NodeHttpHandler` **somente se as dependências já estiverem disponíveis num ambiente isolado**, sem modificar repositório, apontando para servidor local mock TLS e sem Cloudflare real. Certificar que o mock não demonstra proteção em endpoint real.
6. Integrar factory 30I → adapter 30H → lote 30G → claims/outcome Postgres 18 com storage fake, 2 e 10 workers e crash/timeout/concurrency; evidenciar ausência de DeleteObject no import.
7. Revisar recursos do `https.Agent`: encerramento limpo via `close()`, sem sockets persistentes após shutdown, e configuração do NodeHttpHandler compatível com versão específica do SDK.
8. Identificar limitações: não há scheduler, worker real, real provider test, revogação nas rotas, autenticação HTTP, data retention, migração e Android.

Entregar `PESSOASAQUI_ETAPA30I_AUDITORIA_S3_CLIENT_FACTORY_R2.md`. Antigravity NÃO corrige, NÃO commita, NÃO altera servidor, NÃO acessa Cloudflare R2/Render/Supabase produção, NÃO faz deploy nem altera Android.

**ETAPA 30I IMPLEMENTADA, NÃO HOMOLOGADA. ETAPA 30 INCOMPLETA.**
