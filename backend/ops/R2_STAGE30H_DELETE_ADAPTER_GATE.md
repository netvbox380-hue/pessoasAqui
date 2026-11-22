# PESSOASAQUI — ETAPA 30H: ADAPTER PREPARATÓRIO DELETEOBJECT (STAGED / INERTE)

## Baseline homologada
Antigravity informou a homologação 30G no HEAD `be0e5a52cbec1c7cc3ac2a740a87e212c0df91ca`: 45/45 R2 mocks, 208/208 regressão geral, 2/2 PostgreSQL 18 sem skips, 20/20 cenários de integração em PostgreSQL isolado (migrações 004–016). O ambiente **não executou R2 real**; o HTTP 204 foi SIMULADO. A análise também identificou que `Promise.race` não interrompe um SDK que ignore AbortSignal. Confirmação do ponto de restauração `backup/main-pre-r2-delivery-20261114` no SHA `02b6a487ac8b3ad6c64adf6ce8f6887dbfb53bf9` e `main` idêntica à baseline antes das modificações.

## Implementação 30H (sem ativação)
- `backend/src/r2StagedDeleteAdapter.js`: factory `createStagedR2DeleteAdapter({client,DeleteObjectCommand,bucket})`. **Não importa SDK nem cria cliente ou credenciais**. O backend confiável deve injetar a instância oficial AWS SDK v3 e a classe do comando, com endpoint HTTPS exclusivo da conta Cloudflare R2 e credenciais de escopo mínimo.
- A factory apenas valida dependências e retorna callback compatível com `processStagedR2GcBatch`: `deleteObject({objectKey,signal})`.
- Valida chave opaca, bucket e obrigatoriedade de AbortSignal. Não emite operação se já abortada. Envia `new DeleteObjectCommand({Bucket,Key})` e `client.send(command,{abortSignal:signal})`.
- Reconfere `signal.aborted` **após** a Promise do SDK resolver, negando resultado tardio. Exige `result.$metadata.httpStatusCode === 204`. Nunca considera resposta indefinida ou código de erro sucesso. Não registra ACK, state DONE, mensagem ou certificado.
- `backend/test/r2StagedDeleteAdapter.test.js`: 8 cenários unitários novos em SDK simulado, **não executados pelo ChatGPT**.
- `server.js` não importa o módulo. Sem `@aws-sdk/client-s3` instalado, sem conexão, DELETE, polling, scheduler, rota ou deploy. A invocação via mock não executa rede.

## Riscos de engenharia ainda bloqueantes
1. O callback recebe cliente externo, logo a factory **não verifica a configuração TLS, origem R2, credenciais, timeouts nem política de retries do SDK**. Para ativação exigimos um módulo de composição fortemente restrito (endpoint account-id, sem redirect/fallback, timeouts de socket/conexão <=10s, maxAttempts limitado), com testes e configuração de segredo somente no Render. Sem isso, não conectar ao R2.
2. A factory não realiza transação por si só: **somente** compor com `processStagedR2GcBatch`, após claim real 30B, e backpressure/expiração de lease 90s. O callback jamais pode ser exposto como API pública ou baseado em JSON Android.
3. Resposta tardia de SDK ignorando cancelamento pode ter realizado DELETE remotamente mesmo quando a confirmação é recusada. Tombstone PUT selada e DELETE idempotente protegem recuperação, mas é obrigatória auditoria de requests pendentes com `AbortSignal` e transporte real.
4. O próximo passo de Etapa 30 é scheduler com graceful shutdown *depois de* garantir limites globais, métricas sanitizadas, PostgreSQL conectado, retry persistente e ausência de loops quentes. **Não inicializar em import** nem no server.js antes de autorização expressa.
5. Integrações de revogações na transação autoritativa do /api/connections/unmark e das mensagens, autenticação de rotas por prova ECDSA P-256, Android, órfãos CANCELLED/legados, migrações Supabase permanecem PENDENTES.
6. Reexaminar se Cloudflare R2 sempre retorna exatamente HTTP 204 através do AWS SDK na versão pinada; deve ser validado em ambiente descartável antes de qualquer produção.

## Auditoria obrigatória Antigravity
Confirmar SHA do HEAD `main` e backup, worktree isolada, sem mexer em Android, app/build.gradle.kts, logo. Testes:
```sh
node --test backend/test/r2StagedDeleteAdapter.test.js
node --test backend/test/r2StagedGcBatch.test.js
node --test backend/test/r2DeleteOutcomeCoordinator.test.js
node --test backend/test/r2DeleteClaimLedger.test.js
node --test backend/test/r2DeleteCandidateDiscovery.test.js
node backend/run-tests.js
node --test backend/test/r2ReadAdmission.postgres.test.js
```
Usar PostgreSQL 18.1 local 54333 com migrações 004–016, zero skipped. Integrar **simulação** AWS SDK v3 (classe DeleteObjectCommand, client.send) à orquestração 30G e testar: ausência de chamada em import; nenhum DELETE sem claim persistida; 204 exato, 200/500/missing metadata recusados; erro de rede; AbortSignal antes/durante/depois; respostas tardias; storage sucesso PG falha e posterior replay com lease/fencing; 2–10 workers e limite 8; no egress real.

Investigar compatibilidade real do AWS SDK apenas em ambiente isolado se instalado sem modificar repositório; sem R2 real nem credenciais produção. Auditar o caminho de integração futura sem alterar server.js. Relatar limitações de TLS/endpoint/configuração, cancelamento efetivo, idempotência, tempo de socket e falta de scheduler/revogação real. Relatório `PESSOASAQUI_ETAPA30H_AUDITORIA_ADAPTER_DELETE_R2.md`. Antigravity apenas diagnostica, não corrige, não commita, não publica, não acessa Render/Supabase/Cloudflare produção.

**STATUS 30H: implementada para auditoria, NÃO homologada; Etapa 30 INCOMPLETA; R2, GC e rotas desativados.**
