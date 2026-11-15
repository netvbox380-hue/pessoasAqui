# PessoasAqui — Etapa 10 / Orphan GC: inspeção segura, sem exclusão

**Status: PREPARATORY / FLAG OFF.** A aprovação da Etapa 9 valida a expiração de mensagens anexadas, mas NÃO homologa o ciclo completo de upload/download no Android. Não aplicar migrações em produção nem alterar configuração R2 ainda.

## Diferença entre dois relógios

- R2 Object Lifecycle conta desde a criação do objeto no bucket.
- TTL de entrega conta desde `e2ee_messages.accepted_at` no PostgreSQL.
- Com lifecycle atual de 2 dias, um objeto enviado antes do `attach` pode ser apagado enquanto a mensagem ainda está válida: **bloqueio de rollout**.
- Proposta a validar antes de ativar: lifecycle R2 de **7 dias apenas como safety net**, expurgo normal por recibo + outbox, e janela máxima de **2 horas entre `uploaded_at` e `attach`**. O prazo e a alteração Cloudflare dependem de homologação real e decisão operacional, não são executados por este commit.

## Etapa 10A implementada

`backend/src/r2OrphanInspector.js` consulta SOMENTE candidatos:
- estado `RESERVED` ou `UPLOADED`;
- idade mínima de 4h (configurável somente para aumentar);
- para `UPLOADED`, confirma também idade do `uploaded_at`;
- ausência de `e2ee_messages` e recibo terminal;
- lotes <= 100, ordenados, relógio PostgreSQL;
- validação de chave opaca e apresentação de agregados sem identificadores.

**ATENÇÃO:** a consulta é observacional e não bloqueia upload, associação nem concorrentes. **Um candidato NÃO é autorização para exclusão.** NÃO rodar DeleteObject, enfileirar jobs ou atualizar estados a partir desse resultado. Não há timer e `server.js` continua intacto.

## Requisitos antes de criar o worker de exclusão dos órfãos

1. **Lease de upload durável e geração/fencing por objeto:** um upload em andamento não pode ser apagado por rotina de GC. Expirar, invalidar e revalidar lease/generation no banco sob lock antes de qualquer S3 DeleteObject. Em particular, PUT iniciado antes da reserva expirar e finalizado depois de GC pode recriar objeto já deletado.
2. **Estado terminal específico de órfão:** `ORPHAN_QUEUED` com caminho autorizado `RESERVED|UPLOADED -> ORPHAN_QUEUED` na migração futura, bloqueando qualquer `markMediaUploaded` ou `attachMediaObject` a partir daí.
3. **Outbox separado de órfãos:** manter a proteção 006 para mensagens ENTREGUES/EXPIRADAS/CANCELADAS; órfãos não devem gerar recibo de mensagem falso nem desativar o gatilho. A futura fila de órfãos deve requerer estado `ORPHAN_QUEUED`, ausência de `e2ee_messages` e checagens transacionais de lease.
4. **Recovery de reinício do Render:** worker com `FOR UPDATE SKIP LOCKED`, lease de claim, tentativas exponenciais, indicador de conclusão e limpeza de resíduos; não usar memória como única fonte.
5. **Preservar E2EE/Modo A/Android legado:** GC é apenas para novos objetos do protocolo R2. Texto e BLE nunca entram na fila.
6. **Teste real de concorrência:** upload em progresso, cliente reinicia e tenta reattach, PUT conclui depois do timeout, 2 workers, queda de PostgreSQL, delete antes do ACK, objetos com data limite, regra Cloudflare efetiva.

## Critérios de aprovação

- 5 testes do inspetor + regressão backend sem falha.
- Teste SQL isolado demonstra que candidatos podem virar `ATTACHED` imediatamente após leitura, por isso a inspeção NÃO é autorização para delete.
- Migração de Orphan GC com lease e outbox independente validada antes de qualquer agendador.
- Modo R2 continua OFF até ajuste seguro do lifecycle, autorização HTTP/WS, Android persistência/ACK por mídia e testes reais de dois dispositivos.

**Proibido:** alteração da regra Cloudflare, Render deploy ou Supabase de produção sem etapa específica de homologação e aprovação.
