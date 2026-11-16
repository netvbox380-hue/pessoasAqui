# PESSOASAQUI — ETAPA 19: PUT create-only / preparação anti-TOCTOU (STAGED)

**Relatório Etapa 18, 16/11/2026:** 8/8 admission, 13/13 integrity, 8/8 auth, 208/208 regressão, 14 cenários isolados PostgreSQL 18 aprovados. O relatório ressalvou **sobrescrita pós-verificação**, autorização por objeto não integrada e endpoints desconectados. Esta Etapa 19 **AINDA NÃO FOI EXECUTADA/HOMOLOGADA**.

## Fundamento externo verificado

Documentação oficial do Cloudflare R2 (consulta em 16/11/2026):
- Conditional operations: https://developers.cloudflare.com/r2/api/s3/conditional-operations/
- Consistency model: https://developers.cloudflare.com/r2/reference/consistency/
- Presigned URLs: https://developers.cloudflare.com/r2/api/s3/presigned-urls/

R2 aceita `If-None-Match: *` em `PutObject`, avalia atomicamente na gravação e retorna **412 PreconditionFailed** se a chave já existe. A URL pré-assinada em si é reutilizável até vencer. Portanto, a segurança depende de assinar a precondição e de não permitir que o cliente a omita. **A condição não impede recriação se o objeto for DELETADO antes de um segundo PUT ainda válido**: esta limitação é explícita no modelo de consistência do R2. Consequentemente, **GC/exclusão precoce segue proibido**.

## Implementação na main

1. `backend/src/r2CiphertextAdapter.js`: uploads diretos via S3Client injetado incluem `IfNoneMatch: '*'`, protegendo contra overwrite acidental ou repetição enquanto o objeto existe.
2. `backend/src/r2CreateOnlyPut.js`: helper `issueCreateOnlyPutCapability` exige reserva autenticada previamente **pelo servidor** (`reserved=true`, generation=1, bucket e objectKey exatos); invoca assinador SigV4 injetado **com TTL máximo de 900 s, cabeçalhos fixos Content-Type=application/octet-stream e If-None-Match=***.
3. `validatePresignedCreateOnlyPut`: fail-closed se URL não for HTTPS, host não for o domínio S3 do R2, chave/bucket divergentes, assinatura/expiração inválidas ou se `X-Amz-SignedHeaders` não incluir **if-none-match e content-type**, além de host. Sem os cabeçalhos assinados, a URL não é liberada.
4. `backend/test/r2CreateOnlyPut.test.js` contém **9 testes** preparatórios; `r2CiphertextAdapter.test.js` foi ajustado e recebeu simulação de segunda gravação rejeitada (412).

## Fronteiras: o que NÃO foi feito

- **Nenhuma URL R2 real foi emitida**. O assinador concreto AWS SDK v3, credenciais R2, endpoints, Render, Cloudflare e Android continuam intocados. O helper valida a estrutura da URL que o assinador retorna, mas **não valida criptograficamente a assinatura SigV4**, a qual será verificada pelo R2 numa chamada real. Testar compatibilidade end-to-end em bucket isolado antes de produção.
- A condição create-only impede sobrescrita **enquanto o objeto existe**, mas não protege contra DELETE→PUT dentro da validade da URL. A camada de exclusão deve esperar o fim de TODAS as autorizações e requisições PUT ainda em voo, usar object keys imutáveis por tentativa e conciliação posterior. **Não iniciar limpeza de órfãos.**
- `ETag` pode servir como sinal adicional de mudança/versão, mas não substitui SHA-256 do corpo, não comprova autoria nem resolve sozinho a janela TOCTOU. Sua persistência/validação e autorização de download são etapas separadas, não implementadas.
- O endpoint futuro só pode liberar capability ao remetente autenticado P-256 após `reserveLeasedMediaObject`, verificação de conexão bilateral, nonce anti-replay, quotas e associação exata de objeto/identidade. Nunca aceitar `authorizedReservation` do JSON Android.
- ExpiresIn de URL 15 minutos vs lease de PostgreSQL 60 minutos deve ser testado contra late PUT (in-flight) e janela de 2 horas. URL curta reduz risco, não revoga PUT iniciado e não prova retenção.
- Multi-part: contrato atual é somente **PUT simples**, não suporta CompleteMultipartUpload. Não permitir futuros multipart sem condição create-only **na operação de CompleteMultipartUpload**, conforme docs Cloudflare.
- AWS SDK v3 SigV4 pode não incluir cabeçalhos no `X-Amz-SignedHeaders` por padrão; é obrigatório configurar assinatura explícita deles e provar com teste real, em vez de enfraquecer o validador.
- Limites Etapa 18 ainda não são integrados aos fluxos; não presumir cota global ou resistência total a Sybil.
- Lifecycle Cloudflare atual de 2 dias continua não alinhado à expiração de 48h contada de `accepted_at`; não alterar na produção sem autorização separada.

## Antigravity — homologar em testes isolados SEM CORREÇÕES

1. Validar HEAD, branch main e ponto de restauração `backup/main-pre-r2-delivery-20261114`.
2. Executar:
   - `node --test backend/test/r2CreateOnlyPut.test.js`
   - `node --test backend/test/r2CiphertextAdapter.test.js`
   - `node --test backend/test/r2VerificationAdmission.test.js`
   - `node --test backend/test/r2ObjectIntegrity.test.js`
   - `node backend/run-tests.js`.
3. Analisar se `IfNoneMatch='*'` efetivamente vira `If-None-Match: *` em `PutObjectCommand` AWS SDK v3, como cabeçalho **criptograficamente assinado** pelo presigner. Rejeitar assinatura que permita omissão do header.
4. Usar mock/servidor S3 isolado (NÃO produção) para 1º PUT OK, 2º PUT 412, PUTs concorrentes, URI adulterada, cabeçalho omitido, TTL > 15min, key divergente e cliente malicioso.
5. Reproduzir condição DELETE→PUT ainda válido: **documentar como risco residual bloqueador** de GC. Simular arquivo verificado cuja key é recriada posteriormente.
6. Verificar ausência de credenciais e URL presigned em logs, não adicionar arquivos externos, não ativar rotas e não alterar qualquer configuração de produção.
7. Reportar status real de assinatura SDK, eventual incompatibilidade de `SignedHeaders` e recomendações, sem mudar código.

**Estado:** ferramenta preparatória, não testada nesta etapa. Todas as rotas R2 e workers destrutivos seguem BLOQUEADOS.
