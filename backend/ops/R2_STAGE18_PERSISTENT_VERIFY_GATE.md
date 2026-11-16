# PESSOASAQUI — ETAPA 18: admission de verificação R2 com orçamento SQL (STAGED)

**Situação:** implementação preparatória, não homologada; **NENHUM deploy**. Etapa 17 aprovada em testes sintéticos (13/13 integridade, 208/208 regressão), com riscos de TOCTOU, cotas e SDK ainda abertos.

## Correção em main

- `backend/migrations/010_r2_verification_budget_PREPARE_ONLY.sql` cria:
  - `r2_verification_daily_budget`, por identidade e dia UTC, com tentativas e orçamento de bytes;
  - `r2_verification_claims`, com chave única por `object_key`, geração, claim UUID, estado e validade;
  - RLS ativado e nenhuma permissão pública concedida. Não executar essa migração no Supabase real.
- `backend/src/r2VerificationAdmission.js` prepara `claimR2Verification()`: identifica remetente e destinatário, confirma propriedade do objeto com consulta SQL `FOR UPDATE`, rejeita `UPLOADED/ATTACHED` antes de qualquer GET, valida geração 1 e lease ativo, trava o orçamento diário via `FOR UPDATE`, obtém claim única por objeto e cobra o custo estimado em bytes *dentro da transação*.
- Os limites preparatórios são **12 verificações/dia e 200 MiB/dia por identidade**, 25 MiB por objeto, com claim de 180 segundos. Limites NÃO estão ativados e devem ser revisados em testes de uso real, sem prejudicar entrega legítima.
- Chamadas concorrentes por mesma identidade são serializadas pela linha de orçamento SQL; mesma key é serializada pela linha de objeto e claim única compartilhada entre instâncias Render.
- `settleR2Verification()` marca claim completada/falha com token de servidor; **COMPLETED somente após verificação física real e confirmação do upload na MESMA transação SQL**. Tentativas que falham ainda consomem orçamento (recurso de controle de abuso).
- `backend/test/r2VerificationAdmission.test.js` fornece testes unitários de admissão, limites, autoria, falha de banco e single-flight (não executados por ChatGPT).

## Segurança e integração ainda pendentes

1. O módulo aceita `authenticatedIdentity` como argumento **apenas da camada servidor**; não pode ser consumido diretamente de JSON do Android. A etapa 15 deve validar a assinatura P-256 e anti-replay antes de chamar o módulo. O módulo não autentica sozinho.
2. A integridade e a marcação de `UPLOADED` devem ser feitas **fora/depois** da primeira transação de claim: reserva no DB → COMMIT → GET SHA-256 e tamanho R2 → nova transação revalida estado/vínculo → confirmação `markLeasedMediaUploaded` e `settleR2Verification(successful:true)` atômicos. Não executar GET externo dentro de transação.
3. `FAILED` e claims expiradas permitem nova tentativa, mas cada tentativa é cobrada. Nunca permitir duas verificações simultâneas do mesmo objeto; timeout global parametrizável até 120s e claim persistente de 180s. **Ainda é obrigatório comprovar o fencing:** nenhuma confirmação poderá ocorrer após a claim vencer ou ser substituída, e timeouts de SDK/cliente não podem estender a operação além da janela.
4. **TOCTOU continua aberto**: UUID de chave imprevisível não significa imutabilidade. Antes do uso real, bloquear sobrescrita de objeto ou usar geração/versionamento verificável; GET + SHA não provam que o R2 permaneceu inalterado.
5. O controle é apenas por identidade: limites adicionais por IP/instância/global, limites simultâneos e custos reais de transferência seguem pendentes. O orçamento deve considerar que bytes baixados podem divergir de bytes declarados; o GET é limitado estritamente pelo verificador.
6. Nenhuma configuração de AWS SDK `NodeHttpHandler` existe no backend implantado. Exigir connectionTimeout, socketTimeout, maxAttempts e AbortSignal nas rotas futuras.
7. Não criar endpoint HTTP/WS, credenciais, rotina de GC, migração aplicada, mudanças no Android ou deploy. O fluxo atual de mensagens permanece intocado.

## Homologação Antigravity — SEM CORREÇÕES

- Verificar HEAD e ponto de restauração `backup/main-pre-r2-delivery-20261114`.
- Executar:
  - `node --test backend/test/r2VerificationAdmission.test.js`
  - `node --test backend/test/r2ObjectIntegrity.test.js`
  - `node --test backend/test/r2ActionAuth.test.js`
  - `node backend/run-tests.js`.
- PostgreSQL 18 isolado: aplicar migrações 004–010; executar transações concorrentes em duas identidades e duas instâncias simuladas sobre mesmo objeto; comprovar uma única claim, orçamento atômico, primeira e última tentativa do dia UTC, rollback, cancelamento e rejeição de objeto confirmado, órfão ou pertencente a outro remetente.
- Verificar que SETTLE COMPLETED dentro da mesma transação que a confirmação SQL é indispensável para a integração e que a claim expirada não permite que um GET antigo confirme após outra claim.
- Testar colisão de claim e cobranças diárias na mudança de dia UTC, expiração de claim com GET ainda ativo, múltiplas instâncias e deadlocks.
- Não aplicar automaticamente qualquer correção; registrar bloqueadores.
- Sem código novo pelo Antigravity, sem commits, Render, Supabase produção, Cloudflare ou Android.

**STATUS:** PRÉ-PRODUÇÃO. Rollout do R2 BLOQUEADO.
