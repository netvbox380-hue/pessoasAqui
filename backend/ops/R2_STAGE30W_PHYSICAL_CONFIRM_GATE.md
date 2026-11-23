# PESSOASAQUI — ETAPA 30W: GET FÍSICO VERIFICADO + FINALIZAÇÃO TRANSACIONAL (PREPARE ONLY)

## Baseline 30V-FIX homologada
O Antigravity informou homologação da 30V-FIX no commit `261e992d9b8486094c606483e6bfaa1b891410ce`: 10/10 novos testes de confirmação, 63/63 suites R2, 208/208 regressão geral, 20/20 cenários PG18.1 isolado; zero falhas ou skips. Confirmou que 30V só cria claim RUNNING e debita quota, sem GET ou UPLOADED. Antes de começar 30W, `main` idêntica ao commit auditado; backup `backup/main-pre-r2-delivery-20261114` preservado no SHA `02b6a487ac8b3ad6c64adf6ce8f6887dbfb53bf9`.

## Código preparatório 30W
- `backend/src/r2StagedPhysicalConfirm.js` integra três fases SEPARADAS: (a) `admitAuthenticatedR2Confirm` (30V: prova R2_CONFIRM P-256, nonce, bilateral, claim + quota em tx#1); (b) `preflightRetiredR2Put` (tx#2, valida identidade SPKI ativa, vínculo bilateral, mídia RESERVED+lease+geração, claim RUNNING, sela chave PUT no ledger 013 e exige que última URL tenha expirado mais 120s; retorna retry-later e **NÃO faz GET** se ainda ativa); (c) `verifyStoredCiphertext` por GET stream físico **FORA DE TRANSAÇÃO**, com tamanho/SHA256 e timeout 30s; (d) `finalizeVerifiedR2Upload` (tx#3, revalida todos os vínculos, claim, lease, ledger sealed+expirado, e usa `markLeasedMediaUploaded` junto a `settleR2Verification(successful:true)` sob MESMO COMMIT). A falha de settlement LANÇA, exigindo rollback do UPDATE para UPLOADED. Nunca emitir ACK de entrega nesse componente.
- `verifyAndFinalizeStagedR2Upload({withTransaction,identity,peerIdentity,messageId,objectKey,generation,contentSha256,contentBytes,proof,client,GetObjectCommand,bucket,...})` é apenas fábrica/orquestração interna, **não é rota**; cliente e command só podem vir do backend. `verifyCiphertext`/ `admitConfirm` substituíveis apenas para mocks de teste. Assumir resultado de GET falso de Android/HTTP é PROIBIDO. A prova P-256 é validada pela admissão 30V antes do fluxo; a chave pública ativa é revalidada antes e depois do GET para detectar rotação.
- `backend/test/r2StagedPhysicalConfirm.test.js`: 10 novos testes simulados para sucesso, janelas PUT ainda abertas, SHA/bytes errados, GET falhando, settlement/upload falhando, revogação ou rotação, claim expirado, reserva órfã, ordenação de transações e ausência de I/O segurando locks. **Testes não executados pelo ChatGPT.**
- Nenhum código alterado no servidor Express principal, Android, Gradle, logos, migrações, SDK ou worker. Sem deploy e sem chamadas R2 real.

## BLOQUEADORES IMPORTANTES ANTES DE ATIVAÇÃO
1. A confirmação **NÃO É INSTANTÂNEA** se houver URL PUT ainda válida. A etapa sela a chave e aguarda expiração do último PUT + margem 120s antes da leitura. Isso é intencional para evitar TOCTOU, mas introduz UX/latência de até ~17min e o claim inicial de 180s poderá expirar: exigirá novo R2_CONFIRM assinado com outro nonce e quota (só então GET). Propor protocolo mais eficiente com barreira de upload comprovada antes do cutover; não remover o bloqueio sem garantia alternativa.
2. **Expiração da URL não é prova absoluta de conclusão de PUT iniciado antes do vencimento**. A margem 120s depende de limites reais de duração da requisição, cancelamento de upload em voo e semântica real S3/R2. Testar cenário late PUT que começou antes do vencimento e finalizou após a margem: se possível, BLOQUEAR rollout até garantir imutabilidade física via upload gateway/fencing mais forte ou armazenamento write-once. Não declarar TOCTOU resolvido só com timestamp de ledger.
3. Os testes deste commit usam storage SIMULADO e verificação mock para isolar a atomicidade. A verdadeira integração AWS SDK/S3 física ainda exige ambiente descartável/autorização e configuração segura de credenciais; nunca usar produção.
4. Sem registro em `server.js`: não há rota R2_CONFIRM, nem emissão de URL, nem AWS SDK ativo, nem Android capaz de assinar todas operações. Migrações 004–017 seguem fora de Supabase de produção.
5. Na etapa 30W o claim pode ficar RUNNING até expirar se preflight falhar ou GET falhar. A Etapa seguinte deverá auditar política de settlement FAILED e retries, sem permitir que falha de GET marque UPLOADED. Não aceitar `claimId` ou `verification: true` arbitrária do Android.
6. Testar os triggers SQL da migração 008 na transição RESERVED→UPLOADED, especialmente `content_sha256`, `content_bytes`, `uploaded_at`, e rollback após falha de `settleR2Verification`. Exigir prova com PG real, mocks não comprovam isso.
7. A proteção de orçamento de confirmação e reservas e limpeza de órfãos permanecem fundamentais. Sem mensagem DELIVERED antes de ACK local verificado pelo Android.

## Antigravity — auditoria mandatória 30W
Confirmar SHA da main, backup, worktree isolada, PG18.1 local `127.0.0.1:54333` com migrações 004–017 apenas em banco descartável. Preservar Android, gradle, logos. Executar:
```sh
node --test backend/test/r2StagedPhysicalConfirm.test.js
node --test backend/test/r2StagedConfirmAdmission.test.js
node --test backend/test/r2VerificationAdmission.test.js
node --test backend/test/r2ObjectIntegrity.test.js
node --test backend/test/r2UploadLeaseRegistry.test.js
node --test backend/test/r2PutRetirementFence.test.js
node --test backend/test/r2StagedPutIssuance.test.js
node --test backend/test/r2ActionAuth.test.js
node backend/run-tests.js
node --test backend/test/r2ReadAdmission.postgres.test.js
```
Requerer 208/208 regressão, todos os específicos, zero falhas/skips. Em PG18.1 real, executar ao menos 20 cenários:
- Prova R2_CONFIRM P-256 e claim/quotas; preflight sela PUT e aguarda ledger 013: sem URL/ledger, fail closed; PUT ainda vivo, **zero GET**.
- Expiração da janela de PUT +120s, claim válido, reserva ainda no prazo, vínculo bilateral ativo e prova SPKI: só então GET de ciphertext SIMULADO com Node stream, nunca plaintext. Distinguir mock de GET real.
- Verificar SHA-256 dos bytes lidos, content length e bytes reais, corpo truncado, digest divergente, cancelamento de rede, timeout, URL tardia, objeto inexistente; nenhuma transição em falha.
- Com conteúdo válido, segunda transação deve atualizar message_r2_objects e claim no MESMO COMMIT; forçar erro de UPDATE, erro de settlement e COMMIT: ambos rollback, nunca estado UPLOADED isolado. Integridade dos triggers 008.
- Forçar rotação da chave P-256, revogação da conexão, lease ou claim expirado DURANTE GET; depois do GET deve bloquear UPDATE. Rechecar identidade do objeto, generation, claimId não vindo de HTTP, ledger sealed e prazo contra workers concorrentes.
- Testar retenção, expiração da URL PUT e possíveis uploads em andamento que iniciaram antes do vencimento mas terminam tarde. Auditar severidade de race físico: se não houver garantia auditável, manter produção bloqueada.
- Medir concorrência 2,10,50, deadlocks, locks, egress estimado, sem S3 físico, leak de URLs/chaves, HTTP e Android inertes. Se o mock de verificação puder ser injetado por dados do cliente, considerar **bloqueante**.
- Validar caso de primeiro confirm retornar retry e claim RUNNING 180s: próxima tentativa, nonce novo, quotas e possível demora até capability expirar; propor melhoria UX sem alterar código.
- Rejeitar a homologação se teste falhar, se ocorrer UPLOADED antes de checksum real ou se a falha de settlement deixar estado parcial.

Entregar `PESSOASAQUI_ETAPA30W_AUDITORIA_CONFIRMACAO_FISICA_R2.md`; Antigravity apenas diagnostica/testa, não modifica arquivos, não faz push/commit/deploy, não acessa Render/Supabase/Cloudflare real, não muda Android.

**STATUS 30W IMPLEMENTADA PREPARATORIAMENTE, NÃO HOMOLOGADA; CUTOVER R2 CONTINUA BLOQUEADO.**
