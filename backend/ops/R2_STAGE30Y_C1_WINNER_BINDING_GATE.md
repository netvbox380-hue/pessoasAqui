# PESSOASAQUI — ETAPA 30Y-C1: vínculo físico imutável da tentativa vencedora (PREPARE ONLY)

## Baseline auditada
O proprietário relatou 30Y-B homologada sobre `1cf34e77765ccb61742473f6f4571d123c5d2e14`: 208/208 regressão, 9/9 unidade, 69/69 correlatos, 41/41 cenários PostgreSQL 18.1 isolado, concorrência até 50 workers. O diagnóstico recomenda FK composta em 019 contra INSERT SQL que vincule claim a tentativa incorreta. Antes da implementação 30Y-C1, `main` foi conferida idêntica à baseline e checkpoint `backup/main-pre-r2-delivery-20261114` segue em `02b6a487ac8b3ad6c64adf6ce8f6887dbfb53bf9`.

## Entrega C1
- `backend/migrations/020_r2_winning_attempt_binding_PREPARE_ONLY.sql`: adiciona (A) UNIQUE composto da attempt 018 e FK exata na claim 019 `object_key,message_id,identity_id,generation`; (B) UNIQUE composto para vincular prova e descritor `claim_id,object_key,message_id,identity_id,generation,content_sha256,content_bytes`; (C) trigger que impede troca de dono/objeto/mensagem/geração/hash/bytes num retry do claim e restringe transições; (D) tabela `message_r2_winning_attempts`, com `message_id PRIMARY KEY` = no máximo UM vencedor, chave física UNIQUE, claim/descriptor exatos via FK composta, provenance parent via FK, trigger que bloqueia UPDATE/DELETE, RLS e REVOKE para PUBLIC/anon/authenticated. Nenhum backfill, sem mexer em constraint antiga `message_r2_objects.message_id UNIQUE`.
- `backend/test/r2WinningAttemptMigration.test.js`: seis verificações estáticas de superfície para regressão, **não são testes PG reais**; precisam de homologação no PostgreSQL 18.1. Tests não foram executados pelo ChatGPT.
- Nenhum runtime/endpoint/Android/migração em produção modificado. A tabela da vencedora é **inerte**, sem INSERT feito por qualquer código. **C1 não confirma SHA-256 físico e não seleciona vencedora:** a FK composta garante correspondência de claims e descritores, mas não comprova estado COMPLETED, GET real, PUT imutável ou vínculo E2EE. A seleção autorizada virá em C2, exigindo leitura física fora da transação, nova validação e inserção da vencedora + mudança de claim + tentativa no MESMO COMMIT, sem aceitar verified:true de Android.
- **Atenção ao schema PostgreSQL:** `message_r2_upload_attempts` preserva tombstones e trigger proíbe DELETE. FK atual a `message_r2_objects(message_id)` significa que exclusão física da linha lógica precisará de política de tombstone/retenção antes do GC. Ainda não ativar.
- **Atenção à janela TOCTOU:** saída do PUT por tentativa A jamais pode alterar B, mas uma chave B existente pode ser recriada após DELETE prematuro; não apagar enquanto capability ativa ou PUT em voo. Bucket Locks não ativados. Nenhuma prova física Cloudflare.

## Auditoria obrigatória Antigravity 30Y-C1
Confirmar HEAD exato, checkpoint, worktree isolada. Aplicar migrações 001–020 apenas em PG18.1 descartável 127.0.0.1:54333. Antes de avançar, rodar:
```sh
node --test backend/test/r2WinningAttemptMigration.test.js
node --test backend/test/r2StagedAttemptConfirmAdmission.test.js
node --test backend/test/r2StagedAttemptPutIssuance.test.js
node --test backend/test/r2ImmutableAttemptAllocator.test.js
node backend/run-tests.js
node --test backend/test/r2ReadAdmission.postgres.test.js
```
Exigir 208/208 geral, seis testes novos e todas suítes correlatas aprovadas, zero skips/falhas. PG real:
1. Criar claims com `object_key` real mas `message_id`, `identity_id` ou `generation` falsos: FK composta recusa SQL diretamente (23503), sem depender de aplicação.
2. Mudar hash, bytes, messageId, geração, identidade, claimId após COMPLETED: trigger/FK bloqueiam e desfazem; retries com claim expirado preservam SHA/bytes (especificar semântica FAILED→RUNNING).
3. Inserir winner com claim de outra chave, outro messageId, outro ator, outra geração ou conteúdo: FK composta recusa.
4. Inserir winner B e tentar winner A na mesma mensagem: PK recusa; tentar usar mesma chave B como vencedora de duas mensagens: UNIQUE recusa; UPDATE/DELETE de winner recusa 23514. **Admitir que SQL pode inserir winner a partir de claim RUNNING porque FK não valida estado:** isso é bloqueador da futura ativação e exige finalizador/backend e defesa DB adicional.
5. RLS e permissões anon/authenticated, FK ON DELETE RESTRICT, índices/tamanho, migrations 001–020, concurrency 2/10/50, rollback e locks.
6. Auditar triggers atuais 018/019 e relação com migrations 005–008, GC/outboxes, READ/ATTACH, sem permitir que esses consumidores usem registro winner antes de sua seleção fisicamente verificada.
7. Reportar riscos: qualquer função SQL com papel de DB autorizado pode forjar estado COMPLETED/INSERT winner; avaliar função SECURITY DEFINER mínima com roles separadas, proibição de grants diretos e auditoria de privilégios. Não declarar finalização física em C1.
8. Cloudflare R2, Render, Supabase produção e Android intocados. Nenhum deploy, workers, server.js, flags ou novos SDKs.

Gerar `PESSOASAQUI_ETAPA30Y_C1_AUDITORIA_WINNER_FK_IMUTABILIDADE_R2.md`; Antigravity apenas diagnostica/testa, NUNCA corrige/commita/push, não toca serviços de produção.

**STATUS: C1 IMPLEMENTADA SOMENTE NO ESQUEMA, NÃO HOMOLOGADA, NENHUM VENCEDOR PROMOVIDO, CUTOVER BLOQUEADO.**
