# PESSOASAQUI — ETAPA 30F: finalização pós-I/O e reagendamento GC R2 (STAGED / INERTE)

## Base homologada
Antigravity homologou Etapa 30E no SHA `efbae8e73a01cc8c1a52a201cd9d43b1f6005dde`: 38/38 unitários de componentes R2, 208/208 regressão backend, 2/2 testes PG18 sem skips, 16/16 cenários reais com migrações 004–016. Índice 016 eliminou linhas descartadas em join filter no cenário de 600 registros, porém **a latência média aumentou** de 0,227 ms para 0,297 ms; portanto NÃO afirmar ganho de tempo em toda carga. O índice ocupa 72 kB no ambiente testado.

Restauro `backup/main-pre-r2-delivery-20261114` SHA `02b6a487ac8b3ad6c64adf6ce8f6887dbfb53bf9` confirmado antes do primeiro commit, main estava no SHA 30E.

## Escopo implementado
- Novo `backend/src/r2DeleteOutcomeCoordinator.js`, **não integrado** a rotas ou worker:
  - `finalizeTrustedR2Delete`: exige sinal de sucesso interno do futuro adapter DeleteObject (não JSON Android), claimToken + generation + lease atual. Em UMA transação com locks, exige mídia DELETE_QUEUED, outbox PENDING/RETRY, receipt DELIVERED/EXPIRED e janela PUT selada e expirada. Define primeiro a outbox `DONE` (migração 007 exige isso), depois mídia `DELETED`, depois `r2_delete_claims.completed_at`. Qualquer falha em trigger/rowCount lança exceção para rollback total.
  - `scheduleFailedR2Delete`: revalida a mesma claim e registra outbox RETRY, attempts incrementado, `next_attempt_at` não anterior a `lease_until` + delay exponencial com jitter explícito 0–30 segundos; teto de delay 3600s. Não chama R2, não emite timer.
- Novo `backend/test/r2DeleteOutcomeCoordinator.test.js`: oito testes em mocks; **ainda NÃO executados pelo ChatGPT**.

## Bloqueadores de produção
1. **Nada nesta etapa chama DeleteObject nem valida uma confirmação do provedor R2.** O boolean `trustedStorageDeleteCompleted` só pode vir de callback interno de um adapter confiável; o contrato precisa ser vinculado ao resultado efetivo SDK e não pode ser passado de entrada HTTP.
2. **Não há worker físico** (nem concorrência de rede, timeout real, loop, scheduler, drain, graceful shutdown). Mesmo depois de concluir os módulos isolados, a Etapa 30 depende da integração real sob autorização expressa. Não ativar R2/GC, deploy ou migrações no Supabase de produção.
3. R2 e PostgreSQL NÃO compartilham COMMIT. Um DELETE concluído no storage pode preceder uma falha no DB; reexecução idempotente da mesma chave (PUT tombstone bloqueada) deve recuperar. Nunca aceitar completion de claim zumbi.
4. A finalização DB exige que o lease não tenha expirado. Um DELETE físico mais lento que o lease deve ser cancelado antes de concluir ou uma nova claim será necessária; os 90s atuais ainda exigem teste com SDK, tempo máximo, falhas e relógios reais.
5. A política de backoff por `attempts` está condicionada à claim ainda válida. Crash sem agendamento pode voltar após o lease; discovery 30E já evita hot loop enquanto o lease vigora. Investigar alertas de tarefas exauridas após 20 tentativas e tratamento administrativo.
6. **A Etapa 30 continua incompleta**: `recordCommittedRevocation` não integrado em `server.js` nem cancelamento de mensagens/`/api/connections/unmark`, autenticação HTTP ECDSA P-256, cliente R2 real, órfãos, CANCELLED e legadas pré-013, integração Android, teste de implantação controlado.
7. Testes unitários com injeção de SQL não validam trigger PostgreSQL 007. É imprescindível testar com DB18 real que a ordem outbox DONE → mídia DELETED → claim completed funciona; validar rollback se qualquer estágio falhar, inclusive conflito de token/generation e concorrência.
8. Identificar no backend legado chamadas existentes a `recordDeleteClaimCompleted`: não devem coexistir com a nova finalização sem coordenação; desenhar API única para o futuro worker.

## Auditoria Antigravity (somente diagnóstico)

Confirmar SHA da `main`, branch de backup, worktree isolada, preservar `app/build.gradle.kts` e todo código Android. PostgreSQL 18.1 isolado em 54333, migrações 004–016 (NENHUMA produção).

Executar:
```sh
node --test backend/test/r2DeleteOutcomeCoordinator.test.js
node --test backend/test/r2DeleteClaimLedger.test.js
node --test backend/test/r2DeleteCandidateDiscovery.test.js
node --test backend/test/r2DeletionAdmission.test.js
node --test backend/test/r2DeliveryFinalizer.test.js
node --test backend/test/r2PutRetirementFence.test.js
node backend/run-tests.js
```
Executar `backend/test/r2ReadAdmission.postgres.test.js` com `PESSOASAQUI_TEST_PG_URL` para PG18 local, zero skips.

Cenários em **PostgreSQL real**:
- Construir mídia DELETE_QUEUED, outbox PENDING/RETRY, receipt terminal e janela PUT selada/expirada, claim lease válido; exercitar `finalizeTrustedR2Delete` com confirmação simulada do storage. Verificar trigger 007 e estados finais DONE/DELETED/completed_at em 1 COMMIT, tombstone imutável.
- Simular ROLLBACK após DONE, após DELETED, na última UPDATE; exigir nenhum estado parcial. Claim stale/token antigo/generation antiga/lease expirado, receipt CANCELLED, ledger sem prazo, outbox ausente/DONE, mídia ainda ATTACHED → recusas fail-closed.
- Retry com vários counts, jitter 0 e 30, teto 3600+30, `next_attempt_at >= lease_until` e não seleção pela discovery antes do horário; restaurar elegibilidade após vencimento; 2–10 workers concorrentes e deadlocks/lock order.
- Simular crash entre DELETE remoto e finalização SQL **sem executar R2 real**; política de recuperação idempotente e limites.
- Validar nenhuma alteração fora de arquivos backend, nenhum endpoint/worker, nenhum deploy, nenhum DELETE.

Gerar `PESSOASAQUI_ETAPA30F_AUDITORIA_FINALIZACAO_GC_R2.md` com resultados de mocks e PG18 separados, falhas, recomendações de integração. Antigravity NÃO corrige, NÃO commita, NÃO altera `server.js`, NÃO acessa serviços de produção.

**Status: Etapa 30F IMPLEMENTADA PARA AUDITORIA, NÃO HOMOLOGADA. R2 e GC desativados em produção.**
