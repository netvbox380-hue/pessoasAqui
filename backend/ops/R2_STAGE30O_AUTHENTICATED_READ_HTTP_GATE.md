# PESSOASAQUI — ETAPA 30O: HTTP READ R2 AUTENTICADO, SEM REGISTRO DE ROTA

## Baseline
Antigravity homologou 30N no HEAD `877a6cfe1e6688011dbdd57d98746af93e78dc54`, com 208/208 testes de regressão, 51/51 revogação/VM, 2/2 PostgreSQL, 20/20 cenários HTTP/PG18 (localhost:54333); resolveu as 4 falhas VM anteriores sem skips. A `main` foi confirmada idêntica ao commit homologado antes desta etapa. Ponto de restauração `backup/main-pre-r2-delivery-20261114` preservado no SHA `02b6a487ac8b3ad6c64adf6ce8f6887dbfb53bf9`.

## Implementação preparatória 30O
- `backend/src/r2StagedReadHttpHandler.js`: fábrica `createStagedR2ReadHandler({withTransaction,runtime,openCiphertext,admitRead})` retorna um handler POST somente por invocação explícita. **Não registra rota no Express, nem é importado por server.js; não importa AWS SDK, nem acessa R2 ou env no require.**
- O handler rejeita campos extras do corpo JSON e qualquer `admission` declarado pelo Android, exige cinco campos exatos `identity,peerIdentity,messageId,objectKey,proof` e formato canônico das identidades, chave opaca, tamanho da mensagem e prova com cinco campos.
- Exige `runtime.ready===true` antes de consumir nonce/quota de leitura, e novamente depois da admissão.
- Invoca diretamente `admitAuthenticatedR2Read` da Etapa 21 por padrão, que faz prova ECDSA P-256 associada à chave ativa do PostgreSQL, vínculo bilateral, anti-replay, autorização e quota em uma transação. Nunca aceita `authenticatedIdentity` ou `admission` arbitrária do corpo. Admissão é obtida no backend e comparada aos campos requisitados.
- Encaminha a admissão somente ao `runtime.stream` Etapa 30A, que revalida PostgreSQL e listener imediatamente antes de GET R2 e usa streaming limitado com AbortSignal. Handler define Content-Type octet-stream, Cache-Control no-store/private e nosniff. Não gera URL pré-assinada, não confirma DELIVERED e não vê plaintext.
- Em erros antes da transmissão responde código fixo; após headers enviados, destrói conexão, sem tentar emitir JSON tardio. Nunca registra proof, dados, chave, ciphertext ou segredos em logs.
- `backend/test/r2StagedReadHttpHandler.test.js`: 11 testes unitários preparatórios com mocks; **ainda não executados pelo ChatGPT**.

## Bloqueadores antes de uso real
1. O handler **não está registrado em server.js** nem em qualquer rota; nenhum endereço HTTP é disponibilizado. Antes da ativação, exige gates de rollout, readiness de migrações 004–016, vínculo de origem e hardening de Express (limite JSON para mídia, rate limits, cabeçalhos, conteúdo/stream e HTTP abort).
2. As dependências injetadas precisam ser obrigatoriamente backend confiável. `admitRead` é substituível por testes; jamais injetar callback vindo da rede. `runtime` deve ser `createStagedR2DeliveryRuntime` com LISTEN dedicado, fail closed, reconciliar streams e verificar revogação distribuída.
3. A transferência stream envia ciphertext incrementalmente; SHA-256 só se confirma no EOF. Android deve armazenar temporariamente e descartar bytes parciais/corruptos, verificar digest e persistir ANTES de qualquer ACK. A compatibilidade com Android E2EE segue pendente.
4. A fábrica não cria `openCiphertext` para S3 GET nem instala AWS SDK/Smithy. O adapter GET futuro deve impor timeout real de rede, digest/length e abort/stream destruction, sem URLs expostas ao cliente.
5. O handler não está preparado para publicação/produção: fazer auditoria integrada de HTTP real (sem bypass), tokens, streaming, revogação tardia, pausa/backpressure, limite de memória, cancelamento e custos de egress, antes de montar no servidor. Sem autorização de migrações/flags no Supabase, não há endpoints de mídia ativos.
6. Integrar posteriormente também RESERVE, CONFIRM, ATTACH de forma autenticada, quotas e consentimento, gerenciamento de órfãos e CANCELLED. Etapa 30 ainda não concluída.

## Auditoria Antigravity 30O — somente diagnóstico
Verificar HEAD da `main`, checkpoint, worktree isolada, PostgreSQL18.1 porta 54333, migrações 004–016. Não tocar Android/gradle/logo, produção ou deploy.
Executar:
```sh
node --test backend/test/r2StagedReadHttpHandler.test.js
node --test backend/test/r2ReadAdmission.postgres.test.js
node --test backend/test/r2ActionAuth.test.js
node --test backend/test/r2RecipientReadAuth.test.js
node --test backend/test/r2StreamingGateway.test.js
node --test backend/test/r2StagedDeliveryRuntime.test.js
node --test backend/test/r2UnmarkVmRegression.test.js
node backend/run-tests.js
```
Checar previamente os nomes existentes de testes, sem alterar ou omitir resultados. Testar 11 novos mocks, 208/208 regressão, 2/2 PG de leitura, rotas Express isoladas criadas **exclusivamente dentro do harness** (não em server.js) com prova criptográfica real ECDSA P-256, nonce repetido, vínculo removido, listener indisponível, corpo com campos extras, objeto errado, identidade forjada, quota esgotada, storage GET simulado, stream lento/desconectado, revogação no meio da transmissão e integridade SHA-256 em EOF. Não bypassar autenticação; usar keys assinadas geradas no teste. Medir memória, latência, redirecionamentos, caching e compatibilidade com fail closed; verificar ausência de log/segredo. Distinguir mocks/PostgreSQL real. Se biblioteca/gravação de artefato ficar ausente, reportar.
Relatório `PESSOASAQUI_ETAPA30O_AUDITORIA_HANDLER_HTTP_R2_READ.md`. Antigravity não corrige, não comita, não ativa nada, não publica Play, não acessa Cloudflare R2/Render/Supabase produção.

**ETAPA 30O IMPLEMENTADA, NÃO HOMOLOGADA; ETAPA 30 INCOMPLETA; NENHUMA ROTA NOVA ATIVA.**
