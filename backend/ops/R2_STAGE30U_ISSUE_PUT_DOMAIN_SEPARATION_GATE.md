# PessoasAqui — Etapa 30U: domínio criptográfico exclusivo R2_ISSUE_PUT (STAGED / INERTE)

## Diagnóstico da homologação 30T
O usuário encaminhou resumo da auditoria Antigravity `PESSOASAQUI_ETAPA30T_AUDITORIA_ISSUANCE_PUT_R2.md`: 208/208 regressão backend, 72/72 suítes específicas, 20/20 cenários PostgreSQL 18.1 isolado e assinador SigV4 fake. A auditoria APPROVOU 30T apenas em caráter preparatório e identificou **BLOQUEADOR DE PRODUÇÃO**: reutilizar assinatura canônica `R2_RESERVE` para liberar capacidade física PUT viola separação de domínio criptográfico. Nonce novo não corrige essa confusão de finalidade.

Baseline GitHub auditada: `82c8df41c3cddd209cd9970750ccf97d933708d0`, main idêntica antes de iniciar 30U. Backup imutável `backup/main-pre-r2-delivery-20261114` = `02b6a487ac8b3ad6c64adf6ce8f6887dbfb53bf9`.

## Alterações 30U
1. `backend/src/r2ActionAuth.js`: contrato OPERATIONS inclui **`R2_ISSUE_PUT: ['messageId','objectKey']`** como operação explícita, com a MESMA serialização canônica `PA-R2-ACTION-V1`, mas domínio textual exclusivo. Assim, com ator, destinatário, chave, mensagem, timestamp e nonce idênticos, as assinaturas de RESERVE e ISSUE_PUT diferem. Sem downgrade ou alias. Outras operações RESERVE, CONFIRM, ATTACH e READ permanecem inalteradas.
2. `backend/src/r2StagedPutIssuance.js`: `issueAuthenticatedR2Put` usa exclusivamente `executeAuthenticatedR2Action({operation:'R2_ISSUE_PUT',...})`; exige prova P-256 assinada especificamente para emissão de capability, com nonce válido e chave SPKI ativa. A consulta PostgreSQL, row lock, lease, estado, propriedade e ledger PUT da Etapa 30T permanecem iguais. Uma assinatura `R2_RESERVE`, mesmo com novo nonce, é rejeitada antes de emitir URL.
3. `backend/test/r2StagedPutIssuance.test.js`: troca o gerador de prova da emissão para `R2_ISSUE_PUT` e acrescenta três verificações: assinaturas RESERVE não emitem PUT; assinatura ISSUE_PUT não autoriza RESERVE; payloads canônicos diferem com todos os demais campos idênticos. **Testes ainda não executados pelo ChatGPT**. O contrato é exclusivamente backend/staged: não modificar Android agora.

## Bloqueadores preservados
- O Android ainda não sabe produzir prova `R2_ISSUE_PUT`, portanto **a emissão não é interoperável até a futura integração Android**, a ser autorizada na Etapa 31. Esse bloqueio é intencional/fail-closed; nunca fazer fallback para R2_RESERVE.
- Não há HTTP endpoint PUT exposto em `server.js`, credenciais, assinatura SigV4 real ou dependências AWS SDK instaladas. Nenhuma migração 004–017 aplicada ao Supabase produção; não habilitar Render ou Cloudflare real.
- O contrato `R2_ISSUE_PUT` assina `messageId` e `objectKey`. A operação permanece vinculada à autorização bilateral atual e consulta da reserva/ledger. Se futuras URLs estiverem vinculadas a conteúdo SHA256/tamanho, criar nova versão explícita de protocolo, sem extrapolar a assinatura atual.
- A origem da chave opaca e sua política temporal, emissão de presigned URLs de teste reais (somente em infra descartável), limites HTTP, custo/egress, confirmação por leitura de bytes cifrados, anexação de descritores E2EE e limpeza órfã continuam pendentes. Etapa 30 permanece incompleta.

## Antigravity — auditoria 30U (apenas diagnóstico)
Confirmar HEAD main e backup imutável, worktree isolada, PG18.1 local `127.0.0.1:54333`, migrações 004–017 SOMENTE no banco isolado, zero alterações Android/gradle/logos.
Executar:
```sh
node --test backend/test/r2StagedPutIssuance.test.js
node --test backend/test/r2ActionAuth.test.js
node --test backend/test/r2StagedReserveAdmission.test.js
node --test backend/test/r2StagedReserveHttpHandler.test.js
node --test backend/test/r2CreateOnlyPut.test.js
node --test backend/test/r2PutRetirementFence.test.js
node --test backend/test/r2ReserveDailyBudget.test.js
node backend/run-tests.js
node --test backend/test/r2ReadAdmission.postgres.test.js
```
Exigir **208/208 regressão**, todas as suítes específicas aprovadas, zero falhas/skips. No PostgreSQL real + signer fake:
1. RESERVE assinada corretamente cria somente reserva, não emite PUT; mesmo com nonce ainda nunca utilizado, `R2_RESERVE` apresentada à emissão recebe 401/erro de prova e NÃO insere nonce/ledger nem chama signer.
2. `R2_ISSUE_PUT` assinada com nova prova autoriza PUT somente quando existe reserva prévia do mesmo ator/par/message/key, vínculo bilateral, lease e ledger não selado.
3. A prova ISSUE_PUT não autoriza reservas R2_RESERVE nem CONFIRM/ATTACH/READ, inclusive em mesmos campos; mudar operation, actor, peer, message/key, timestamp ou nonce invalida assinatura.
4. Mesma `nonce` em operações distintas não pode executar duas ações para a mesma identidade; banco controla replay e rollback.
5. Nonce, vínculo, lease, PUT fence e SIGNER erros; COMMIT/ROLLBACK atômico, capacidade bearer só após COMMIT.
6. Concorrência de 2/10 trabalhadores na mesma chave, mark/unmark, lease expirado, chave selada e tentativas de uso com client legado.
7. Exigir confirmação de que nenhuma rota foi registrada em `server.js` ou credenciais/libs reais configuradas; ausência de fallback para RESERVE se Android ainda não tiver suporte.
8. Validar que prova ECDSA-P256 no protocolo Android futuro pode ser derivada pela mesma canonicalização, DOMÍNIO distinto, sem modificar Android nesta auditoria; avaliar protocolo/versão e testes interlinguagem.

Entregar `PESSOASAQUI_ETAPA30U_AUDITORIA_DOMAIN_SEPARATION_ISSUE_PUT_R2.md`. Antigravity SOMENTE testa e diagnostica; não modifica código, não comita, não realiza deploy, não toca Android, Render, Supabase ou R2 produção.

**STATUS: 30T homologada APENAS preparatoriamente. 30U CORRIGIDA NO CÓDIGO, AINDA NÃO HOMOLOGADA; PRODUCTION CUTOVER BLOQUEADO.**
