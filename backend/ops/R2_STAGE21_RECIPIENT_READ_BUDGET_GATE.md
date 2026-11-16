# PESSOASAQUI — ETAPA 21: admissão autenticada de downloads e limite SQL (STAGED)

**Base da auditoria:** Etapa 20, commit 80bb682, aprovada pelo Antigravity com 8/8 testes de autorização, 9/9 create-only, 8/8 auth, 13/13 integridade, 8/8 admission e 208/208 regressão; 14 cenários em PostgreSQL isolado. Esses resultados **não** equivalem à ativação real de downloads. A Etapa 21 foi criada agora e **ainda não foi homologada**.

## O que foi preparado

- `backend/src/r2ReadAdmission.js`: função `admitAuthenticatedR2Read()`, que combina em **uma única transação**:
  1. Autenticação ECDSA P-256 e anti-replay `R2_READ` com chave vinculada no PostgreSQL (Etapa 15);
  2. verificação bilateral `connections` sob lock;
  3. autorização específica do objeto + mensagem para o destinatário, com verificação de expiração, cancelamento e recibo terminal (Etapa 20);
  4. débito atômico de quota de leitura do destinatário, antes de qualquer acesso ao R2.
- `backend/migrations/011_r2_read_budget_PREPARE_ONLY.sql`: tabela `r2_read_daily_budget`, segregada das quotas de **verificação de upload** da Etapa 18. Usa dia UTC autoritativo do PostgreSQL, RLS e remoção de privilégios públicos. **Não aplicar na produção.**
- Limites temporários para homologação: máximo **24 solicitações de download/dia** e **300 MiB/dia por identidade**, com arquivo máximo de **25 MiB**. Esses valores não foram aprovados para produção e precisam ser calibrados para mídia de uso real.
- `backend/test/r2ReadAdmission.test.js`: 8 testes novos sobre assinatura, replay, conexão unilateral, objeto cancelado/terminal, quota, falha de banco e retorno sem URL.

## Propriedades e limites

- Retorno contém só metadados autorizados; **nenhuma presigned GET, chave bearer, conteúdo cifrado ou plaintext** é entregue. O módulo não toca em Cloudflare, Render ou Android.
- O argumento `identity` representa o ator alegado na prova: a camada `r2ActionAuth` o valida criptograficamente contra a chave persistida antes de autorizá-lo. O chamador não pode pular esse gate.
- Cobramos a tentativa (não o sucesso do download) para evitar uso ilimitado. Em requisições inválidas, não há débito; uma prova de identidade válida consome nonce no banco mesmo se a quota for excedida, pois a transação é concluída sem chamada externa.
- O uso de `UPDATE ... WHERE attempts < max AND reserved_bytes <= max - bytes` é atômico. Concorrência entre múltiplas instâncias Render não pode ultrapassar a quota da mesma identidade no mesmo dia.
- Essas quotas não substituem limites por IP, global, concorrência, proteção contra identidades Sybil ou defesa de custos reais de egress. Não há integração de rota ainda.

## Bloqueadores antes da entrega REAL

1. **Revogação após COMMIT**: a autorização SQL não revoga uma presigned GET previamente emitida. É necessário decidir e implementar uma política de gateway/proxy, TTL curto e rechecagens antes de enviar bytes. Se houver mídia já em trânsito, revogação imediata depende de cancelamento de stream controlado pelo backend.
2. **TOCTOU de objeto**: ETag não é hash autenticado; validar o SHA-256 do conteúdo cifrado durante a entrega, bloquear overwrite, e impedir DELETE→PUT com URL anterior ainda válida. Não ativar GC nem qualquer rotina de exclusão antes de isolar late PUT.
3. **ACK**: mensagem só pode ser marcada `DELIVERED` quando o Android comprovar persistência local íntegra; esta etapa não faz ACK.
4. **Relógio e 48h**: evitar que lifecycle de dois dias destrua arquivos antes do prazo medido de `accepted_at`; política R2 precisa aprovação separada.
5. **Integração de fluxo**: antes de iniciar GET, confirmar novamente vínculo e estado, adotar limite de bytes, deadline de conexão/stream, SDK transport timeout e limite de concorrência global. Sem erros que retornem dados de outra pessoa.
6. **Revogação de identidade**: banimento, chave recuperada/revinculada e incompatibilidades V2/V3 precisam ser exercitados com a prova P-256 antes de produção.

## Antigravity — testes isolados, diagnóstico sem correções

Confirmar HEAD main e ponto de restauração `backup/main-pre-r2-delivery-20261114`. Executar:

- `node --test backend/test/r2ReadAdmission.test.js`
- `node --test backend/test/r2RecipientReadAuth.test.js`
- `node --test backend/test/r2ActionAuth.test.js`
- `node --test backend/test/r2VerificationAdmission.test.js`
- `node --test backend/test/r2ObjectIntegrity.test.js`
- `node backend/run-tests.js`

Em PostgreSQL 18 isolado aplicar migrações 004–011 e verificar: múltiplas conexões e 2 instâncias concorrentes, limites exatos 24/300MiB, mudança do dia UTC, quota esgotada, assinatura falsa, replay, cancelamento, entrega e recibo terminal; simular revogação antes e depois do commit. Confirmar que o módulo não tem endpoint nem acesso externo e que a migração 011 permite somente o papel backend necessário.

**Antigravity não corrige, não commita, não implanta e não acessa Supabase/Render/R2 de produção.**

**R2 ROLLOUT PERMANECE BLOQUEADO.**
