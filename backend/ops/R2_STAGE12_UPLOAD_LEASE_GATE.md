# PessoasAqui — Etapa 12: concessões de upload R2 no PostgreSQL (STAGED)

**Estado:** IMPLEMENTADO SOMENTE EM BIBLIOTECA, NÃO ATIVADO. A migração 008 foi homologada no PostgreSQL isolado, mas a transferência real R2 permanece bloqueada.

## Implementação

`backend/src/r2UploadLeaseRegistry.js` acrescenta APIs *novas* para o protocolo R2 com migração 008:

- `reserveLeasedMediaObject`: INSERT autoritativo do PostgreSQL com `upload_generation=1` e `upload_lease_expires_at=clock_timestamp()+60 minutos`. A reserva repetida não renova lease, não muda objeto e não permite reuso de message ID.
- `markLeasedMediaUploaded`: UPDATE protegido simultaneamente por chave, ID de mensagem, geração exata, `state='RESERVED'` e `upload_lease_expires_at > clock_timestamp()`. Falhas não confirmam upload; retries `UPLOADED/ATTACHED` são idempotentes somente se hash e tamanho coincidirem.
- `backend/test/r2UploadLeaseRegistry.test.js`: 10 cenários unitários para esses contratos; **aguardando Antigravity executar**.

**Observação:** `reserveMediaObject` e `markMediaUploaded` anteriores continuam presentes para testes legados, mas não possuem fiscalização da migração 008. **Nunca habilitar endpoints de mídia R2 chamando essas funções antigas.** Antes de ativar, unificar/substituir as APIs e obrigar o modo leased, além de validar a janela de upload->attach dentro da transação.

## Segurança e condições impeditivas

1. **A lease do PostgreSQL não encerra um PUT S3 iniciado com URL pré-assinada válida.** Este código não cria URLs nem faz upload, e não impede PUT atrasado. Não há GC destrutivo.
2. A reserva/confirmação chama funções de DB injetadas; **não autentica remetente, não autoriza conexão bilateral e não valida SHA256 lendo o R2**. A integração futura deve fazê-lo; jamais confiar somente em hash declarado pelo Android.
3. `upload_generation=1` descreve uma tentativa única. Retry legítimo deve usar o mesmo objeto apenas dentro de uma lease ativa; tentativas novas exigem **nova identidade de objeto e novo protocolo de mensagem**, sem reatribuição da chave.
4. A regra R2 Lifecycle de 2 dias ainda não foi alterada. A proposta de 7 dias é uma rede de contingência, não a autoridade da expiração de 48h nem garantia do horário exato do expurgo.
5. Para evitar objeto zumbi no R2 após DELETE, o protocolo futuro precisa de reconciliação pós-DELETE / listagem órfã e regra de lifecycle configurada, ou gateway com duração máxima de upload verificável. UUID v4 evita associação indevida, **mas não elimina por si só resíduos/custos**.
6. A migração 008 foi validada em testes isolados; **não aplicada em Supabase de produção**.

## Próximos passos obrigatórios antes de ativar

- Antigravity executar 10 testes do novo módulo, regressão backend e integração 008 no PostgreSQL isolado.
- Auditar fluxo de `UPLOAD` real e autorização de chaves; decidir estratégia contra PUT tardio com evidência.
- Criar cláusulas de attach com janela `uploaded_at` recente e geração vinculada ao descritor; testar concorrência GC/attach e limites temporais.
- Definir outbox de órfãos com retries, fencing e segunda verificação; não ativar DeleteObject sem o protocolo completo.
- Implementar Android download hash AEAD + persistência local antes de ACK; manter Modo A BLE independente.
- Ajustar lifecycle R2 em etapa explícita, separada e autorizada; testar dois dispositivos antes de deploy.

**Nunca enviar credenciais para o chat. Não modificar Render, R2 nem Supabase em produção neste estágio.**

**Restauração:** `backup/main-pre-r2-delivery-20261114`.
