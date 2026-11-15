# PessoasAqui — Etapa 9 / Expiração de mídias R2 — portões antes de ativar

**Estado:** apenas preparação e testes, sem integração ao `server.js`, sem workers, sem credenciais e sem alterações em produção.

## Implementado, NÃO ativado

`expireR2Message()` no `backend/src/r2DeliveryFinalizer.js` bloqueia mídia, recibo e mensagem em ordem consistente com `confirmR2Delivery()`. Após verificar expiração no PostgreSQL, tenta criar no mesmo commit: recibo terminal `EXPIRED`, aviso sem conteúdo, estado `DELETE_QUEUED`, outbox de exclusão e remoção do payload do banco. Reconfere a data no `DELETE` final. Falhas lançam exceção para rollback via `withTransaction`.

`backend/test/r2MediaExpiration.test.js` contém testes unitários de limites, idempotência, estados indevidos, falhas de banco e sequência das operações. **Nenhum destes testes foi executado automaticamente por esta alteração**.

## Riscos que NÃO podem ser ignorados

1. **Lifecycle Cloudflare em 2 dias contados do upload, não da aceitação da mensagem.** Se um objeto permanecer em `UPLOADED` durante horas e só depois for associado à mensagem, a exclusão automática do R2 pode chegar **antes do prazo de entrega de 48h**, quebrando downloads. Não ativar protocolo de mídias até resolver a divergência. Opções para decisão/validação: regra de lifecycle com margem maior que atraso máximo de associação + 48h, com worker de expiração autoritativo e métricas; ou exigir associação imediatamente após upload e usar janela de segurança. Nunca confiar no lifecycle para exclusão exata na hora.
2. **Mídias órfãs.** A migração 006 exige recibo terminal e mídia `DELETE_QUEUED` para aceitar um outbox; a migração 007 impede transição direta de `RESERVED`/`UPLOADED` para `DELETE_QUEUED`. Não criar GC fazendo bypass do trigger ou simulando entrega de mensagens que nunca foram anexadas. Projetar workflow e autorização próprios, com estado `ORPHAN_QUEUED`, lease de upload, `DELETE` revalidado e auditoria, em migração separada.
3. **Expiração/ACK conciliados:** a data vem do PostgreSQL, e um ACK dentro do prazo exige confirmação após download, hash e persistência criptografada local no Android. O Android atual não implementa ainda esse protocolo R2.
4. **Avisos:** o registro de expiração não equivale a entrega de notificação ao Android. É preciso criar endpoint/evento autenticado, agrupamento por conversa, estado de leitura e pedido de reenvio, sem expor conteúdo.
5. **Estados terminais e RLS:** confirmar privilégios efetivos do papel do Render, lock ordering com revogação, retenção de recibos e controle de privilégios de trigger em staging.
6. **Limite de 25 MB:** não considerar vídeos grandes homologados; arquitetura de multipart/chunking e limites precisam ser definidos de acordo com o produto e hardware.

## Critérios de homologação

- Reexecutar testes backend e módulo R2, PostgreSQL real isolado 004–007.
- Concorrência real `confirmR2Delivery` vs `expireR2Message` em duas conexões, 47h59 / 48h exato / >48h.
- Cada mensagem produz no máximo um recibo terminal, um aviso de expiração e uma ordem durável de exclusão.
- Injetar falhas em cada statement para confirmar rollback; queda do worker antes/depois da chamada S3.
- Testar um objeto criado 24h antes do `attach` sob o lifecycle efetivo; conferir que a exclusão automática não impede o download durante o TTL.
- Testar fluxo de rede desligada, reinício, reinstalação, revogação, versões antigas, Modo A BLE e E2EE com dois aparelhos.

**Proibido:** ativar R2 ou aplicar migração ao Supabase de produção nesta fase.
