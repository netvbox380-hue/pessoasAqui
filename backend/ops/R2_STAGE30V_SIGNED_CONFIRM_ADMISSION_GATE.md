# PESSOASAQUI — ETAPA 30V: ADMISSÃO CRIPTOGRÁFICA DA CONFIRMAÇÃO DE UPLOAD (PREPARE ONLY)

## Baseline 30U homologada
Resumo fornecido pelo proprietário: Antigravity aprovou no SHA `8ef63335f19c1bb5732476c5c034d65003aa50ef` com 208/208 testes gerais, 75/75 específicos e 20/20 cenários PG18.1 isolado. Confirmada separação estrita `R2_RESERVE` versus `R2_ISSUE_PUT`, sem fallback, sem efeitos colaterais em rejeições. Antes da Etapa 30V, `main` idêntica ao SHA homologado. Ponto de restauração `backup/main-pre-r2-delivery-20261114` preservado no SHA `02b6a487ac8b3ad6c64adf6ce8f6887dbfb53bf9`.

## Código preparatório
- Novo `backend/src/r2StagedConfirmAdmission.js`: `admitAuthenticatedR2Confirm` recebe identidade/par, messageId, objectKey, geração 1, SHA-256 cifrado, bytes e prova assinada. Reutiliza `executeAuthenticatedR2Action({operation:'R2_CONFIRM'})`, cuja prova ECDSA P-256 valida chave ativa vinculada ao PostgreSQL, conexão bilateral FOR SHARE e nonce anti-replay.
- Dentro do callback da **MESMA transação** de autenticação, chama `claimR2Verification` (Etapa 17) com wrapper `withTransaction: async fn=>fn(tx)` para **não criar nova transação**. Este helper confirma owner/estado RESERVED/lease do objeto, adquire claim distribuído 180s por objeto, respeita orçamento diário de verificação (12 tentativas/200MiB) e faz débito transacional dos bytes esperados. A quota visa custos de GET físico posterior.
- Retorna ticket interno com `claimId`, descritor SHA/bytes, identidade, par, objeto e expiração **somente após `withTransaction` retornar com COMMIT**. Não é payload a entregar ao Android; `claimId` nunca deve ser obtido de body HTTP.
- O retorno `granted:true` **NÃO significa UPLOADED, não verifica bytes, não faz GET e não envia ACK**. É apenas permissão interna para o backend executar leitura física limitada e verificar bytes reais OUTSIDE da transação.
- Novo `backend/test/r2StagedConfirmAdmission.test.js`: 9 testes com mocks, assinaturas P-256 verdadeiras, limites, anti-replay, conexão unilateral, domain separation, single-flight, lease, quota, falha de banco. **Ainda não executados pelo ChatGPT**.

## BLOQUEADORES CRÍTICOS DA CONFIRMAÇÃO FINAL
1. A segunda fase (ainda NÃO implementada) deve chamar `verifyStoredCiphertext` com `GetObject` via cliente de backend autorizado, limite/timeout, SHA-256 e tamanho **dos bytes cifrados realmente recuperados**. Nunca confiar em Android, ETag, HEAD ou metadata.
2. Depois do GET, **nova transação curta** precisa confirmar novamente identidade ativa/vínculo bilateral, propriedade, estado RESERVED, generation=1, lease, `claimId` ainda RUNNING/não expirado, e conteúdo observado idêntico ao objeto reservado; só então concluir `markLeasedMediaUploaded` e `settleR2Verification(successful:true)` NO MESMO COMMIT. Não marcar UPLOADED antes do GET; não manter locks durante rede.
3. Há risco TOCTOU entre GET e atualização de estado se PUT create-only ainda estiver em voo e URL ainda não expirou; exigir política de imutabilidade, fence/expiração/retiro do PUT para a confirmação final. Limitar janela de upload, avaliar revogação no meio de GET.
4. O claim não é capacidade HTTP transferível. Um eventual handler HTTP deve aceitar apenas campos/prova do Android, não aceitar `claimId` de cliente nem devolver URL de GET. O resultado de claim é exclusivamente backend confiável.
5. O helper de verificação usa janela 180s; testar se GET máximo 120s + settlement cabe, expiração, concorrência, e que sucesso tardio NÃO confirma upload.
6. Migrações 004–017 **não instaladas em Supabase produção**, nenhuma rota R2 adicionada em `server.js`, sem Cloudflare R2 real, SDK, Android ou deploy. A integração Android para `R2_ISSUE_PUT` permanece pendente. Etapa 30 incompleta.

## Auditoria Antigravity 30V — somente diagnóstico
Confirmar HEAD main e ponto de restauração, worktree isolada, PG18.1 `127.0.0.1:54333` com migrações 004–017 somente no banco descartável. Não tocar Android, gradle, logos, Render/Supabase/Cloudflare produção.
Executar:
```sh
node --test backend/test/r2StagedConfirmAdmission.test.js
node --test backend/test/r2VerificationAdmission.test.js
node --test backend/test/r2ObjectIntegrity.test.js
node --test backend/test/r2ActionAuth.test.js
node --test backend/test/r2StagedPutIssuance.test.js
node --test backend/test/r2UploadLeaseRegistry.test.js
node backend/run-tests.js
node --test backend/test/r2ReadAdmission.postgres.test.js
```
Se algum teste nomeado não existir, registrar e apresentar substituto correto sem alegar execução.
- Critérios: **208/208 regressão geral, 100% da bateria específica, zero falhas e skips**.
- PostgreSQL real + ECDSA P-256: R2_CONFIRM assinado permite nonce e claim em UM COMMIT, com vínculo bilateral e reserva válida; assinatura RESERVE/ISSUE_PUT não passa. Nonce repetido e chave rotacionada são recusados sem despesas.
- Conta única/concorrência: 2, 10 e 50 verificações simultâneas do mesmo objeto; só uma claim RUNNING; quota 12 tentativas/200MiB por dia/identidade, não ultrapassar limites; bloqueio sem migração 010; falhas rollback integral de nonce/claim/quotas.
- Estados: RESERVED permitido, UPLOADED/ATTACHED/ORPHAN_QUEUED/DELETE_QUEUED, lease expirado ou identidade diferente bloqueados. Falhas de conexão DB, deadlocks, timeouts e rollback COMMIT.
- **Confirmar ausência de streaming ou GET no novo módulo**, nenhum objeto `UPLOADED` criado só por request, nenhuma URL/signature/segredo em logs. Identificar riscos residuais e dependências para finalizar a segunda fase com verificação real SHA-256 e SETTLE idempotente.
- Testar integração Express somente no harness com handler temporário, sem editar server.js, distinguindo mocks, PostgreSQL real e armazenamento S3 simulado. Não declarar que houve verificação física Cloudflare.

Relatório `PESSOASAQUI_ETAPA30V_AUDITORIA_ADMISSAO_CONFIRMACAO_R2.md`.

Antigravity apenas testa/diagnostica, não corrige, não comita, não faz deploy, não configura flags, não toca produção/Android.

**ETAPA 30V IMPLEMENTADA, NÃO HOMOLOGADA; CONFIRMAÇÃO DE UPLOAD FÍSICO AINDA PENDENTE.**
