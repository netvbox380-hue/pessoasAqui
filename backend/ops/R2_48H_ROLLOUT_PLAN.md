# PessoasAqui — R2 / entrega de mensagens em 48h (implantação gradual)

Estado: **FASE 1 — apenas funções puras e testes; DESATIVADO em produção**.
Base restaurável: `02b6a487ac8b3ad6c64adf6ce8f6887dbfb53bf9`
Branch de restauração: `backup/main-pre-r2-delivery-20261114`.

## Contrato funcional do proprietário

- Modo A BLE: sem Supabase, Render, R2 ou retenção remota.
- Modo B: envio cifrado E2EE; servidor é intermediário, histórico definitivo fica nos aparelhos.
- Conteúdo pendente dura no máximo 48h a partir de aceitação durável no servidor.
- Confirmação de entrega significa **persistência local íntegra**, não leitura.
- Conteúdo confirmado: excluir do armazenamento remoto; sem confirmação: pendente até expiração.
- Expiração cria evento mínimo sem payload; agrupar avisos por conversa (3 mensagens => 1 aviso); destinatário solicita reenvio; remetente escolhe reenviar a cópia local.
- Registros de aviso/solicitação têm prazos e deduplicação independentes; nunca armazenar conteúdo no aviso.

## Encontrado na versão de produção anterior

- `backend/src/server.js` insere conteúdos em `e2ee_messages` antes do encaminhamento.
- `acknowledgePendingMessage` apenas marca `is_delivered=true` e preserva `ciphertext_payload`.
- Fila HTTP e WS lista somente `is_delivered=false` e não canceladas.
- Confirmação embutida `[DELIVERY_ACK:...]` ainda passa pelo despachante, exigindo auditoria.
- Revogação cancela pendentes, e não deve regredir.
- **A cadeia de ACK após persistência local no Android e o fluxo de anexos ainda precisam ser auditados**.

## Plano de ativação seguro

1. **Auditoria Antigravity, somente leitura:** localizar fluxos Android de envio, salvamento local, ACK HTTP/WS, BLE e arquivos; documentar cenários de reinício/reenvio e contratos legados; validar versão 1.3.8 local com Gradle modificado sem sobrescrever.
2. **Schema aditivo, sem execução automática:** controle `PENDING/DELIVERED/EXPIRED/CANCELLED`, timestamp/expiração de origem DB, tabela de eventos mínimos de expiração, recibo/tombstone de idempotência com TTL, R2 object reference e outbox durável de remoção.
3. **Backend sob feature flag OFF:** transições atômicas `PENDING -> DELIVERED|EXPIRED|CANCELLED`; somente ACK autenticado depois de persistência local; verificação da janela no despacho/poll/ACK; tombstone para reenvio de ID; ACK repetido responde idempotentemente.
4. **R2 privado:** upload cifrado; rota autenticada para gerar autorização de curta duração; nunca enviar credenciais R2 ao Android. Sanitização de tamanho, MIME, hash do ciphertext, chaves aleatórias, estados de upload, limpeza de órfãos, multipart, sem URLs com vida superior à janela.
5. **Outbox:** commit DB do estado terminal + solicitação de exclusão na mesma transação; worker executa DeleteObject idempotente e marca conclusão; retries/backoff e telemetria; a regra R2 de 2 dias é proteção complementar, não garante apagar exatamente em 48h.
6. **Compatibilidade:** novos clientes reconhecem avisos e reenvio; clientes antigos não podem acusar entrega antecipada; rollout gradual, sem ativar recurso antes de Android+backend+DB compatíveis.
7. **Testes:** online/offline, desligar rede e processo durante download/ACK, entrega aos 47h59, conflito ACK versus expiração, WS/HTTP simultâneos, reconexão, revogação, grandes arquivos, chave privada nunca sai do aparelho, fila >100, aviso agrupado, aviso offline, duplicidade de reenvio, R2 indisponível, observabilidade de egress e banco.

## Restrições

- Não executar migração ou limpeza no Supabase de produção nesta fase.
- Não instalar token R2 no GitHub/Android, nem fornecer Access Key ou Secret em logs.
- Não atualizar Render automaticamente; Auto-Deploy deve continuar OFF.
- Não editar a configuração 1.3.8 local do Antigravity sem reconciliar seu diff.
- A pasta `backend/src/deliveryRetentionPolicy.js` contém apenas funções puras; **não é executada pelo servidor nesta fase**.
