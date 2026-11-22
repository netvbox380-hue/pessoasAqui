# PESSOASAQUI — ETAPA 30L: PONTE DE REVOGAÇÃO TRANSACIONAL (PREPARE ONLY)

## Baseline 30K aprovada
Antigravity informou 30K homologada no commit `8d42793984ee1d4982d2701486671f222a3f689c`: 54/54 testes de ciclo R2, 208/208 backend, 2/2 PostgreSQL 18 e 20/20 cenários reais em ambiente isolado; sem chamadas R2 nem deploy. Antes desta implementação, `main` estava no SHA 30K. O ponto de restauração `backup/main-pre-r2-delivery-20261114` permanece em `02b6a487ac8b3ad6c64adf6ce8f6887dbfb53bf9`.

## Objetivo 30L
Avançar o bloqueador mais importante de integração: gravar o evento de revogação no **mesmo COMMIT** da exclusão de vínculo/cancelamento de mensagem, evitando stream de mídia continuar após revogação. Nesta etapa não alterar `server.js` nem ativar tabela 012 em produção: preparar contrato verificável antes da inserção na rota real.

## Implementação
- Novo `backend/src/r2StagedRevocationBridge.js` disponibiliza `recordPairRevocationInExistingTransaction` e `recordMessageRevocationInExistingTransaction`. Requer função `txQuery` vinculada à mesma transação SQL AUTORITATIVA, identidades canônicas de 16 hex uppercase e contagem de linhas alteradas. Falha fechada para contagem inválida.
- Insere evento `PAIR` ou `MESSAGE` via `recordCommittedRevocation` (Etapa 24), seguido de `pg_notify` no mesmo contexto de transação: PostgreSQL só entrega NOTIFY após COMMIT. Caso INSERT/NOTIFY falhe, a exceção deve abortar a transação do chamador.
- Sem alteração de vínculos por este módulo: a mutação deve ter acontecido antes e fornecer `deletedConnectionRows` e `cancelledMessageRows` obtidos dos próprios resultados DB, jamais números passados pelo Android.
- Sem mutação (ambas contagens zero): não publica evento. No futuro, se for necessário cancelar streams após unmark idempotente ou comportamento de reconnect, autenticação e reconciliação autoritativa da Etapa 30A devem fechar a lacuna.
- Testes `backend/test/r2StagedRevocationBridge.test.js` com 9 casos mocks, **não executados pelo ChatGPT**.

## Integração futura identificada
No `backend/src/server.js`, a rota existente `POST /api/connections/unmark` já possui `withTransaction`, `DELETE FROM connections`, `UPDATE e2ee_messages ... is_cancelled=TRUE` e atualização opcional de revisão bilateral. A integração real deve:
1. Continuar exigindo a autenticação existente `authorizeConnectionWrite`.
2. Usar `RETURNING` ou rowCount dos DELETE/UPDATE *dentro da mesma tx*, fornecendo os dois números ao bridge; preferir disparar o evento PAIR **após** a revogação e **antes** do COMMIT.
3. Compatibilizar com `advancePairRevision` e evitar efeitos em RAM antes de COMMIT. Não efetuar NOTIFY isolado fora da transação.
4. Como migração 012 ainda não existe no Supabase produção, qualquer import/uso no `server.js` agora poderia fazer a rota unmark falhar. **Por isso esta etapa não faz ligação com a rota nem altera produção**.
5. Auditar `purgePendingMessagesBetween` e outras rotas de cancelamento, sem duplicação de eventos ou marcação indevida de recibos.
6. Validar integrações de LISTEN/streaming com revogação de ambas as identidades e reconciliação após COMMIT, incluindo múltiplas instâncias.

## Bloqueios restantes
Ainda faltam integração real no `server.js`, gates de schema/migração e rollout, endpoints de mídia P-256, retenção órfãos/CANCELLED/legados, revisão das migrações 004–016 e AWS SDK/Smithy no pacote, Render e testes com R2 descartável. Nenhuma exclusão R2 física foi autorizada. Android e Play Store não alterados.

## Auditoria 30L — Antigravity só diagnostica
Confirmar HEAD da main e backup, worktree isolada, PostgreSQL 18.1 porta 54333 com migrações 004–016. Não acessar infra de produção.
Executar:
```sh
node --test backend/test/r2StagedRevocationBridge.test.js
node --test backend/test/r2RevocationOutbox.test.js
node --test backend/test/r2RevocationListener.test.js
node --test backend/test/r2StagedGcScheduler.test.js
node --test backend/test/r2StagedGcBatch.test.js
node backend/run-tests.js
node --test backend/test/r2ReadAdmission.postgres.test.js
```
Observar que nomes de testes existentes devem ser conferidos antes da execução; se não existirem, registrar e executar testes reais disponíveis, sem omitir explicação. Testar PostgreSQL REAL: (a) DELETE de vínculo + UPDATE mensagens + bridge dentro de uma transação, evento/notify apenas após COMMIT; (b) ROLLBACK por falha de evento/notify recupera dados; (c) DELETE/UPDATE zero mudanças não gera evento; (d) conflitos/transações concorrentes; (e) listener 30A recebe evento e revoga streams; (f) pair mutação com 1/2 vínculos, mensagens entregues permanecem; (g) evento message cancelado com rowCount 1, não evento se 0; (h) idempotência, reconnect e ordem NOTIFY/COMMIT; (i) locks/revisionamento e proteção contra autorizações do cliente; (j) nenhuma regressão, nenhum segredo em logs.
Auditar separadamente a lacuna de ativação em `server.js` e os passos de migração segura, sem alterar código. Entregar `PESSOASAQUI_ETAPA30L_AUDITORIA_REVOGACAO_TRANSACIONAL_R2.md`. Antigravity apenas diagnostica, não corrige, não commita, não faz deploy nem modifica Android.

**ETAPA 30L IMPLEMENTADA, AGUARDANDO HOMOLOGAÇÃO. ETAPA 30 INCOMPLETA.**
