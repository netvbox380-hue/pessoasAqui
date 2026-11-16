# PessoasAqui / Etapa 11 — Migração 008 para GC de órfãos (PREPARE ONLY)

## Implementado e desligado

A migração `008_r2_orphan_gc_PREPARE_ONLY.sql` prepara **somente** a estrutura relacional para o futuro garbage collector de mídias órfãs. O código atual `server.js`, Android e o outbox de mensagens entregues/expiradas permanecem sem alteração.

Alterações propostas:
- `upload_lease_expires_at` nulo por padrão: reservas legadas não são aptas à coleta sem uma decisão explícita;
- `upload_generation` como marcador numérico preliminar de geração; **ainda não é prova de fencing do PUT remoto**;
- estados `ORPHAN_QUEUED` e `ORPHAN_DELETED` separados dos terminais de mensagem;
- `message_r2_orphan_outbox` exclusivo para órfãos, com RLS, retries e limites;
- trigger de monotonicidade da migração 007 preservado e ampliado;
- trigger independente que só permite enfileirar mídia ORPHAN_QUEUED, com mesmo objeto, ID e geração, sem mensagem nem recibo;
- condições de quarentena de 4 horas, lease encerrado há 3 horas, ausência de mensagem/recibo.

**Não implementar worker S3 / DeleteObject / agendador nesta fase.** A migração só será executada em PostgreSQL isolado após auditoria. Não alterar banco em produção.

## Bloqueador crítico de segurança: PUT tardio

Um timestamp `upload_lease_expires_at` no PostgreSQL **não revoga** automaticamente uma URL de PUT R2 já emitida. Um cliente pode ter iniciado upload antes do vencimento e a Cloudflare pode concluir o PUT depois de o backend ter considerado a reserva órfã. Uma operação DELETE seguida desse PUT pode recriar o objeto. Apenas esperar 4 horas não prova impossibilidade absoluta.

Antes de permitir o GC destrutivo, implementar e auditar uma destas garantias:
1. Upload mediado pelo backend com autorização verificável em todos os envios, bloqueando PUT após invalidar a reserva; verificar corpo, timeout, conexão e conclusão real; ou
2. Chaves imutáveis e únicas por tentativa com registro/geração, tempos máximos efetivos de PUT confirmados na Cloudflare, janela de quarentena comprovada, e revarredura/reconciliação de objetos que possam ter sido recriados. **Esta alternativa não dá garantias fortes sem limite de vida de request comprovado.**

As funções antigas `reserveMediaObject` e `markMediaUploaded` ainda **não** criam nem fiscalizam leases. Não ativar nem conceder privilégios de GC.

## Checklist técnico para Antigravity (sem modificar código)

1. Aplicar migrações 004, 005, 006, 007 e 008 em PostgreSQL **18 isolado**. Verificar sintaxe PL/pgSQL, constraints, FK, RLS, trigger de exclusão comum 006 e novo trigger de órfãos.
2. Verificar fluxo de texto sem alterações, `ATTACHED -> DELETE_QUEUED -> DELETED`, recibos imutáveis e ACK/expiração anteriores.
3. `RESERVED/UPLOADED -> ORPHAN_QUEUED` somente se lease não-nulo encerrado há >=3h, reserva >=4h e upload concluído >=4h (quando aplicável). Testar ausência de `e2ee_messages` e recibo.
4. Testar também proibição de: `ATTACHED -> ORPHAN_QUEUED`, `ORPHAN_QUEUED -> ATTACHED`, `ORPHAN_QUEUED -> DELETED` direto, `ORPHAN_QUEUED -> ORPHAN_DELETED` sem outbox DONE, mutação de objeto/chave/identidades e inserção na outbox com ID/geração incorretos.
5. Rodar com 2 conexões PostgreSQL: lock de `attach` vs GC, inserção de mensagem vs transição para órfão, transação do DELETE com fila, revogação e atualização da reserva.
6. Testar se constraints e triggers suportam ambas as linhas de origem: `RESERVED` sem hash e `UPLOADED` com hash.
7. Validar RLS com **papel específico não proprietário** no PostgreSQL; verificar grants de backend e bloqueio anon/authenticated.
8. Verificar exceções `23514` sem efeitos parciais e falhas com rollback.
9. Auditar concorrência real entre PUT R2, expiração do lease e DeleteObject. Documentar que não pode ser reproduzida só com PostgreSQL simulado.
10. Executar todas as suítes backend existentes e testes isolados anteriores.

**Gates pendentes:** lifecycle R2 2d -> proposta 7d ainda não aplicada; Android R2 + ACK de persistência; controle de PUT tardio; GC worker; rotas de autorização assinada; observabilidade; rollout em staging e aprovação da produção.

Ponto de restauração: `backup/main-pre-r2-delivery-20261114`.
