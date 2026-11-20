# PESSOASAQUI — ETAPA 25: Listener dedicado de revogação R2 (STAGED)

## Base

Antigravity reportou **Etapa 24 aprovada como preparatória**: 9/9 testes outbox, 8/8 active registry, 8/8 stream gate, 8/8 read admission, 2/2 PG 18 real sem skip e 208/208 backend, 14 cenários PG isolado. A base `main` foi conferida no SHA `03af011a36f4d5431402e11e3c578fca09e72da5` antes dos novos commits. Ponto de restauração `backup/main-pre-r2-delivery-20261114`: `02b6a487ac8b3ad6c64adf6ce8f6887dbfb53bf9`.

## Mudanças criadas

`backend/src/r2RevocationListener.js`: fábrica `createR2RevocationListener` com início/parada EXPLÍCITOS, nenhum código automático de inicialização. Exige callback `connect` que deverá fornecer **pg.Client dedicado**, nunca client emprestado de `pg.Pool`. Faz `LISTEN pessoasaqui_r2_stream_revocation_v1`, verifica cada payload UUID consultando `r2_stream_revocation_events` (Etapa 24), e chama `registry.revokeMessage/revokePair` somente após checar outbox persistida.

Políticas locais: falha de SQL, perda do socket, notificação inválida, event ID ausente, excesso de fila e erro de reconciliação alteram `ready=false`, invocam **failClosed** e agendam retry exponencial com jitter. Sem streams locais ativos, ignora hints sem SELECT ao banco. Reconciliação é obrigatória e injetada para boot/reconnect e executada antes de `ready=true`, duas vezes para diminuir a janela de boot. Eventos durante inicialização são temporariamente armazenados e despachados após a reconciliação; fila limitada a 256.

`backend/test/r2RevocationListener.test.js`: nove testes com cliente PostgreSQL simulado, cobrindo início, notificação válida/errada, zero streams, payload inválido, evento ausente, reconexão, erro de reconciliação e opções inválidas. **Não executados ainda pelo ChatGPT/Antigravity.** Testes existentes da Etapa 24 não foram alterados.

## Contrato de segurança — REGRAS INEGOCIÁVEIS

1. `failClosed` deve **abortar sincronamente todos os downloads locais ativos**. Apenas alterar uma flag NÃO equivale a fail-closed. O gateway futuro tem de recusar novos streams sempre que `listener.ready !== true`, inclusive durante boot/reconnect.
2. `reconcile` deve validar **cada download ainda ativo contra dados autoritativos do PostgreSQL** e abortar aqueles com vínculo/mensagem inválidos. A Etapa 25 não implementa essa rotina — está agendada para a Etapa 26. `reconcile` vazio gera falsa sensação de proteção. **Sem rota habilitada.**
3. Notificações nunca são garantidas por `LISTEN/NOTIFY` durante desconexão, e `replayRecentRevocations` é limitado por janela/volume. Falhas ou lacunas exigem abortar todas as sessões locais, e não confiar somente no replay.
4. O registro local `activeStreamRegistry` não expõe enumeração das sessões ativas nem `abortAll` ainda; o gateway futuro precisará implementar um fail-closed real, com controle de concorrência em todas as instâncias.
5. A transação que revoga mensagem/conexão ainda NÃO grava o evento outbox (Etapa 24 criou função mas não integrou às rotas). Portanto, listener não equivale a revogação distribuída pronta.
6. A janela entre preflight, registro no Map e início da resposta HTTP permanece aberta. Usar fencing/recheck após registro e antes do primeiro byte; não bloquear transação SQL durante rede.
7. Não alterar configs Render/Supabase, não aplicar migração 012 em produção, não ativar endpoints/GC, não criar listener em `server.js` neste estágio. Não modificar Android.
8. `LISTEN/NOTIFY` em Supabase pode depender de privilégios/conexão direta/pooler; validar com PostgreSQL isolado e posteriormente com ambiente explicitamente autorizado antes do rollout.
9. Evitar logs de URLs presigned, segredos, payloads e identidade. A migração 012 só registra metadados técnicos.

## Antigravity — auditoria sem alterações

Confirmar `main` HEAD e restaurabilidade do checkpoint `backup/main-pre-r2-delivery-20261114`. Usar worktree isolada e preservar `app/build.gradle.kts`.

Testes:
```sh
node --test backend/test/r2RevocationListener.test.js
node --test backend/test/r2RevocationOutbox.test.js
node --test backend/test/r2ActiveStreamRegistry.test.js
node --test backend/test/r2StreamStartGate.test.js
node backend/run-tests.js
```
No PG 18 isolado `127.0.0.1:54333`, migrações 004–012, executar `PESSOASAQUI_TEST_PG_URL=... node --test backend/test/r2ReadAdmission.postgres.test.js` (**zero skips**). Além disso, usar duas conexões reais dedicadas para testar `LISTEN`, reconexão, notificações após COMMIT, rollback silencioso, descarte quando `activeCount===0`, payload UUID inválido, falha no SELECT, backpressure, perda do socket e replay no restart; conferir limite de consultas e crescimento de listeners. **Separar testes simulados de tests reais**.

Especialmente procurar: *stale events* durante boot, perda de notificação antes de registrar stream, erro em `failClosed` e reconciliação vazia, eventos no momento da transição `ready`, pool exhaustion, reconexão simultânea, listener duplicado, backlog e custos egress. Documentar lacunas mesmo quando os 9 testes unitários passam.

Antigravity NÃO corrige código, NÃO commita, NÃO executa deploy, NÃO acessa produção e NÃO altera Android.

**STATUS: Etapa 25 preparatória implementada, NÃO HOMOLOGADA, R2 desligado.**
