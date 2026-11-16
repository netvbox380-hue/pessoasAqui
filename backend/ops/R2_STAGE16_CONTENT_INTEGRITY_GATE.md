# PessoasAqui — ETAPA 16: verificacao real dos bytes R2 (STAGED)

**Nao ativar em producao.** Etapa 15 homologada com ressalvas no relatorio de 16/11/2026; a auditoria reportou 8/8 autenticacao, 7/7 registry, 10/10 attach, 8/8 leased attach, 10/10 lease, 208/208 regressao e 15/15 PostgreSQL isolado.

## Implementacao
- backend/src/r2ObjectIntegrity.js adiciona verifyStoredCiphertext(). Recebe somente S3 client e GetObjectCommand injetados (sem credenciais e sem chamada enquanto o modulo nao for explicitamente invocado).
- Faz GET do R2, valida tamanho anunciado contra o tamanho esperado, processa stream por blocos com limite maximo de 25 MiB, verifica tamanho efetivamente recebido e SHA-256 calculado **sobre os bytes reais criptografados**.
- Nao confia no ETag, na hash fornecida pelo Android, nem em metadados remotos para declarar sucesso. Compara SHA-256 em tempo constante e fecha o stream em erro, truncamento, alteracao, excesso de tamanho ou expiracao do timeout.
- Retorno verificado contem apenas objectKey, digest, bytes e verified=true, nunca o blob, nome de arquivo, chave E2EE ou plaintext.
- backend/test/r2ObjectIntegrity.test.js contem 10 testes novos para integridade, dados adulterados, truncamento, stream exagerado, tamanho de cabecalho diferente, recurso ausente, chunks invalidos e stall de stream.

## Sequencia de integracao futura (NAO IMPLEMENTADA)
1. Autenticar o ator e verificar autorizacao **especifica** do objectKey + messageId no PostgreSQL. O gate de Etapa 15 sozinho nao verifica posse do objeto.
2. Consultar reserva registrada, generation, lease e os campos esperados; validar custo e limites de requisicoes/bytes.
3. Ler e calcular digest real do R2 **fora de uma transacao aberta**. Nunca segurar locks ou nonce do banco durante GET remoto.
4. Abrir transacao NOVA, revalidar identidade/vinculo/objeto/state/generation/lease, verificar que o objeto nao foi substituido entre GET e commit e executar markLeasedMediaUploaded() apenas se os bytes verificados coincidirem. Usar nonce ou ticket de confirmacao cuidadosamente definido em etapa futura, evitando replay e inconsistencia TOCTOU.
5. O descriptor cifrado da mensagem em attach precisa ter hash assinado (Etapa 15), mas nao confunde-se com hash do blob.

## Bloqueios criticos
- **TOCTOU:** GET confirma apenas o conteudo daquela leitura. Sem versionamento/imutabilidade de chave, outro PUT pode sobrescrever o mesmo objectKey entre verificacao e commit; proibir sobrescrita via contrato R2 ou vincular a versao/ETag de modo auditado.
- **Custos:** Cada GET para verificacao traz bytes R2->Render, inclusive se o app estiver fechado ou o cliente repetir requests. Evitar verificacoes duplicadas, rate-limit por identidade/objeto, conteudo maximo, protecao de abuse e contabilizacao de egress.
- **GET inicial sem timeout total:** o timeout atual cobre o streaming depois que a resposta S3 chega; o SDK HTTP tambem precisa de timeout de conexao e cabecalhos, cancelamento via AbortController e limites de retries antes de producao.
- **Memoria:** a verificacao usa streaming e nao acumula o arquivo inteiro; tests devem verificar o comportamento com fluxo de ate 25MiB e falhas do SDK.
- A integridade nao comprova autorizacao, exclusao segura ou recibo de entrega. O Android ainda deve confirmar persistencia local criptografada antes do ACK.
- A regra atual R2 de 2 dias pode remover objetos cedo em relacao as 48h apos accepted_at; lifecycle de 7 dias permanece apenas proposto.
- Sem endpoints HTTP/WS, nenhum cliente Android chama esta implementacao. Nenhuma migracao ou alteracao de producao nesta etapa.

## Antigravity — validar sem alterar
1. Confirmar HEAD e ponto de restauracao backup/main-pre-r2-delivery-20261114.
2. node --test backend/test/r2ObjectIntegrity.test.js
3. node --test backend/test/r2ActionAuth.test.js
4. node --test backend/test/r2LeasedMediaAttach.test.js
5. node --test backend/test/r2UploadLeaseRegistry.test.js
6. node backend/run-tests.js
7. Simulacao apenas com stream/S3 mock: bytes corretos, adulterados, truncados, mais longos que ContentLength, informacao de tamanho falsa, timeout, stream quebrado, objeto ausente, lentidao de headers e concorrencia de overwrite. Apontar falhas com precisao; nao marcar cenarios nao implementados como aprovados.
8. Auditar CPU, memoria e egress da verificacao de 25MiB e isolamento de outras mensagens/fluxos.
9. Nenhum codigo deve ser modificado pelo Antigravity. Nenhum deploy ou acesso a Render, Supabase, Cloudflare R2 ou Android de producao.

**Resultado atual:** codigo pronto para auditoria, NAO HOMOLOGADO. Rollout R2 BLOQUEADO.
