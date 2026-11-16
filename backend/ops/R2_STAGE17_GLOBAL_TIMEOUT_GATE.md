# PESSOASAQUI — ETAPA 17: limite global de leitura R2 e gate de custos (STAGED)

**Etapa 16:** auditoria de 16/11/2026 reportou 10/10 no verificador e 208/208 regressao, mas encontrou bloqueadores de timeout pre-headers, TOCTOU e abuso de egress. Nenhum teste da presente Etapa 17 foi executado ate esta revisao. **R2 permanece desconectado do server.js.**

## Implementado

- `backend/src/r2ObjectIntegrity.js`: timeoutMs agora inicia ANTES da requisicao GET. O deadline inclui obtencao da resposta, handshake DNS/TCP/TLS e leitura do stream. Ao vencer, AbortController envia cancelamento ao SDK, fecha o stream e rejeita R2_READ_TIMEOUT. Promise.race bloqueia mesmo um cliente que ignore abort.
- `backend/src/r2CiphertextAdapter.js`: fetchCiphertext passa `abortSignal` em `client.send(cmd,{abortSignal})`. Se o SDK entregar uma resposta apos cancelamento, fecha imediatamente Body e nao entrega bytes.
- `backend/test/r2ObjectIntegrity.test.js`: novos testes para stall antes dos headers, resposta atrasada com fechamento de body e propagacao do AbortSignal.

## NAO concluido — PROIBIDO ATIVAR

1. Configuracao obrigatoria de `NodeHttpHandler` connectionTimeout/socketTimeout e limites de retentativa no futuro cliente SDK. O AbortSignal da biblioteca e uma protecao adicional, nao dispensa timeout no transporte.
2. **TOCTOU**: UUID aleatorio impede adivinhacao mas NAO impede reuso de URL PUT valida nem sobrescrita do mesmo objectKey. Exigir condicao create-only suportada de ponta a ponta por Cloudflare R2, ou contrato de objeto versionado/imutavel validado, e testes contra PUT concorrente. Nao assumir que `If-None-Match: *` funciona no R2 sem verificar a semantica real de S3/presigned URL e o cliente.
3. **Custos/DoS**: autorizacao por identidade E objeto, rate-limit global e por identidade, quota de bytes/dia e numero de verificacoes, single-flight por objeto com vinculacao do ator e transicao, e retornos idempotentes via estado SQL para nao repetir downloads. Precisa sobreviver a multiplas instancias de Render; controles em RAM isolados nao bastam.
4. **Transacionalidade:** GET remoto sempre fora da transacao PostgreSQL; apos GET, revalidar sob lock na transacao SQL antes de marcar uploaded. Nunca tratar somente o SHA esperado do cliente como prova. Callback de Etapa 15 nao deve executar GET durante consumo do nonce.
5. Validar stream SDK S3, erros tardios, cancelamento, memoria e tempo real com mocks. O timer protege a funcao, mas componentes de rede externos podem ter efeitos residuais ate respeitarem o abort.
6. A regra de lifecycle atual de 2 dias ainda e incompatível com garantia de 48h apos accepted_at em todas as situacoes; ajuste para 7 dias e reconciliacao permanecem propostas, sem aplicar.
7. Nao iniciar GC de orfaos nem criar rotas HTTP de upload/download antes de fechar estes bloqueadores.

## Antigravity — auditoria EXCLUSIVAMENTE em ambiente isolado

1. Confirmar main HEAD e ponto de restauracao `backup/main-pre-r2-delivery-20261114`.
2. Executar:
   - `node --test backend/test/r2ObjectIntegrity.test.js`
   - `node --test backend/test/r2ActionAuth.test.js`
   - `node --test backend/test/r2LeasedMediaAttach.test.js`
   - `node --test backend/test/r2UploadLeaseRegistry.test.js`
   - `node backend/run-tests.js`
3. Testar DNS/handshake simulado sem retorno, headers retardados, cliente SDK ignorando abort, GET que retorna depois do timeout, erro no stream, 25MiB sob timeout, limite de bytes e ausencia de unhandledRejection.
4. Auditar race do timer: se o timeout disparar durante verificacao SHA ou depois de stream completado, nunca retornar sucesso falso nem destruir recurso ainda em uso indevido.
5. Reportar separadamente bloqueadores nao implementados (overwrite, quotas, auth por objeto, SDK HTTP handler) sem declarar a cadeia completa homologada.
6. Proibido alterar arquivos, commitar, implantar Render, modificar Supabase/Cloudflare, ou tocar no Android de producao.

**Status:** somente biblioteca preparatoria, nao testada nesta etapa; rollout bloqueado.
