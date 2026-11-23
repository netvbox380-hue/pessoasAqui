# PESSOASAQUI — ETAPA 30V-FIX: REGRESSÃO DAS FIXTURES DE CONFIRMAÇÃO (STAGED)

## Resultado recebido
Antigravity relatou **208/208 regressão geral**, testes existentes de R2 aprovados e **20/20 cenários PostgreSQL 18.1 isolado aprovados** na Etapa 30V sobre `31d7775ff92c8127ddfa69594d0ea20c8829794f`. O relatório também identificou **DUAS FALHAS na suíte NOVA `backend/test/r2StagedConfirmAdmission.test.js`**. Segundo o resumo, a execução com fixture corrigida no worktree do auditor passou 9/9, mas a correção não havia sido persistida na `main`. Portanto, **30V não deve ser declarada integralmente homologada até a execução no novo HEAD de todos os testes oficiais**.

## Mudanças mínimas efetuadas na main
- `backend/test/r2StagedConfirmAdmission.test.js`: gerador da assinatura separa os campos por operação: `R2_CONFIRM` usa exatos `messageId, objectKey, generation, contentSha256, contentBytes`; `R2_RESERVE` e `R2_ISSUE_PUT` usam exatos `messageId, objectKey`. Assim o teste de assinatura cruzada usa uma prova criptográfica de fato válida para a operação errada; a recusa deve ocorrer **na verificação ECDSA de domínio**, não na geração da própria prova.
- Remove assertion dependente de um `claimId` literal de mock, exigindo apenas UUID sintaticamente válido. A associação autoritativa do claim, identidades/objeto/limites permanece testada na cadeia com PostgreSQL real.
- Acrescenta teste de regressão para provar que assinatura válida `R2_ISSUE_PUT` não autoriza `R2_CONFIRM`; somente consulta a binding key, sem nonce/claim/quota.
- **Nenhuma modificação em código de runtime/produção** (`backend/src`), banco, servidor, Android, gradle ou logotipos. Nenhum deploy, flags ou migração.
- Verificação SHA-256 física e atualização `UPLOADED` continuam pendentes da Etapa 30W; nenhum novo endpoint foi criado.

## Auditoria Antigravity obrigatória
Usar worktree isolada do HEAD registrado, backup `backup/main-pre-r2-delivery-20261114` em `02b6a487ac8b3ad6c64adf6ce8f6887dbfb53bf9`, PG18.1 isolado em 127.0.0.1:54333 com migrações 004–017 exclusivamente descartáveis.
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
Confirmar contagem real de testes (há teste extra de separação de operação), **0 falhas / 0 skips**, 208/208 geral. Se falhar, reportar causa e stack sem alterar código.
Reexecutar os 20 cenários PG18.1+Express staged, conferir que só o claim RUNNING e quota são criados, enquanto `message_r2_objects.state` continua RESERVED com content_sha256/content_bytes/uploaded_at nulos, sem GET e sem UPLOADED. Rejeição de `R2_RESERVE` e `R2_ISSUE_PUT` para CONFIRM deve ocorrer sem qualquer efeito colateral. Não confundir testes unitários simulados com integração PG real.
Entregar `PESSOASAQUI_ETAPA30V_FIX_AUDITORIA_REGRESSAO_FIXTURE_CONFIRM_R2.md` com diff auditado e parecer final.

## Próxima implementação após homologação
Etapa 30W deve operar em duas transações curtas distintas com GET externo entre elas, jamais sustentar lock durante rede. A confirmação final exige verificação de bytes cifrados e SHA-256, vínculo ativo, claim válido, estado RESERVED/generation1/lease e regra de imutabilidade versus PUT concorrente, transição `UPLOADED` e settle COMPLETED em **mesmo COMMIT**. Qualquer falha deve impedir UPLOADED e não liberar falsa entrega. Avaliar fence de PUT e race de upload tardio antes de implementar.

**STATUS: CORREÇÃO DE FIXTURES IMPLEMENTADA, AINDA NÃO HOMOLOGADA; PRODUÇÃO R2 INERTE.**
