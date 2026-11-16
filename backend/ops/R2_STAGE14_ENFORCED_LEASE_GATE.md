# PessoasAqui — Etapa 14: eliminar caminhos R2 sem lease (STAGED)

**Base auditada:** etapa 13 em `e2a3c77`, 6/6 testes de associação, 10/10 do módulo antigo, 208/208 de regressão e 15/15 PostgreSQL 18 isolado. **Não confundir esses resultados anteriores com execução dos testes da etapa 14.**

## Correção na main

- `backend/src/r2MediaRegistry.js`: `attachMediaObject` agora **sempre** exige `uploadGeneration === 1`, `upload_generation` do banco correspondente, lease persistido, `uploaded_at` presente e, para `UPLOADED`, janela máxima de 2h, verificada dentro da transação com lock na linha.
- O parâmetro externo `requireLeasedUpload` foi **removido**. Passá-lo como campo adicional de JSON não altera o contrato; nenhuma opção permite desligar a verificação de lease.
- As funções inseguras `reserveMediaObject` e `markMediaUploaded` foram **removidas** de `r2MediaRegistry.js`. Novas reservas e confirmações devem passar por `reserveLeasedMediaObject` e `markLeasedMediaUploaded` em `backend/src/r2UploadLeaseRegistry.js`.
- `attachLeasedMediaObject` continua como alias sem possibilidade de relaxar a validação; `attachMediaObject` permanece somente por compatibilidade de nome com a suíte, **com as mesmas restrições obrigatórias**.
- Os testes `r2MediaRegistry.test.js` e `r2MediaAttach.test.js` foram ajustados para representar o contrato da migração 008. O servidor ativo não importa os módulos R2.

## Restrições e riscos restantes

1. A migração 008 foi executada apenas no PostgreSQL isolado. O backend de produção não pode importar estes módulos antes da aplicação aprovada das migrações.
2. Uma consulta `SELECT ... FOR UPDATE` com expressão de `clock_timestamp()` deve ser validada em corrida real, em torno do exato limite de duas horas. Não usar o Android como relógio autoritativo.
3. **Autenticação ainda não está conectada**: `authenticatedSenderIdentity` é um argumento de contexto; somente uma rota que verifique assinatura e anti-replay pode fornecê-lo.
4. O servidor ainda não compara a mídia criptografada de fato no Cloudflare R2 com SHA-256 e tamanho. A consulta de banco não comprova que um blob existe ou é íntegro.
5. SQL lease e URL de upload pré-assinada continuam com a possibilidade de PUT tardio. R2 lifecycle proposto 7 dias e reconciliação não foram ativados. GC destrutivo permanece proibido.
6. `requireLeasedUpload` não deve reaparecer em rotas HTTP/WS. Não habilitar versões anteriores dos métodos de upload no novo protocolo.
7. Garantir que nenhum arquivo de teste ou módulo ainda importe as APIs removidas; executar testes estáticos e regressão. Verificar scripts e fontes fora de `backend/src`.

## Homologação Antigravity

- `node --test backend/test/r2MediaRegistry.test.js`
- `node --test backend/test/r2MediaAttach.test.js`
- `node --test backend/test/r2LeasedMediaAttach.test.js`
- `node --test backend/test/r2UploadLeaseRegistry.test.js`
- `node backend/run-tests.js`
- PostgreSQL 18 isolado, migrações 004–008: reserva sem lease, generation errada, upload antigo, `ORPHAN_QUEUED`, retries `ATTACHED`, concorrência `attach`/GC, sem contrato seletor.
- Testar explicitamente chamada direta a `attachMediaObject` com `requireLeasedUpload:false` e `uploadGeneration:undefined`: ambas devem falhar ou usar verificações obrigatórias, **nunca anexar**.
- Verificar que `reserveMediaObject` e `markMediaUploaded` não são exportadas e que as rotas de produção não importam módulos R2.
- Somente testes, sem commits pelo Antigravity, sem contatos com produção.

**Produção Render/Supabase/R2/Android inalterada.** O rollout segue BLOQUEADO.
