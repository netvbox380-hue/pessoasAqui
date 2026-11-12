# PessoasAqui — Etapa 4B: implantação coordenada e reversível

**Estado:** preparação para homologação. **NÃO autoriza execução em Supabase ou Render.**

## Problema corrigido

O relatório anterior propunha reconciliar colunas `from_cid/to_cid` e `low_cid/high_cid` que **não existem** no esquema do PessoasAqui. Além disso, mudava `current_state` sem aumentar `revision` e ignorava revogações de pares ausentes em `connections`.

O código usa `connections(from_identity, to_identity, is_mutual)` e `connection_revisions(pair_key, identity_low, identity_high, current_state, revision)`. Uma transição durável exige preservar o inteiro monotônico e o tombstone `NONE`.

**IMPORTANTE:** também havia outro escritor de `connections`: `POST /api/invites/redeem`. A rotina de resgate com `CONNECTION_REVISIONS_ENABLED=true` deve usar o mesmo `pg_advisory_xact_lock`, transação e `advancePairRevision` utilizados por `/mark` e `/unmark`. Validar explicitamente essa integração, inclusive concorrência entre resgate e revogação.

## Pré-requisitos obrigatórios

1. Homologar mudanças em PostgreSQL isolado e suíte backend/Android. Testar o resgate LINK/QR sob a flag ligada e desligada.
2. Inspecionar outros possíveis escritores de `connections`, inclusive tarefas de suporte/administrativas. Nenhuma escrita pode ficar fora do protocolo quando a flag estiver ligada.
3. Obter backup/snapshot recuperável do Supabase e registrar contagens prévias de `connections`, `connection_revisions` e `connection_events` (quando existirem), sem expor identidades.
4. Reservar janela de manutenção e **suspender TODAS as instâncias do backend capazes de gravar vínculos**, inclusive resgate de convites. Não basta alterar a flag em um processo enquanto outros continuam ativos. Não aceitar solicitações durante o corte.
5. Confirmar ausência de operações de escrita em andamento. Monitorar a interrupção dos serviços e avisar usuários se necessário.
6. Garantir que o Render reiniciará com o código homologado e `CONNECTION_REVISIONS_ENABLED=true` desde o PRIMEIRO processo que voltar a aceitar requisições. Evitar versões mistas.

## Chaveamento: com os escritores suspensos

1. Aplicar a versão auditada de `backend/migrations/003_connection_monotonic_revisions.sql`. Se já existir, não fazer DROP nem recriar tabelas.
2. Executar `backend/ops/connection_revisions_cutover.sql` **como uma única operação**, ainda sem escritores. O script bloqueia órfãos, IDs ambíguos e inconsistências, preserva revisões existentes e acrescenta tombstones de pares removidos. Mudanças de estado incrementam a revisão e geram evento administrativo `PURGE`; novos pares de baseline não geram eventos históricos artificiais.
3. Fazer validação read-only, antes do reinício, de todos os pares ativos e tombstones. Se houver divergência, **não retomar o tráfego**. Não contornar validações.
4. Iniciar o Render já com `CONNECTION_REVISIONS_ENABLED=true`, garantindo que não haja outra instância legada aceitando tráfego.
5. Validar consultas assinadas de status, marcação, revogação, reconexão, convite LINK/QR e WS. Verificar se os pares ativos sempre têm revisão e nenhum tombstone antigo volta para `MUTUAL` sem nova aceitação.
6. Verificar log do backend e comportamento Android, monitorar recursos do Supabase.

## Rollback

Se o novo backend apresentar problemas, **suspender novamente todos os escritores e bloquear novos vínculos**. Não simplesmente desligar a flag enquanto houver clientes que já observaram revisões: isso pode permitir mudanças legadas não versionadas e criar uma nova lacuna temporal. Um rollback seguro exige plano de preservação/reconciliação das revisões, inspeção de mudanças ocorridas após a ativação e validação antes de reiniciar a versão antiga. **Nunca executar DROP CASCADE nas tabelas de revisão.**

## Limitações e critérios de aprovação

- Não é possível reconstruir revogações ocorridas ANTES da primeira implantação de revisões; não fabricar eventos históricos.
- A reconciliação só pode ser considerada segura se `connections` estiver estável do início do snapshot até o primeiro processo revisionado aceitar tráfego. Um lock limitado à transação não protege a janela depois do COMMIT.
- `ON CONFLICT DO NOTHING` do backfill 003 não substitui a reconciliação, pois não ajusta linhas anteriormente existentes.
- Identidades órfãs em `connections` bloqueiam o cutover: investigar o dado sem apagar automaticamente.
- Testar reconciliação repetida: na segunda execução sem novos writes, não incrementar revisões nem criar novos eventos.
- Testar revogação enquanto o sistema legado estava ativo antes do corte: converter estado antigo em `NONE`, preservar revisão crescente e registrar evento de manutenção sem autor falso.
- Testar link/QR em paralelo com revogação do mesmo par. As respostas REST e notificações WS devem carregar revisão.
- Não liberar produção com divergências ou testes faltantes.

**Nenhum comando deste plano deve ser executado no Supabase ou Render sem autorização específica do proprietário.**
