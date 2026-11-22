# PESSOASAQUI — ETAPA 30M: INTEGRAÇÃO DA REVOGAÇÃO EM /api/connections/unmark (GUARDED / OFF BY DEFAULT)

## Baseline
Antigravity relatou homologação da Etapa 30L no SHA `c758018449748324e69d89c380d4d034e2083993`: 47/47 testes R2, 208/208 regressão, 2/2 PG18, 20/20 cenários PostgreSQL 18.1 isolado. Reportou que a migração 012 não está instalada em Supabase de produção, portanto ativar incondicionalmente `recordCommittedRevocation` quebraria o unmark com erro 42P01. Antes das alterações, `main` era idêntica ao SHA 30L; checkpoint `backup/main-pre-r2-delivery-20261114` está no SHA `02b6a487ac8b3ad6c64adf6ce8f6887dbfb53bf9`.

## Código implementado
- `backend/src/server.js`: a rota existente `POST /api/connections/unmark` conserva a autorização `authorizeConnectionWrite`, advisory lock, exclusão bilateral, cancelamento SOMENTE de mensagens pendentes, revisão e efeitos em RAM após COMMIT.
- Novo bloqueio de integração: `r2RevocationBridgeEnabled()` exige simultaneamente as variáveis de servidor `PESSOASAQUI_R2_REVOCATION_TX_ENABLED=true` E `PESSOASAQUI_R2_STAGE_ROLLOUT_APPROVED=true`. **Ambas ausentes/desativadas por padrão. NENHUMA delas deve ser configurada na produção antes de aprovação formal do rollout, backup e aplicação controlada das migrações.**
- Com flags desativadas: a rota não consulta tabelas R2, não exige migração 012, não emite pg_notify e mantém exatamente os fluxos SQL já existentes, salvo captura não destrutiva de `rowCount`.
- Com flags ativadas: **antes de alterar qualquer vínculo**, confirma na MESMA transação `to_regclass('public.r2_stream_revocation_events') IS NOT NULL`. Se não existir, lança erro, rollback e responde 503: não cancela parcialmente conexões.
- Dentro da transação, captura `delRes.rowCount` após DELETE e `cancelRes.rowCount` após UPDATE. Passa ambos ao `recordGuardedUnmarkRevocation` (novo `backend/src/r2UnmarkRevocationGate.js`), que repete check de schema e aciona o `recordPairRevocationInExistingTransaction` da 30L, antes do `advancePairRevision` e do COMMIT.
- Se as contagens forem 0/0, nenhum evento é inserido, replicando a semântica homologada na 30L. Se INSERT ou NOTIFY falhar, a exceção aborta a mesma transação.
- Novo teste `backend/test/r2UnmarkRevocationGate.test.js`: 8 cenários MOCK para flags, schema presente/ausente, contagens, erro INSERT/NOTIFY e preservação do comportamento inerte. **Não executados pelo ChatGPT**.
- Não há nova rota R2, import na inicialização do servidor, worker, SDK, credenciais, alteração Android ou migração em produção.

## Riscos a auditar antes de ativar
1. O novo feature flag é somente uma barreira de rollout. A existência da tabela não comprova integralmente todos os triggers, permissões/RLS, versão, conexão dedicada ou readiness do LISTEN. A migração 012 continua inativa em produção. Validar todos os requisitos antes de qualquer migração/deploy.
2. **Mudar `server.js` significa que eventual deploy do HEAD já leva código novo, embora flags desativadas deixem as revogações R2 inertes.** Exigir teste de regressão da rota real e nenhuma alteração em produção antes da autorização.
3. A rota preserva `revokedConnections` em RAM e revisionamento. Evento de revogação 30M não significa que o R2 reader esteja ativo: ainda faltam listener/runtime HTTP, rotas de mídia autenticadas ECDSA P-256 e ciclo de produção.
4. Eventos 0/0 não são emitidos; múltiplas instâncias devem revalidar vínculos por PostgreSQL e nunca confiar somente em NOTIFY para fechar streams. Detectar possível race de reconnect e exigência de cancelar streams na ausência de mudanças.
5. Outros caminhos de cancelamento (`purgePendingMessagesBetween`, revogação por moderação, mensagem individual) ainda exigem a mesma integração autoritativa; não declarar revogação do aplicativo completa.
6. A assinatura exigida por `authorizeConnectionWrite` deve permanecer obrigatória. A auditoria deve testar que flags de R2 não criam bypass de autenticação.
7. Feature flags no Render devem permanecer **ausentes/false**. A migração `012_r2_stream_revocation_events_PREPARE_ONLY.sql` só poderá ser considerada em implantação auditada após backup separado de produção. Não aplicar agora.
8. O pacote AWS SDK e a configuração R2 real também continuam inativos. Etapa 30 ainda incompleta.

## Auditoria Antigravity — apenas diagnóstico
Confirmar HEAD 30M, checkpoint e worktree isolada, PostgreSQL 18.1 em `127.0.0.1:54333` com migrações 004–016. Não modificar produção, Android, gradle, logos ou server.js.
Executar:
```sh
node --test backend/test/r2UnmarkRevocationGate.test.js
node --test backend/test/r2StagedRevocationBridge.test.js
node --test backend/test/r2RevocationOutbox.test.js
node --test backend/test/r2RevocationListener.test.js
node backend/run-tests.js
node --test backend/test/r2ReadAdmission.postgres.test.js
```
Testes reais devem inicializar Express no ambiente isolado e acessar `POST /api/connections/unmark` com **prova de identidade válida** em cada cenário. Nunca contornar autenticação existente. Cenários:
1. flags ausentes: unmark com esquema legado sem tabela 012, comportamento idêntico, sem 42P01 nem consultas R2.
2. somente um flag true / strings não exatas: nenhum acesso R2.
3. ambos true + tabela ausente: 503 e **nenhum DELETE/UPDATE**, checar rollback.
4. ambos true + tabela 012 presente: UNMARK autorizado, delRes/cancelRes reais, PAIR insert+NOTIFY mesma transação, revisionamento e commit, ambos os sentidos.
5. INSERT falhou, NOTIFY falhou, evento 0/0 e rollback forçado.
6. mensagens já entregues preservadas, pendentes canceladas.
7. contas não autorizadas recebem 401/403 antes de SQL.
8. concorrência mark/unmark com advisory lock e listener de revogação; zero deadlocks.
9. cliente LISTEN recebe evento após COMMIT e nada após ROLLBACK.
10. verificação da integridade do baseline quando flags desativadas.
11. reinício do processo, envs persistentes e nenhuma ativação automática de GC/S3.
12. simular migração 012 ausente com flags true para provar fail-closed sem perda de vínculo.
Separar testes mock e PostgreSQL real; relatar literalmente quaisquer diferenças. Não autorizar R2 real, Render, Supabase produção, deploy ou edição da main.

Entregar `PESSOASAQUI_ETAPA30M_AUDITORIA_UNMARK_REVOGACAO_GUARDED_R2.md`. Antigravity NÃO modifica código, NÃO commita, NÃO ativa flags, NÃO executa deploy.

**STATUS: ETAPA 30M IMPLEMENTADA, NÃO HOMOLOGADA. REVOGAÇÃO R2 DESATIVADA EM PRODUÇÃO. ETAPA 30 INCOMPLETA.**
