# PESSOASAQUI — ETAPA 22: revalidação de leitura no início do streaming R2 (STAGED)

**Base:** Relatório Antigravity ETAPA 21B, commit 5309e66: erro PostgreSQL 42725 corrigido, teste SQL real 2/2 sem skips, 8/8 unitários, 208/208 regressão, oito cenários de PostgreSQL 18 isolado aprovados. A Etapa 21B está homologada apenas quanto ao escopo testado. **Nenhuma rota R2 está habilitada.**

## Implementação preparatória

- `backend/src/r2StreamStartGate.js`: `revalidateR2ReadAtStreamStart({withTransaction,admission})` exige autorização prévia `admitAuthenticatedR2Read()` da Etapa 21 (prova P-256/nonce, posse exata da mídia e quota).
- Imediatamente antes do futuro início do GET, em uma **nova transação curta** consulta as duas direções da conexão sob `FOR SHARE`, invoca `authorizeRecipientR2Read` novamente e exige a correspondência dos descritores SHA-256 e tamanho já admitidos.
- Rejeita revogação, unilateralidade, cancelamento, expiração, estado terminal, objeto alterado ou banco indisponível. Não cria bearer token, não emite presigned GET, não lê R2.
- `backend/test/r2StreamStartGate.test.js`: oito testes unitários novos para sucesso, ausência de admissão, revogação, cancelamento, expiração, objeto divergente, metadados inválidos e erro PostgreSQL. **Ainda não executados pelo ChatGPT.**

## Fronteira de segurança importante

A entrada `admission` é **EXCLUSIVAMENTE objeto de contexto do backend, retornado por `admitAuthenticatedR2Read` na mesma requisição**. Ela não é prova criptográfica autônoma, não deve ser desserializada de JSON do Android, cookie ou cache alterável pelo cliente, e jamais autoriza uma URL GET direta.

A revalidação detecta revogação **antes** de iniciar a transferência, mas **não cancela bytes já em trânsito** depois do COMMIT. A aplicação final precisa decidir se exige revogação efetiva durante uma transmissão já aberta. Para isso será necessário gateway de streaming mantido pelo backend com sinalização/checagens contínuas e cancelamento, em vez de distribuir presigned URLs GET diretamente. Polling agressivo ao PostgreSQL pode aumentar egress e custo; projetar evento/estado compartilhado com limite de frequência e testes multi-instância.

O lock transacional vale somente enquanto a transação está aberta. O stream R2 NÃO pode ocorrer dentro da transação PostgreSQL. Portanto permanece uma janela temporal entre o COMMIT do preflight e o primeiro byte; não apresentar o gate isolado como revogação imediata garantida.

**Riscos ainda abertos:** autenticação específica do gateway, observação de revogações pós-COMMIT, TOCTOU de DELETE→PUT com URL de escrita ainda válida, validação digest na transmissão, cancelamento de streams, quota global / IP, configuração real do AWS SDK e ACK após persistência local Android. A proteção R2 create-only não cobre recriação após exclusão.

## Antigravity — homologação exclusivamente isolada

1. Confirmar HEAD da main e ponto de restauração `backup/main-pre-r2-delivery-20261114`.
2. Executar:
   - `node --test backend/test/r2StreamStartGate.test.js`
   - `node --test backend/test/r2ReadAdmission.test.js`
   - `PESSOASAQUI_TEST_PG_URL=... node --test backend/test/r2ReadAdmission.postgres.test.js` (**0 skipped**, porta local 54333)
   - `node --test backend/test/r2RecipientReadAuth.test.js`
   - `node --test backend/test/r2ActionAuth.test.js`
   - `node backend/run-tests.js`.
3. PostgreSQL 18 isolado, migrações 004–011: revogar vínculo **entre admissão e preflight**; cancelar e expirar mensagem entre transações; marcar terminal; simular troca de digest, estado do objeto, atraso de conexão, corrida com revogação concorrente e banco indisponível.
4. Verificar o SQL real sob `FOR SHARE`, a ordem de locks e ausência de deadlock. Repetir com duas instâncias simuladas e conferir que o resultado não confia em dados de cliente.
5. Demonstrar explicitamente que revogação após COMMIT e durante streaming continua **NÃO RESOLVIDA**, pois não existe stream nem cliente SDK ativado. Não marcar esses cenários como PASS.
6. Não alterar código nem fazer commits, deploy ou acessos a Cloudflare, Render e Supabase de produção. Não modificar Android ou habilitar GC.

**Status: Etapa 22 IMPLEMENTADA PARA TESTES, NÃO HOMOLOGADA, SEM ROLLOUT.**
