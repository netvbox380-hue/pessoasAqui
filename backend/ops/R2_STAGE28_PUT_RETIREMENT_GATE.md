# PESSOASAQUI — ETAPA 28: Proteção contra PUT tardio após DELETE (STAGED)

## Base
Antigravity homologou a Etapa 27B no commit `86724aec1b9afccb6652bdd6d5760300ee671369` (correção de client disconnect): 9/9 registry, 8/8 gateway, 8/8 reconcile, 9/9 listener, 9/9 outbox, 2/2 PostgreSQL 18 real (zero skips) e 208/208 backend. Dez cenários de desconexão e concorrência aprovados. Isto não aprova R2 real nem gateway conectado ao servidor.

Checkpoint confirmado: `backup/main-pre-r2-delivery-20261114` no SHA `02b6a487ac8b3ad6c64adf6ce8f6887dbfb53bf9`.

## Risco remanescente
O PUT presigned create-only (`If-None-Match: *`) protege um objeto **enquanto ele existir**, mas se um DELETE ocorrer antes de a URL PUT expirar, a URL antiga pode permitir recriar o mesmo objeto. Essa janela põe em risco o GC e qualquer expectativa de destruição definitiva do ciphertext.

## Preparação da Etapa 28
1. `backend/migrations/013_r2_put_capability_retirement_PREPARE_ONLY.sql`: nova tabela persistente `r2_put_capability_windows`, `object_key` imutável no processo, `expires_at` máximo, `sealed_at` monotônico, RLS ativo e permissões públicas revogadas. A linha age como tombstone permanente do objectKey; **não excluir após remover o objeto do R2**.
2. `backend/src/r2PutRetirementFence.js`:
   - `issuePutWithDurableRetirementFence()`: assina localmente create-only na transação PostgreSQL injetada, confere `X-Amz-Date` próximo do relógio do banco (±60 s), prazo até 15 min e persiste a maior validade de capabilities emitidas. Rejeita novas assinaturas se a chave foi selada. URL só pode sair ao Android **após commit bem-sucedido**; o callback `withTransaction` fornecido precisa efetivamente garantir isso. Signer deve ser computação local sem rede.
   - `sealPutKeyForRetirement()`: chamado na MESMA transação autoritativa que marca a mídia como terminal/DELETE_QUEUED, antes de colocar em fila de deleção. A ausência de histórico de URL gera erro, não libera remoção.
   - `authorizePhysicalR2Delete()`: exige linha selada e relógio PostgreSQL depois do **último vencimento** de PUT mais margem de 120 segundos; de outro modo, retorna `R2_PUT_DELETE_BLOCKED`. É necessário um segundo gate de estado DELETE_QUEUED e outbox pertencentes ao objeto na futura integração.
3. `backend/test/r2PutRetirementFence.test.js`: oito testes isolados com drivers injetados; **não executados pelo ChatGPT**.

## AVISOS E BLOQUEADORES
- **Não está integrado ao emissor de URL das rotas, ao estado de reserva, ao terminal receipt, ao worker DELETE ou ao server.js.** A migração 013 não foi aplicada em produção. Não há operações de R2, assinatura real, exclusão ou endpoints.
- **URLs emitidas antes da implantação da ledger NÃO ESTÃO RASTREADAS.** Para esses objectKeys, o gate deve falhar fechado; o futuro rollout exige quarentena/migração específica de chaves pré-existentes, sem presumir que ausência de registro significa ausência de URL.
- O relógio de assinatura, TTL máximo e margem devem ser validados contra R2/SDK compatível na integração posterior. Sincronismo dos relógios e expiração real SigV4 dependem do provedor; 120 s é provisório.
- Um worker não deve apagar fisicamente o objeto apenas porque existe autorização de `authorizePhysicalR2Delete`; deve validar também `DELETE_QUEUED`, recibo terminal, outbox e relações no PostgreSQL (incluindo serialização concorrente) e impedir novos uploads para a mesma chave para sempre.
- Emissões de URL precisam ser serializadas contra a etapa de selar e ambas devem usar exatamente a mesma linha `r2_put_capability_windows` dentro de transações SQL. Se não houver garantia transacional, não emitir URLs.
- Não confiar em TTL fornecido pelo Android, URL cliente, chamadas de HTTP ou flags enviadas pelo usuário. Não registrar URLs presigned nos logs; não expor bucket/credenciais.
- O uso exclusivo de `CREATE TABLE IF NOT EXISTS` não substitui análise de migrações prévias. O banco isolado deve ser criado do zero e os constraints avaliados antes de qualquer produção.
- **NENHUMA coleta automática de lixo pode ser ativada** até homologação da Etapa 29 e integração de todos os gates.

## Antigravity — homologação da Etapa 28 (diagnóstico sem correção)
Confirmar HEAD `main`, checkpoint, worktree isolada e preservação do Android local.

Testes:
```sh
node --test backend/test/r2PutRetirementFence.test.js
node --test backend/test/r2CreateOnlyPut.test.js
node --test backend/test/r2StreamingGateway.test.js
node --test backend/test/r2ActiveStreamRegistry.test.js
node --test backend/test/r2RevocationListener.test.js
node backend/run-tests.js
```
Executar `PESSOASAQUI_TEST_PG_URL=... node --test backend/test/r2ReadAdmission.postgres.test.js` em PostgreSQL 18 local `127.0.0.1:54333`, **0 skips**. Aplicar migração 013 **somente no PG isolado** junto às migrações 004–012.

Testes reais de PostgreSQL: URL emitida e ledger confirmada; segunda URL para mesma chave aumenta prazo; `seal` proíbe reissue; transação de URL vs seal concorrentes; NULL/ausência de ledger; rollback da emissão/selagem; bloqueio antes do deadline e autorização somente depois da margem; DELETE/PUT tardio simulado (sem chamada R2 real); relógio UTC e datas SigV4 inválidas; constraints e RLS; nunca suprimir erros; nenhuma autorização física se houver janela PUT válida. Provar que a condição `If-None-Match:*` SOZINHA não fecha DELETE→PUT.

Auditar impacto em fluxos futuros: `issueCreateOnlyPutCapability`, `confirmR2Delivery`, `expireR2Message`, `message_r2_delete_outbox`, orphan cleanup e eventual worker GC. **Não alterá-los ainda**.

Exigir no relatório distinção clara entre unitários (mocks) e PostgreSQL 18 real; destacar que GET/PUT/DELETE de produção seguem inexistentes e que URLs pré-migração não são cobertas.

Antigravity não altera código, não commita, não faz deploy, não acessa Render/Supabase/Cloudflare produção e não modifica Android.

**STATUS: Etapa 28 preparada, não homologada. R2 e GC de produção bloqueados.**
