# PESSOASAQUI — ETAPA 30S: QUOTA DIÁRIA DE RESERVAS R2 (STAGED / INERTE)

## Baseline homologada
O Antigravity reportou 30R homologada no SHA `157540ff793239c382a03e21018d89f34e64fb5f`: 208/208 regressão, 17/17 novos testes de reserva, 49/49 testes correlatos, 2/2 PostgreSQL e 20/20 cenários Express/PG18.1 isolados. Identificou como bloqueador de rollout a AUSÊNCIA de quota diária de `R2_RESERVE`, que poderia permitir crescimento ilimitado de linhas/índices e abuso de armazenamento. Antes da implementação, `main` era idêntica a esse SHA; backup `backup/main-pre-r2-delivery-20261114` preservado no SHA `02b6a487ac8b3ad6c64adf6ce8f6887dbfb53bf9`.

## Implementação da Etapa 30S
1. Migração **`backend/migrations/017_r2_reserve_daily_budget_PREPARE_ONLY.sql`** (NÃO aplicar Supabase produção): cria `public.r2_reserve_daily_budget`, chave composta `(identity_id, day_utc)`, identidade de 16 hex maiúsculos, contagem inteira com CHECK 0–24, RLS ativado, revoga privilégios públicos e papéis anon/authenticated. Não cria worker, cron ou trigger; requer verificação posterior das permissões do papel backend, RLS e checksum em ambiente de rollout.
2. `backend/src/r2ReserveDailyBudget.js`: `chargeNewR2Reservation({tx,authenticatedIdentity})` usa ÚNICO INSERT...ON CONFLICT DO UPDATE...WHERE reservations < 24 RETURNING. PostgreSQL controla a data UTC pelo relógio do DB e serializa a concorrência via índice único/row lock. Limite inicial conservador: **24 novas reservas por identidade por dia UTC**; não é parâmetro vindo do Android. Em quota esgotada, **LANÇA** erro sanitizado `R2_RESERVE_DAILY_QUOTA_EXCEEDED`; é imperativo que a transação do chamador faça ROLLBACK integral. Sem fallback para RAM.
3. `backend/src/r2StagedReserveAdmission.js`: conecta quota no callback do gate de prova ECDSA P-256 `executeAuthenticatedR2Action`, dentro da MESMA transação de nonce e `reserveLeasedMediaObject`. Para reserva NOVA, cobra a quota depois do INSERT, antes do COMMIT. Se quota negada, exceção causa ROLLBACK de INSERT da mídia e nonce, retorna resultado HTTP 429. Para DUPLICATA idempotente, não cobra novamente nem renova lease. Falhas de DB diferentes de quota propagam (handler HTTP as mapeia para 503).
4. `backend/test/r2ReserveDailyBudget.test.js`: cinco testes da parametrização SQL, escopo UTC, quota, identidade e falhas. `r2StagedReserveAdmission.test.js`: adiciona três testes, ajusta mock anterior para contemplar quota. `r2StagedReserveHttpHandler.test.js`: adiciona cenário HTTP 429. **Testes não foram executados pelo ChatGPT; Antigravity deve executar antes de homologar.**
5. Nenhum import em `server.js`; handler R2_RESERVE permanece NÃO REGISTRADO. Não há PUT URL, SDK, Cloudflare R2 físico, alteração Android ou migração de produção.

## Riscos importantes
- A quota cobre SOMENTE **novas reservas com INSERT efetivo**, não tentativas inválidas nem duplicatas. Ainda são necessários rate limiting HTTP por identidade/IP, limites de corpo, proteções DoS, custo de armazenamento, número de objetos ativos, GC de órfãos e limites cumulativos de bytes. O limite 24 é escolha preparatória, não decisão de política definitiva.
- A transação da prova P-256 pode segurar locks de conexão até a conclusão de quota; medir conflito e deadlocks com 2/10/50 trabalhadores, multiinstância e UNMARK. Se `r2_reserve_daily_budget` não existir, o INSERT falha **fechado** e a reserva é revertida. O backend real não importa esse caminho, evitando quebrar produção.
- A quota é cobrada **depois** do INSERT da mídia, mas antes de COMMIT, no MESMO withTransaction; o rollback deve reverter nonce, mídia e tentativa de cobrança. É proibido engolir exceção dentro do callback action. Um mock de transação NÃO comprova rollback físico.
- A migração 017 requer auditoria de idempotência, RLS/roles e plano de backup. As migrações preparatórias 004–017 NÃO DEVEM ser aplicadas no Supabase de produção sem autorização explícita do proprietário.
- Chave opaca proposta no corpo é assinada; falta política de emissão, quotas por bytes, endpoint SIGV4 create-only PUT, CONFIRM, ATTACH, schema/readiness/kill-switch, verificação real R2 em infraestrutura descartável e integração Android. Etapa 30 segue INCOMPLETA.

## Antigravity — homologação obrigatória 30S (diagnóstico apenas)
Confirmar HEAD na main, backup e worktree isolada; PostgreSQL 18.1 local `127.0.0.1:54333`. Aplicar migrações 004–017 SOMENTE no banco de teste descartável. Preservar Android, `app/build.gradle.kts`, logos e assinatura Play.

Executar:
```sh
node --test backend/test/r2ReserveDailyBudget.test.js
node --test backend/test/r2StagedReserveAdmission.test.js
node --test backend/test/r2StagedReserveHttpHandler.test.js
node --test backend/test/r2ActionAuth.test.js
node --test backend/test/r2UploadLeaseRegistry.test.js
node --test backend/test/r2CreateOnlyPut.test.js
node --test backend/test/r2PutRetirementFence.test.js
node backend/run-tests.js
node --test backend/test/r2ReadAdmission.postgres.test.js
```
Critério de aprovação: regressão geral 208/208, todos os novos testes, zero falhas e zero skips. Testes com PG18 REAL + Express isolado, chave ECDSA P-256 ativa e duas conexões bilaterais, exigem:
1. 24 novas reservas no mesmo dia UTC passam; 25ª retorna 429; nenhuma mídia/nonce residual da tentativa recusada. No dia UTC seguinte nova reserva pode passar. Contagem por identidade, não global.
2. Duas identidades concorrentes não compartilham quota; 2, 10 e 50 concorrentes da MESMA identidade com mensagens/chaves distintas não ultrapassam 24 nem criam media rows além do limite.
3. Repetição idempotente da mesma reserva com novo nonce não consome quota adicional; lease não é renovado. Repetição com nonce já usado recusa 409.
4. Falha SQL do UPSERT, falha no COMMIT, DROP/ausência da tabela 017, lock timeout e desconexão PG causam rollback completo e HTTP 503; nenhum fallback RAM.
5. Cancelamento de vínculo entre assinatura/registro, interleaving mark/unmark, lock e revogação: nenhum bypass da condição bilateral; sessões previamente revogadas não reservam mídia.
6. Identidade pública falsa, chave rotacionada, assinatura alterada, corpo com campo extra, ID errado e tentativa de alterar limite por JSON: nenhum INSERT quota/mídia.
7. Egress: monitorar que esta etapa não faz chamadas R2, não emite URL presigned e não habilita leitura/gravação real. Revisar RLS, grants e integridade dos índices da migração 017. Não alterar produção.
8. Certificar que os 208 testes do backend continuam aprovados, inclusive `connectionRevocation.test.js` (VM legacy).
9. Medir crescimento da tabela quota, locks, filas e efeitos do dia UTC; registrar abuso por duplicatas sem consumo da quota e recomendações de rate limit HTTP adicional.

Relatório obrigatório: `PESSOASAQUI_ETAPA30S_AUDITORIA_QUOTA_RESERVA_R2.md`.

Antigravity NÃO modifica código, NÃO comita, NÃO instala dependências na main, NÃO acessa Render, Supabase ou Cloudflare de produção, NÃO ativa rotas e NÃO modifica Android.

**ETAPA 30S IMPLEMENTADA, NÃO HOMOLOGADA; ETAPA 30 AINDA INCOMPLETA.**
