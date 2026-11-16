# PessoasAqui — Etapa 13: associação de mídia com lease e janela de 2h

**STAGED / NÃO ATIVAR.** Esta etapa foi criada após homologação da Etapa 12. Nenhum endpoint, worker, Render, Supabase, Cloudflare R2 ou Android foi modificado.

## Mudança

`attachLeasedMediaObject()`, exportada em `backend/src/r2MediaRegistry.js`, reutiliza a transação atômica de `attachMediaObject()`, mas exige `uploadGeneration = 1` e, sob o mesmo lock `FOR UPDATE` do objeto:

- correspondência de `message_r2_objects.upload_generation`;
- `upload_lease_expires_at` registrado pelo PostgreSQL;
- `uploaded_at` presente;
- se `state='UPLOADED'`, `uploaded_at >= clock_timestamp() - interval '2 hours'`;
- recibo terminal ausente, E2EE descriptor, identidade autenticada externamente e conexão bilateral;
- retry em `ATTACHED` com descriptor exato continua idempotente, mesmo após duas horas.

`backend/test/r2LeasedMediaAttach.test.js` prepara **6 testes unitários**. Ainda não foram executados pelo ChatGPT; Antigravity deverá homologar com PostgreSQL isolado (004–008).

## Impeditivos para ativação

1. `reserveMediaObject`, `markMediaUploaded` e `attachMediaObject` legados ainda existem para compatibilidade. Nenhuma rota pode selecioná-los quando o protocolo R2 ficar ativo. Desativação/remoção formal desses caminhos será exigida antes do deploy.
2. A verificação criptográfica de identidade e autorização de vínculo, além de checagem do objeto real contra hash/tamanho no R2, continuam responsabilidades da futura integração HTTP/WS autenticada. O código atual é biblioteca sem import no `server.js`.
3. `fresh_upload` reflete a comparação da idade de upload no relógio PostgreSQL durante o lock. Não impede que o lifecycle de **2 dias** do R2 elimine o blob antes das 48h de entrega. A configuração proposta de 7 dias segue pendente de aprovação explícita.
4. Presigned PUT de 15min vs lease de 60min reduz risco de atraso, mas não garante que todo upload HTTP termine antes da validade. Chaves únicas + lifecycle e reconciliação são necessárias; não iniciar GC.
5. APK Android de protocolo R2 ainda não existe; confirmação de entrega somente após persistência durável criptografada no aparelho. Modo A BLE não deve ser afetado.
6. Se o monitoramento do Pooler mostrar tráfego atípico novamente, diagnosticar separadamente sem misturar a Etapa R2 com incidentes de Egress.

## Antigravity — critérios de teste

- Unitários: `node --test backend/test/r2LeasedMediaAttach.test.js`, `node --test backend/test/r2MediaAttach.test.js` e `node backend/run-tests.js`.
- PostgreSQL 18 isolado com migrações 004–008: sucesso com upload recente, 2h no limite, 2h+1µs expirado, geração errada, lease nulo, estado `ORPHAN_QUEUED`, terminal receipt, rollback e duas associações simultâneas.
- Verificar se nova leitura de `clock_timestamp()` no SQL sob lock pode criar corrida de fronteira e se o intervalo de duas horas basta para o lifecycle futuro.
- Confirmar retorno idempotente apenas com envelope E2EE idêntico e nunca reinserção após expiração.
- Investigar injeção do campo interno `requireLeasedUpload` e garantir que futuros endpoints tenham contrato fixo, não aceitem controle desse campo em JSON do cliente.
- Nenhum teste deve utilizar o Pooler/Supabase de produção. Não realizar correções, commits ou deploys durante auditoria.

Ponto de restauração: `backup/main-pre-r2-delivery-20261114`.
