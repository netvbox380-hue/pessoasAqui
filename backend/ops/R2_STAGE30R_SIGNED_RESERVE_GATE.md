# PESSOASAQUI — ETAPA 30R: RESERVA AUTENTICADA R2 POR HTTP (STAGED / INERTE)

## Baseline homologada
O relatório Antigravity da Etapa 30Q informa homologação do commit `7f761f25cc66476be193f4605da68a5aeb9c5a0d`: 208/208 regressão geral, 41/41 testes R2 relacionados, 2/2 testes PostgreSQL e 20/20 cenários de integração em PostgreSQL 18.1 isolado. Armazenamento S3 simulado, **nenhuma chamada real Cloudflare R2**. Antes do trabalho 30R, a `main` estava idêntica a esse SHA; backup `backup/main-pre-r2-delivery-20261114` preservado no SHA `02b6a487ac8b3ad6c64adf6ce8f6887dbfb53bf9`.

## Implementação preparatória
- `backend/src/r2StagedReserveAdmission.js`: `admitAuthenticatedR2Reserve` integra `executeAuthenticatedR2Action` (P-256 ativo do PostgreSQL, conexão bilateral FOR SHARE, nonce anti-replay) com `reserveLeasedMediaObject` (INSERT por chave/mensagem imutável, lease definido pelo clock PostgreSQL em 60 minutos, geração 1) **na MESMA transação**, sem transação aninhada. Evita alegar reserva/PUT antes de COMMIT.
- `backend/src/r2StagedReserveHttpHandler.js`: `createStagedR2ReserveHandler` cria handler POST explicitamente, **não registrado em server.js**, sem URL S3/PUT e sem alterações Android. Corpo JSON de cinco campos exatos: identity, peerIdentity, messageId, objectKey, proof. A assinatura vincula ator, par, messageId e objectKey, e o banco é a autoridade sobre ownership, unicidade e lease. Rejeita campos extras (admission, authenticatedIdentity etc.) antes de SQL.
- O resultado da rota entrega SOMENTE chave reservada, geração, lease, duplicata e messageId, nunca URL pré-assinada. Nada importa credenciais, SDK, SQL ao carregar módulos, ou inicializa timers/servidor.
- Testes novos `backend/test/r2StagedReserveAdmission.test.js` (9 casos mock com chaves P-256 reais, assinaturas, spoof, vínculo unilateral, nonce, colisão, lease, falhas DB) e `backend/test/r2StagedReserveHttpHandler.test.js` (8 cenários de HTTP handler mock). **Não foram executados pelo ChatGPT**.

## Riscos e dependências até o envio real
1. **Etapa 30R apenas reserva o objeto**. Não fornece SigV4/PUT, verifica bytes ou conclui mensagem. Não fornece URL pré-assinada e não realiza R2 físico.
2. O contrato atual aceita uma chave opaca proposta no request, exigindo assinatura e validação PostgreSQL, mas ainda falta definir e auditar a **origem e alocação de chaves opacas**, sobretudo colisões e fluxo de convite da assinatura. Não assumir que chaves são obrigatoriamente geradas pelo servidor, pois essa garantia ainda não existe na rota preparatória.
3. As migrações 008 e 009 são necessárias para reserva, lease e nonce. Ambiente Supabase produção não contém as migrações preparatórias aprovadas. **Nunca registrar a rota no Express de produção sem feature flag, schema gate, backup do banco e autorização do proprietário.**
4. Exige autorização bilateral no momento da reserva. Após reserva, liberação create-only PUT deve usar `r2PutRetirementFence.issuePutWithDurableRetirementFence`; a URL é bearer, validade <=15 min, cabeçalhos pré-condicionais assinados; nenhum URL pode ser entregue antes de COMMIT. Não permitir que os 60 minutos de lease renovem/reativem objetos expirados.
5. A sequência completa exigirá R2_CONFIRM com claim, quotas, leitura real de ciphertext para SHA-256 e tamanho, revalidação/settle no PG; R2_ATTACH com digest assinado do descritor E2EE; tratamento de mensagens CANCELLED e exclusão de órfãos; autenticação de rotas com P-256 sem fallback.
6. `run-tests.js` testa suíte preexistente, que pode não incluir novos `backend/test/r2StagedReserve*.test.js`; executar ambos explicitamente além da regressão.
7. O handler aceitar `reserve` como injeção é somente para testes/ligação exclusiva do backend. Nunca permitir objeto reserve fornecido pelo Android, nem variável de cliente controlar flags, chaves AWS, SQL ou callbacks.
8. No momento **nenhum deploy foi realizado**, `server.js` não importa os novos módulos, e Android, gradle, logos oficiais e assinatura Play permanecem inalterados.

## Auditoria Antigravity 30R — apenas diagnóstico
Verificar HEAD da `main`, backup, worktree isolada, PG18.1 local `127.0.0.1:54333` com migrações 004–016. Nenhum acesso produção.
Executar:
```sh
node --test backend/test/r2StagedReserveAdmission.test.js
node --test backend/test/r2StagedReserveHttpHandler.test.js
node --test backend/test/r2ActionAuth.test.js
node --test backend/test/r2UploadLeaseRegistry.test.js
node --test backend/test/r2CreateOnlyPut.test.js
node --test backend/test/r2PutRetirementFence.test.js
node --test backend/test/r2StagedReadHttpHandler.test.js
node --test backend/test/r2UnmarkVmRegression.test.js
node backend/run-tests.js
node --test backend/test/r2ReadAdmission.postgres.test.js
```
Critérios mínimos: 208/208 regressão geral, todos os novos testes explícitos, zero skips/falhas. Se falhar, relatar sem correção.

Em Express real **somente no harness** e PG18.1 isolado, montar handler e exercitar:
1. identidade P-256 real, assinatura correta do par/key/messageId, identidade forjada/rotacionada, timestamp expirado, nonce repetido, ação trocada e corpo com campos extras;
2. conexão unilateral, revoked, interleaving mark/unmark, concorrência de 2–10 reservas, FK, locks, duplicata exata e conflito com chave/mensagem diferente, lease sem renovação, reuso de objeto após ORPHAN_QUEUED ou DELETED;
3. COMMIT/ROLLBACK: simular falha antes/depois de INSERT, crash antes de resposta HTTP e reenvio com novo nonce sem promover estado, nenhuma reserva fantasma;
4. garantir que nenhuma URL presigned, plaintext, ciphertext, credencial, ID autenticado falsificado, contagem interna de SQL ou stacktrace vaze; rate limit e orçamento de reserva ainda PENDENTES;
5. avaliar possível abuso gerando enormes quantidades de reservas válidas, crescimento de índices, orphan cleanup, quotas e prevenção de egress: **bloqueadores para rollout**;
6. verificar ausência de import em `server.js`, alterações Android e configuração Render/Supabase/Cloudflare real.

Relatório `PESSOASAQUI_ETAPA30R_AUDITORIA_RESERVA_HTTP_AUTENTICADA_R2.md`.

Antigravity apenas diagnostica/testa, NÃO modifica código, commits, dependências, migrações produção, Android, servidor ou infraestrutura.

**STATUS 30R: IMPLEMENTADA, NÃO HOMOLOGADA. ETAPA 30 INCOMPLETA.**
