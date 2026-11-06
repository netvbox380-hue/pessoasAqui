# PessoasAqui — Recuperação familiar V3: integração e critérios de segurança

**Estado:** código integrado na `main` como candidato técnico; **NÃO homologado**. Nenhum deploy realizado. Não liberar APK/AAB de produção antes da validação abaixo.

## Compromissos anteriores preservados

- Recuperação da **identidade**: QR/LINK emitido por um familiar + aprovação independente de outro familiar; LINK remoto inclui janela de contestação de uma hora, QR não. O aparelho novo deve provar a posse de sua chave privada P-256; um familiar isolado nunca transfere a identidade.
- Recuperação adicional do **PIN esquecido e do segredo V3 perdido**: três familiares previamente autorizados, separados em emissor do QR/LINK e dois guardiões liberadores de partes Shamir 2-de-N.
- Familiares armazenam o **cofre criptografado**, mas não recebem o PIN e o segredo V3 íntegros. Cada familiar guarda somente uma parte aleatória, cifrada localmente com Android Keystore. O Render/PostgreSQL nunca recebe as partes, o PIN ou o segredo completo.
- Compatibilidade V2→V3: backups legados devem permanecer acessíveis enquanto a migração não for concluída e confirmada; não permitir downgrade de V3. Histórico temporário do BLE não se torna permanente sem a confirmação bilateral explícita.
- **Sem familiares disponíveis:** recuperação de PIN e segredo V3 não foi implementada. Um período de espera isolado (por exemplo, duas horas) **não** basta para demonstrar titularidade; requer um fator independente previamente provisionado. Esse fluxo permanece bloqueado.

## Fluxo integrado

1. Titular credencia **três familiares distintos no backend** antes de ativar nova recuperação V3.
2. O backup V3 cria três partes Shamir independentes do mesmo PIN/segredo. Transmite cada parte por envelope E2EE para um familiar, em mensagem de controle separada do cofre cifrado. Réplica só conta como confirmada com ambos gravados; exige três ACKs.
3. Familiar protege a parte em armazenamento local Android Keystore; o backend só guarda delegações e registros de aprovação, jamais o segredo.
4. No aparelho substituto, QR/LINK mais segunda aprovação permitem CLAIM da identidade. A chave pública ativa é rotacionada e o backend avisa familiares para atualizarem o pin criptográfico pelo endpoint autenticado.
5. O novo aparelho guarda sessão de recuperação pós-CLAIM em Keystore, válida por 15 minutos. Consulta a lista de guardiões excluindo o familiar emissor.
6. O titular solicita as partes por mensagens de controle E2EE. Cada guardião recebe **diálogo explícito de consentimento**; somente após assinatura P-256 e autorização do backend envia sua parte criptografada para a identidade recuperada.
7. O novo aparelho aceita apenas respostas dos guardiões autorizados com hash da sessão correto, escolhe partes de dois familiares distintos da mesma versão e combina o segredo Shamir, sem registrar PIN ou segredo em logs.
8. Com o material recuperado, o usuário restaura o cofre V3; o código temporário é apagado em caso de sucesso.

### Restrições

O endpoint `/api/family/recovery/share-release` exige base PostgreSQL persistente, delegação ativa, prova assinada não reutilizada, terceira identidade familiar, segundo aval original, chave do novo dispositivo ativa, identidade recuperada em evento auditável, e janela pós-CLAIM. O endpoint `/api/family/recovery/guardians` autoriza consultas somente pela identidade recuperada. O banco `recovery_share_releases` armazena apenas (grant, familiar, horário).

A revogação assinada elimina autorização no servidor e solicita a exclusão da parte local do familiar. Como o familiar pode estar offline, a exclusão física no outro aparelho **não é garantida sem confirmação de entrega**; a autorização revogada bloqueia novas liberações no backend. Antes da distribuição de novos backups, revisar recuperação de falhas de entrega/paralelismo.

## Gates ainda obrigatórios

- Compilar Android debug e release, executar **todos** os testes Kotlin/JUnit.
- Executar `cd backend && node --test tests/*.test.js`, especialmente `recoverySharePolicy.test.js` e `recoveryShareAuth.test.js`.
- Testar endpoints com PostgreSQL isolado, transações concorrentes, replay, falsificação de assinatura, exclusão de emissor, revogações, sessão expirada, identidade rotacionada, guardian offline e duplicação de partes.
- Teste real com **quatro aparelhos**: titular original, substituto e três familiares (pelo menos cinco aparelhos contando todos), versão antiga/atual, LINK com espera, QR imediato, restauro com PIN correto, esquecimento de PIN, perda de segredo, ambos perdidos e atualização de familiares.
- Inspecionar preservação dos cofres V2, AAD/autenticidade, falhas de Keystore, material de recuperação não exposto em UI/backup/log, não persistência de mensagens BLE do Modo A.
- Análise criptográfica independente do protocolo, incluindo armazenamento de partes, ratchet/autenticidade E2EE, envio/recebimento de respostas, limpeza de RAM, corrida de revogação e confiabilidade da confirmação de réplica.
- **O servidor foi modificado no Git, mas não atualizado no Render.** Backend anterior não suporta estes endpoints; um APK novo precisa de backend compatível e migrações aditivas para funcionar. Deploy apenas com autorização explícita do proprietário.

**Não considerar funcionalidade homologada nem publicação Google Play aprovada sem resultados reais dos testes.**
