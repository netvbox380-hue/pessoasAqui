# PESSOASAQUI — ETAPA 23: Registro local de downloads ativos e cancelamento (STAGED)

**Origem:** auditoria corrigida da Etapa 22 reportada pelo Antigravity: HEAD `2022566` na worktree isolada, 8/8 `r2StreamStartGate`, 8/8 `r2ReadAdmission`, 8/8 `r2RecipientReadAuth`, 8/8 `r2ActionAuth`, 2/2 PostgreSQL 18 real (0 skipped), 208/208 regressão e 12 cenários transacionais aprovados. O rollout R2 permanece desligado.

## Implementação preparatória

- `backend/src/r2ActiveStreamRegistry.js`: `createR2ActiveStreamRegistry()` gerencia streams de download ativos **no processo atual somente**, por remetente, destinatário e ID da mensagem.
- Cada registro exige **referência confiável do backend**, `AbortController` e resposta HTTP injetada, com no máximo 64 downloads ativos no processo e 2 por destinatário (valores preparatórios).
- `revokeMessage(messageId)` e `revokePair(identityA,identityB)` abortam o controlador do download e encerram a resposta HTTP para impedir bytes adicionais **após o processo receber o evento de revogação**. O encerramento e a remoção de listeners são idempotentes, também no evento `finish`/`close`.
- `backend/test/r2ActiveStreamRegistry.test.js` contém oito testes novos de cancelamento, encerramento do socket, concorrência e limpeza; ainda **não executados aqui**.

## LIMITES CRÍTICOS — NÃO CONFUNDIR COM REVOGAÇÃO EFETIVA

1. Este mapa vive na RAM de **um único processo Node**. Não propaga eventos entre múltiplas instâncias Render, nem persiste eventos, nem obtém estado do PostgreSQL. Um `revokePair` só é eficaz se ocorrer no mesmo processo do stream.
2. Não há `LISTEN/NOTIFY`, CDC, triggers, outbox, verificação periódica de estado ou rota: **nenhuma alteração no servidor de produção ou no banco**. A revogação de múltiplas instâncias e a janela entre COMMIT e notificação permanecem abertas.
3. `register()` deve ser chamado **exclusivamente pelo gateway de download** depois de `admitAuthenticatedR2Read()` (ECDSA P-256/anti-replay + quota) e `revalidateR2ReadAtStreamStart()` (autoridade atual). Nada recebido por JSON Android pode gerar uma referência confiável.
4. O evento de revogação deve ser publicado **depois do COMMIT transacional** das alterações de conexão/mensagem ou ligado a uma outbox durável reconciliável, de preferência com identificadores não sensíveis. Não enviar URL de R2, identificadores pessoais ou plaintext em logs/notificações.
5. Quando o fluxo HTTP Android for interrompido, o cliente **deve descartar bytes parciais**, não descriptografar conteúdo incompleto, não registrar `DELIVERED` e não reter mídia incompleta. A implementação Android ainda não existe.
6. Nem todos os dados já transmitidos podem ser recuperados após cancelamento. Não anunciar revogação retroativa ou instantânea.
7. O caminho R2 DELETE→PUT com URL antiga válida continua bloqueando GC. Não ativar exclusão de órfãos, nem envio de presigned GET direto ao Android, nem entrega de arquivos.
8. `response.destroy()` finaliza a conexão; futuro gateway terá de impedir escrita posterior e controlar `AbortSignal` em `client.send(...)` e em stream pipeline; medir egress, desempenho, memória e vazamentos reais.
9. Como a revogação pode chegar na pequena janela entre preflight e registro ativo, o gateway futuro precisará de **verificação de estado após registrar** e antes de enviar bytes, ou mecanismo de sessão durável/revisão que evite perda de eventos. O registro em RAM sozinho não fecha essa janela.

## Antigravity — auditoria isolada sem correções

1. Verificar HEAD `main` e o ponto de restauração `backup/main-pre-r2-delivery-20261114`. Atualizar `origin/main` em worktree separada e nunca descartar alterações locais do aplicativo.
2. Executar:
   - `node --test backend/test/r2ActiveStreamRegistry.test.js`
   - `node --test backend/test/r2StreamStartGate.test.js`
   - `node --test backend/test/r2ReadAdmission.test.js`
   - `PESSOASAQUI_TEST_PG_URL=... node --test backend/test/r2ReadAdmission.postgres.test.js` com PG 18 isolado, **zero skips**.
   - `node backend/run-tests.js`.
3. Simular revogação da mensagem e do vínculo após registrar, streaming parcial, fechamento HTTP, evento abort independente, notificações duplicadas, resposta terminada, muitas sessões, limite por destinatário, tentativa de forjar ator, e vazamento de listeners.
4. Confirmar que **sem evento distribuído** o sistema NÃO interrompe downloads em outras instâncias e que o módulo não pode ser interpretado como mecanismo final homologado de revogação.
5. Em PostgreSQL 18 isolado, estudar modelo de notificação pós-COMMIT e outbox durável; **não criar triggers nem migração** nesta etapa.
6. Sem commits pelo Antigravity, deploy, produção, Android ou habilitação de GC.

**STATUS: Stage 23 implementado para auditoria, ainda NÃO HOMOLOGADO. Rollout R2 estritamente desativado.**
