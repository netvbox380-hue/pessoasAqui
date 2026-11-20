# PESSOASAQUI — ETAPA 24: NOTIFICAÇÃO TRANSACIONAL E OUTBOX DE REVOGAÇÃO R2 (STAGED)

## Base auditada

Antigravity confirmou a **Etapa 23**, HEAD `45cb1c086e8909288b8fe61784f113d455697efc`, em worktree separada: 8/8 testes de streams ativos, 8/8 de preflight, 8/8 de admissão, 2/2 de PostgreSQL 18 real sem skips, 208/208 regressão, 14 cenários operacionais. **Isso não comprova revogação distribuída:** o registro atual é um Map em um único processo.

**Ponto de restauração confirmado:** `backup/main-pre-r2-delivery-20261114` no commit `02b6a487ac8b3ad6c64adf6ce8f6887dbfb53bf9`. `main` conferida no commit `45cb1c0` antes das alterações.

## Implementação preparatória — NOVA ETAPA 24

1. `backend/migrations/012_r2_stream_revocation_events_PREPARE_ONLY.sql`: tabela durável `r2_stream_revocation_events`, evento UUID, `MESSAGE` ou `PAIR`, timestamps PostgreSQL, índice para reconciliação, CHECK de formato, RLS habilitado e acesso público removido. **Nenhuma migração foi aplicada na produção**.
2. `backend/src/r2RevocationOutbox.js`:
   - `recordCommittedRevocation({query,event,assertRevocationApplied:true})`: insere evento na tabela + `pg_notify` na **mesma transação do cancelamento de mensagem ou revogação de vínculo**. O `NOTIFY` é entregue no COMMIT, não no rollback. A flag é um contrato exclusivamente do código backend, **NÃO** prova verificável isolada de que a revogação ocorreu; o chamador futuro terá de garantir operação real/atomicidade.
   - `dispatchCommittedRevocation({query,registry,eventId})`: recebe UUID da notificação (hint não confiável), lê evento persistido via SELECT, valida o conteúdo e só então chama `registry.revokeMessage` ou `revokePair`. Não confia em JSON do canal.
   - `replayRecentRevocations`: busca eventos recentes com limite estrito e falha explícita se o conjunto está truncado. Não utiliza cursor de sequência, já que ordem de ID não é a ordem de commit de transações concorrentes.
3. `backend/test/r2RevocationOutbox.test.js`: testes novos de validação, notify transacional em mocks, idempotência, evento persistido, replay limitado e falha do banco. **Ainda NÃO executados** pelo ChatGPT.

## Pontos que AINDA BLOQUEIAM produção

- `LISTEN` **não é durável**: não recebe notificações ocorridas durante desconexão. Um consumidor real deve manter conexão **dedicada** (não conexão efêmera de pool), reagir a queda de socket e **reconciliar todos os streams ativos contra o PostgreSQL autoritativo** no início/reconexão e em caso de lacuna. Eventos da outbox são ajuda, não substituem rechecagem de estado. Se reconciliar falhar, cancelar streams (fail-closed).
- Nenhum listener, triggers, publisher nas rotas de `unmark`/`cancel`/`confirmR2Delivery`, worker ou gateway HTTP foi conectado. A função de outbox apenas **prepara** operação com query **injetada**. Os futuros pontos de mutação do vínculo e da mensagem terão de publicar dentro da mesma transação que efetua o UPDATE/DELETE — não em um POST separado.
- Uma requisição legítima já admitida e revalidada pode ser registrada durante o COMMIT do cancelamento: deve existir fencing/sessão ou **revalidação obrigatória após registro e antes do primeiro byte**, com protocolo que não perca revogações. Sem isso não declarar garantia de revogação imediata.
- Escuta e replay distribuídos precisam de testes reais no PostgreSQL 18: duas conexões `LISTEN` dedicadas, múltiplas instâncias simuladas, ordem commit/rollback, eventos duplicados, desconexão e recuperação, backlog saturado, atraso de `NOTIFY` e cancelamento de stream ativo.
- A outbox não implementa retenção/limpeza automática. Não cortar eventos antes da duração máxima de streams e margem de reconciliação; **não permitir R2 GC**. A leitura periódica agressiva de outbox pode gerar custos Supabase/egress. Preferir `LISTEN/NOTIFY` e reconciliação no boot/reconnect/erros, com limites estritos.
- Um `NOTIFY` não é canal de autenticação: payload deve conter apenas UUID e ser validado contra evento persistido. Evitar IDs pessoais, URL R2, plaintext, segredos e dados sensíveis nos logs.
- Antes de produção há também riscos de DELETE→PUT tardio, persistência antes do ACK Android, integridade de streaming, transporte S3 com timeouts, quotas globais e rastreamento do estado da mídia.
- **Nenhuma rota em `server.js` alterada, nenhum acesso aos serviços R2/Render/Supabase de produção, nenhuma mudança no Android, nenhuma URL emitida.**

## Antigravity — AUDITORIA SEM CORREÇÃO

Confirmar HEAD da `main`, SHA completo do checkpoint e worktree isolada com Android local intocado.

Executar:
```sh
node --test backend/test/r2RevocationOutbox.test.js
node --test backend/test/r2ActiveStreamRegistry.test.js
node --test backend/test/r2StreamStartGate.test.js
node --test backend/test/r2ReadAdmission.test.js
node backend/run-tests.js
```
Com `PESSOASAQUI_TEST_PG_URL` apontando exclusivamente para PG 18 isolado na porta 54333, executar `node --test backend/test/r2ReadAdmission.postgres.test.js` com **zero skips**.

Aplicar migração 012 **somente no banco isolado**. Validar SQL PostgreSQL real, inserção de evento + `pg_notify` na mesma transação, visibilidade somente após COMMIT, nenhuma notificação ao ROLLBACK, `LISTEN` em duas sessões, replay de mensagens acumuladas, duplicidade idempotente, queda/reconexão, limite de backlog e custo de tráfego. **Não há listener integrado no código**: relatar esse bloqueador, não simular que já existe.

Antigravity **não altera arquivos, não commita, não faz deploy, não modifica Supabase/Render/Cloudflare/Android e não ativa rotas ou GC**.

**STATUS:** ETAPA 24 STAGED / AINDA NÃO HOMOLOGADA / ROLLOUT R2 BLOQUEADO.
