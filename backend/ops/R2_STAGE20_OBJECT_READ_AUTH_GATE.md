# PESSOASAQUI — ETAPA 20: autorização de download por objeto R2 (STAGED)

**Base:** Etapa 19 homologada em 16/11/2026 (9/9 create-only, 9/9 adapter, 8/8 admission, 13/13 integrity, 208/208 regressão), com risco DELETE→RECREATE confirmado. **NÃO ativar exclusão/Garbage Collector.**

## Implementação

Foi adicionado `backend/src/r2RecipientReadAuth.js` com `authorizeRecipientR2Read()`, um gate SQL somente-leitura que DEVE ser invocado dentro da transação/callback autenticado `R2_READ` de `executeAuthenticatedR2Action()` (ECDSA P-256 + anti-replay da Etapa 15).

A autorização exige:
- identidade técnica do destinatário proveniente da **prova criptográfica validada** pelo servidor; não de JSON Android;
- conexão bilateral ativa já verificada e bloqueada com `FOR SHARE` na camada `r2ActionAuth.js`;
- correspondência EXATA de `object_key`, `message_id`, identidade remetente e destinatário nas tabelas `message_r2_objects` E `e2ee_messages`;
- objeto em estado `ATTACHED`, mensagem `PENDING`, não cancelada e com `expires_at > clock_timestamp()`;
- ausência de `message_delivery_receipts` terminal;
- digest SHA-256 e tamanho cifrado armazenados, com limite de 25 MiB.

`SELECT ... FOR SHARE OF o,m` protege contra alterações concorrentes enquanto a transação de autorização está aberta. Nenhuma URL presigned GET é gerada, nenhum conteúdo é transferido e nenhum SQL de produção foi alterado. O retorno contém apenas metadados de tamanho, SHA-256 e IDs já aprovados.

`backend/test/r2RecipientReadAuth.test.js` contém oito testes novos (não executados pelo ChatGPT) de destinatário legítimo, identidade invertida, outro objeto, mensagem vencida/cancelada/entregue, recibo terminal, metadados inválidos, parâmetros malformados e DB indisponível.

## BLOQUEADORES de produção

1. **TOCTOU entre COMMIT e GET**: como a transação precisa terminar antes de chamar a rede, a autorização deve ser revalidada imediatamente antes de emitir a capacidade de leitura. Uma URL GET não pode ser revogada instantaneamente depois de emitida; utilizar TTL curto, evitar cache, restrições por identidade/objeto no gateway e cancelar quando houver revogação.
2. **Integridade durante o download**: a camada de leitura deve calcular o SHA-256 real do stream e verificar bytes recebidos, com limites globais de tempo e tamanho. Um ETag sozinho não comprova SHA-256 do ciphertext, autoria, nem impede alterações depois do HEAD. Verificar versão/ETag quando aplicável, preferir armazenamento efetivamente imutável.
3. **Não expor mídia por estado de entregue**: a política atual rejeita toda mensagem cuja `delivery_state` não é `PENDING`; homologar tratamento de retries de entrega e ACK após armazenamento local, sem bloquear downloads legítimos durante corrida de recibos.
4. **Proteção contra exclusão prematura**: conditional PUT create-only permite DELETE→PUT caso um link ainda esteja válido; quarentena de duas horas por si só não comprova término de um PUT lento já iniciado. GC permanece desativado até prova de fencing de uploads, lifecycle e reconciliação.
5. **Egress/DoS**: leitura/download também precisa de quota e limite de concorrência, separadamente da quota de confirmação de upload. Controles Etapa 18 não protegem GET de destinatários automaticamente.
6. **A migração 010 (preparatória) não foi aplicada na produção.** Não importar nenhum destes módulos para server.js ou publicar rotas antes do portão integral de ativação.
7. O `r2ActionAuth` ainda exige integração correta com recovery/rebinding de identidade, moderação/banimento e exceções de família; não declarar integração Android completa.

## Antigravity — homologação SEM ALTERAÇÃO

Verificar HEAD e o ponto de restauração `backup/main-pre-r2-delivery-20261114`. Executar:
- `node --test backend/test/r2RecipientReadAuth.test.js`
- `node --test backend/test/r2CreateOnlyPut.test.js`
- `node --test backend/test/r2ActionAuth.test.js`
- `node --test backend/test/r2ObjectIntegrity.test.js`
- `node --test backend/test/r2VerificationAdmission.test.js`
- `node backend/run-tests.js`

PostgreSQL 18 isolado (migrações 004–010): testar autorizador dentro do callback `executeAuthenticatedR2Action(R2_READ)` com prova P-256 real; cada objeto, mensagem, destinatário e remetente; revogação simultânea, expiração na fronteira e recibo terminal; banco indisponível. Verificar se `FOR SHARE OF o,m` é SQL válido e mantém ordem de locks sem deadlock.

**IMPORTANTE:** testes com mocks não substituem validação transacional real e ensaios de revogação entre autorização e entrega. Relatar como bloqueios todas as partes sem integração.

Sem commits ou correções pelo Antigravity, sem contatos com Render/Supabase/Cloudflare de produção e sem APK.

**STATUS: código staged, ainda NÃO HOMOLOGADO.**
