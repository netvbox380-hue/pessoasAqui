# PessoasAqui — Gate de validação criptográfica (05/11/2026)

> **Status: NÃO HOMOLOGADO / NÃO PUBLICAR.** Código na branch `main`; os protocolos HTTP e WebSocket mudaram e o backend novo não é compatível com todos os APKs antigos. Render e Supabase NÃO foram atualizados por esta auditoria. O proprietário controla qualquer deploy.

## Escopo integrado na main (código candidato 1.3.2)

- `CLIENT_MUTUAL_SYNC` não recria vínculos revogados; estado das conexões depende do servidor e o acesso falha diante de indisponibilidade do PostgreSQL.
- `/api/identities/register`: prova ECDSA P-256 obrigatória, sem envio do PIN fixo `0000`.
- `/api/connections/mark`, `/unmark`: provas assinadas vinculadas ao autor, destinatário e operação, com proteção de nonce persistido em PostgreSQL.
- `/api/messages/send`, `/pending`, `/api/presence/heartbeat`, `/nearby`, `/api/connections/status`: requisições assinadas; as leituras confidenciais passaram para POST; GET legado foi bloqueado.
- WebSocket `AUTH`: desafio de 24 bytes por conexão, assinatura ECDSA e validação da chave ativa quando existe vínculo de recuperação.
- E2EE: **novos envios exigem ECDH**; não pode reduzir `ENC_ECDH` a uma chave determinística; servidor não deve decifrar envelopes legados `ENC:`.
- Armazenamento: novos envelopes `ENC_V2:` com Android Keystore AES-256-GCM; leitura histórica V1; PIN pessoal cifrado no armazenamento e verificador PBKDF2 com sal por registro.
- BLE: ECDSA de transcripts e vínculo de chave ECDH com a identidade; identidades familiares recuperadas só são aceitas offline quando a geração da chave pública já foi autenticada e fixada localmente.
- Chaves P-256: vinculação persistente `identity_key_bindings` no PostgreSQL, `POST /api/identities/key` assinado, anti-rollback de geração e cache persistente de chaves de contato. Nunca aceitar anúncio WebSocket como atualização automática de chave.
- Backup distribuído V2: PBKDF2-SHA256 com sal e 310.000 iterações, AES-GCM com AAD autenticando titular/versão/tempo, limite de descompactação, substituição atômica de arquivo e leitura histórica do formato V1. Ver limitações sobre PIN de baixa entropia abaixo.
- Suporte/denúncias: identidade assinada e dados vinculados ao hash da solicitação; falha de banco não pode gerar sucesso.
- Administração: autenticação obrigatória até para alterar versão; sem senha/segredo de fábrica, tokens de 8h, validação de dados, XSS mitigado no painel e transações de moderação.
- Invites LINK + QR v1.3.2: interface de geração, leitor QR, aceitação explícita e token de uso único já presentes na `main`; `versionCode = 28` e `versionName = 1.3.2` sincronizados com a versão-candidata. Não importar trechos antigos de branch que desativam a validação do certificado TLS.

## Comandos de validação no Antigravity (Windows — execução obrigatória antes de concluir)

```powershell
cd backend
node --test tests/*.test.js
cd ..
.\gradlew.bat :app:compileDebugKotlin --rerun-tasks
.\gradlew.bat :app:testDebugUnitTest --rerun-tasks
.\gradlew.bat :app:compileReleaseKotlin --rerun-tasks
```

Checar também os testes no dispositivo **Samsung S24 Android 16** e **Zenfone 9 Android 14**. Não gerar AAB para publicação antes de aprovação de todas as etapas.

## Matriz de homologação obrigatória

1. Instalação atualizada dos DOIS telefones antes de permitir backend novo. Login WS com desafio, reconexão, assinatura incorreta, replay e chave recuperada/rotacionada.
2. Modo A: radar BLE bilateral em 10m, envio direto sem convite, descarte de mensagem atrasada, sem persistência permanente; handshake ECDSA válido entre aparelhos físicos.
3. Modo B: marcação, aceitação, desvinculação por qualquer lado, nenhum ressurgimento de vínculo em sincronização HTTP/WS ou após reiniciar servidor e aparelhos.
4. Convite LINK+QR v1.3.2: integração de interface + parser + API de uso único + metadados na `main` feita; falta **executar** criação, scanner, aceite explícito, expiração e concorrência de resgates nos dois telefones e no PostgreSQL real.
5. Mensagens: ECDH + AES-256-GCM com chave autenticada; retomada de chat com contato offline, após fechar app, recuperar aparelho e alterar chave; proibir plaintext ou downgrade.
6. Recuperação familiar: autorização, emissão de token, uso único, revogação, transferência de identidade e substituição do aparelho antigo; confirmar preservação de backup e histórico.
7. Armazenamento: migração `ENC_V1` para V2 e PIN anterior; corrupção/ausência de chave no Keystore deve falhar sem apagar dados, sem reiniciar identidade, sem aceitar PIN de fábrica.
8. Falha no PostgreSQL: operações não podem confirmar gravações inexistentes; reinício e recuperação dos dados; verificar rotas que retornam cache de memória durante outage.
9. Segurança da sessão: mensagens HTTP/WS com identidades trocadas rejeitadas; leitura de fila e chamados de terceiros rejeitada.
10. Confirmar configuração TLS de Supabase com validação do servidor, em ambiente isolado, não apenas cifragem de tráfego.
11. Auditar provas criptográficas de outros fluxos similares: denúncias, administração, contas legadas, presença, backups distribuídos.

## Limitações explícitas e bloqueadores conhecidos

- Verificação estática da sintaxe dos principais módulos JavaScript: **aprovada**. Foram adicionados testes de PIN legado/V2, relatórios assinados, consulta persistente de chave, BLE/identidade recuperada e cofre V1/V2, mas **nenhum teste de execução da `main` alterada foi homologado nesta sessão**. Suites majoritariamente simuladas, sem PostgreSQL real.
- Android **não foi compilado nesta sessão**, devido à ausência de SDK Android. A integração completa precisa de Antigravity.
- Pinning persistente de chave ECDH foi implementado no código; testes em reinicialização/formatação e recuperação em dispositivos reais ainda não executados.
- BLE de identidade recuperada exige confirmação de geração da chave via servidor antes de reencontro offline; se não há pin local e está sem rede, o handshake deve falhar de forma segura. Testar fisicamente.
- O motor legado `ENC:` ainda é decifrável por quem conhece os identificadores; legado é só para leitura. Histórico não equivale a E2EE moderno.
- Backups distribuídos V2/PIN/admin e denúncias receberam revisão e proteções novas, **não homologadas** em produção. Um PIN de 4–6 dígitos sozinho ainda permite ataque offline ao backup em caso de cópia por adversário. Para uma garantia forte, é necessária evolução do produto com segredo aleatório de recuperação de alta entropia ou esquema de compartilhamento protegido entre familiares; PBKDF2, isoladamente, não cria entropia adicional.
- Sem suíte instrumental Android/Keystore executada: ainda faltam testes reais de migração de PIN V1→V2, perda de chave Keystore, recuperação em aparelho formatado, reinício e ataque de rollback.
- Para nova sessão da administração, provisionar `ADMIN_USER`, `ADMIN_PASSWORD` (≥12 caracteres), `ADMIN_JWT_SECRET` (≥32 bytes); certificado do PostgreSQL validado. Aplicar `backend/schema.sql` antes do primeiro deploy.
- Nova API **incompatível com APK legado**; planejar implantação coordenada, janela de atualização ou rota de migração autenticada, não publicar backend sem APK pronto.

**Nenhuma alteração de banco, variáveis de ambiente ou deploy foi realizada nesta etapa.**


### Critérios explícitos de liberação pelo proprietário

- **Build Android:** compilação debug/release sem erros; todos os testes unitários
  e instrumentados aplicáveis aprovados. Assinatura de APK de teste, sem
  publicação na Play Store por padrão.
- **Recuperação:** formatar ou trocar aparelho de teste, restaurar com consentimento
  familiar e PIN; confirmar geração de chave atualizada no servidor e revogação
  do aparelho antigo sem perda de histórico ou acesso de terceiros.
- **BLE:** Samsung S24 (Android 16) ↔ Zenfone 9 (Android 14) em ambos os sentidos;
  conversa temporária sem convite em ≤10m, ausência de fallback de rede, link
  mútuo opcional; replay/rechaveamento recusados.
- **LINK/QR:** gerar um único convite, confirmar QR e URL iguais; cada um só pode
  ser resgatado UMA vez; segundo resgate (inclusive concorrente ou após reinício)
  recusado; mostrar confirmação humana antes de aceitar.
- **DB e administração:** provar PostgreSQL real, indisponibilidade 503, TLS
  autenticado, sem credenciais padrão, sem XSS e rollback de transação.
- **Segurança do backup:** verificar V1 histórico legível, V2 autenticado,
  metadados corrompidos rejeitados, PIN incorreto recusado, migração do
  armazenamento local Keystore. O risco de PIN curto para backups **permanece**.

**A implementação não equivale a homologação**. Não houve compilação Android,
uso de aparelhos, banco remoto, Render, geração de APK ou deploy nesta etapa.


## Atualização do cofre — 05/11/2026 (V3, **não ativada por padrão**)

- Introduzido motor de cofre V3 com segredo independente de 256 bits aleatórios
  (`P2PVaultManager.generateRecoverySecret()`) mais PIN do usuário. O segredo
  **não** é salvo no pacote JSON nem derivado de identidade/contato/PIN.
- Cofres V3 usam domínio criptográfico próprio e AAD autenticado. Sem PIN **e**
  segredo correto, a restauração V3 falha; troca do campo `formatVersion`
  também falha. Familiar pode armazenar o arquivo V3 sem conhecer seu segredo.
- O código mantém escrita e leitura antigas V1/V2 para não quebrar fluxos
  existentes. O repositório oferece entradas explícitas
  `executeDistributedBackupWithSecret` e
  `queryAndRestoreFromFamilyVaultWithSecret`; exportação/importação local
  também aceitam segredo opcional.
- **NÃO considerar a vulnerabilidade eliminada para usuários existentes:**
  o fluxo automático e as telas de recuperação continuam gerando V2 enquanto
  o usuário não tiver um segredo V3 independente provisionado e resgatável.
  O aumento de PBKDF2 sozinho não resolve ataques offline a PIN curto.
- **Bloqueador para ativação padrão:** disponibilizar o segredo por método de
  recuperação independente e autenticado, como envelopes de transporte E2EE
  com aprovação familiar (com resistência a perda/comprometimento de aparelho),
  sem colocar segredo junto do backup. Implementar UI de recuperação, migração
  explícita V2→V3, confirmação de recebimento do segredo e recuperação em
  telefone novo; sem isso não ativar V3 automaticamente.
- Cofre V3 já armazenado não pode ser substituído por V1/V2, mesmo com
  versão anunciada maior. Ao habilitar V3 em produção, replicação silenciosa
  deverá reutilizar segredo V3 autorizado para continuar gerando novos V3.
- Testes de regressão foram adicionados mas **não foram executados no Android**
  nesta sessão. Antigravity deve compilar, executar testes e validar o
  processo de migração/recuperação com Samsung S24 e Zenfone 9 antes de liberar.


## Integração funcional adicional — Cofre V3 ativável (05/11/2026)

- UI `DistributedBackupDialog`: botão de geração do segredo 256-bit, exibição,
  confirmação obrigatória de cópia guardada **fora do telefone** antes da ativação.
  Após ativação, backups manuais e automáticos utilizam V3.
- Segredo local protegido por `LocalDiskCryptoManager` / Android Keystore,
  vinculado à identidade. Falha de leitura do segredo bloqueia criação de
  novo backup no aparelho, evitando regressão silenciosa para V2.
- Família continua armazenando **somente** o arquivo de backup cifrado.
  A tela **Entre na sua** permite restaurar o cofre da família com PIN + segredo
  V3 que o titular guardou separadamente. Recuperação V1/V2 continua aceitando
  somente PIN. Na restauração V3 válida, o segredo passa a ser armazenado no
  Keystore do aparelho recuperado **antes** da gravação de conversas.
- Importação local aceita segredo V3 adicional. Nunca enviar o segredo no
  payload `[VAULT_STORE:]`, `[VAULT_QUERY:]`, `[VAULT_RESP:]`.
- **Limitação explícita:** o familiar **não** consegue recuperar sozinho o
  segredo V3 perdido. O mecanismo atual requer que o titular guarde
  o segredo offline. Ainda não há compartilhamento limiar Shamir, envelopes
  E2EE de recuperação ou rotação aprovada por vários familiares. A aprovação
  familiar existente recupera a identidade, não substitui este segredo.
- **Não é migração automática de cofres antigos já distribuídos:** V1/V2
  permanecem legíveis, mas cópias antigas obtidas por terceiros permanecem
  vulneráveis a ataques offline. Após confirmar V3, realizar um backup novo
  e validar a substituição entre familiares.
- Riscos de recuperação: perder PIN **ou** segredo V3 significa perda de acesso
  ao cofre V3. Antes da produção, avaliar UX de exportação offline autenticada,
  aviso de perda irreversível e política de rotação segura em caso de
  comprometimento do segredo. Testar formatação, falta de Keystore e hardware.
- Não houve compilação, execução de testes Android, aparelho físico, PostgreSQL,
  Render ou Supabase nesta integração. **NÃO PUBLICAR sem Antigravity homologar.**

### Homologação Antigravity adicional (crítica)

1. Ativar V3, guardar segredo externamente, criar backup; garantir
   `formatVersion=3` no JSON e ausência literal do segredo.
2. Restaurar o cofre em outro aparelho, após transferência da identidade
   autorizada, com PIN e segredo V3. Reiniciar aparelho e verificar
   reativação do backup automático V3.
3. Confirmar falhas com PIN correto + segredo errado, só PIN,
   PIN errado + segredo correto, JSON alterado e versão reduzida.
4. Confirmar que apagar/inutilizar chave Keystore **não** produz
   backup V2 como fallback e não apaga histórico/identidade.
5. Confirmar V1/V2 ainda restauráveis, e impossibilidade de
   sobrescrever V3 armazenado por V1/V2, mesmo com versão maior.
6. Repetir nos aparelhos Android 14 e 16; rodar compilação debug/release
   e testes unitários em Antigravity, em branch/main com este commit.


## Integração de recuperação e suporte familiar — 05/11/2026 (código, não homologado)

**Requisito de produto:** recuperação familiar exige **2 familiares distintos**,
credenciados e ativos no PostgreSQL. Um único familiar não pode emitir e
aprovar a mesma recuperação. QR tem elegibilidade imediata após todos os
avales; LINK possui **1 hora** de proteção e mais 15 minutos de validade para
conclusão. A mera passagem do tempo nunca autoriza a transferência.

### Fluxo implementado no código

1. Titular autoriza familiares individualmente mediante assinatura ECDSA P-256.
2. Titular consulta `/api/family/recovery/readiness`; o Cofre V3 só pode ser
   ativado pela interface quando existem ao menos dois familiares autorizados.
3. Familiar A emite sessão QR ou LINK com operação ECDSA específica,
   código aleatório 256-bit, expiração e `eligible_at` persistidos.
4. O aparelho novo assina `/api/family/recovery/prepare`, vinculando a
   sessão a sua chave pública e identidade técnica; o código antigo sozinho
   não permite transferir a identidade.
5. Familiar B **diferente de A** assina `/api/family/recovery/approve` e o
   banco verifica delegação ativa, chave atual, nonce, sessão e aparelho.
6. `/claim` exige B independente, preparação, prazo, identidade bloqueada
   por nenhum incidente e geração de chave correta antes da transferência.
7. LINK HTTPS `/recover#key=...` mantém o código fora do GET HTTP,
   com landing sem cache/referrer e deep link Android. QR usa payload direto.
8. Familiar pode relatar incidente via Suporte selecionando contato,
   assinatura sobre os campos e vínculo credenciado verificado no banco.
9. Painel administrativo agora inclui aba de investigação do ID prejudicado,
   familiares credenciados, sessões, eventos, tickets e status de dispositivos,
   além de bloqueio temporário de recuperação, liberação e cancelamento
   auditados. O titular pode contestar e suspender recuperação por 24h.
10. Backups V3 exigem ACKs de dois familiares distintos para reportar sucesso,
    recusam downgrade V3→V2 e permitem repetição idempotente quando ACK
    anterior se perdeu. Restauração V1/V2 avança a versão antes da migração.

### Pendências críticas (não tratar como implementadas)

- **PIN esquecido / reconstrução do segredo V3:** NÃO implementado. O fluxo
  ainda exige PIN **e** segredo V3 previamente guardado offline. Não existe
  distribuição/recuperação criptográfica por partes para familiares.
  Não afirmar que dois familiares recuperam o segredo ou o PIN; é necessário
  protocolo auditado de escrow criptográfico + controle de papéis, sem jamais
  expor PIN em texto claro. A recuperação da identidade não é a restauração
  do backup. Para esquecer PIN com esquema limiar exigindo dois participantes
  na reconstrução *além* do familiar de identidade, serão necessários 3
  familiares ou outro fator independente credenciado.
- **Recuperação sem familiares em duas horas:** não implementada; requer
  credencial de emergência de alta entropia previamente emitida e armazenada
  separadamente, vinculada a sessão de posse de nova chave. Nunca usar apenas
  temporizador ou declaração pessoal.
- **V2→V3:** nova geração pelo conteúdo local, atualização de versão e
  confirmação de dois destinatários estão conectadas, mas a atomicidade
  da migração em múltiplos aparelhos e a preservação de cópias legadas
  devem ser homologadas em rede real. A ativação V3 não apaga históricos.
- **QR realmente presencial:** QR/link diferem por modalidade assinada e
  prazo, mas um QR fotografado/transmitido pode ser usado remotamente.
  Ainda falta vinculação verificável a BLE presencial, se o produto exigir
  garantia de presença física.
- **Exclusão administrativa definitiva:** deliberadamente NÃO adicionada;
  precisa de autenticação do titular ou duas aprovações administrativas
  independentes, política LGPD, retenção, reversão e auditoria. Banimento
  e desbanimento já existiam, bloqueio de recuperação é novo e reversível.
- **Autenticação forte de operador administrativo:** sessão HMAC e senha
  existem, mas MFA, RBAC individual, duplo controle de ações destrutivas e
  auditoria resistente a adulteração continuam pendentes.
- **Compilação, migrações PostgreSQL reais, testes de integração de endpoints,
  testes físicos Android 14/16, comunicação BLE e LINK no dispositivo não
  foram executados nesta sessão.**
- **Não fazer deploy no Render e não publicar APK antes da homologação.**

### Testes obrigatórios para Antigravity

```powershell
cd backend
node --test tests/*.test.js
cd ..
.\gradlew.bat :app:compileDebugKotlin --rerun-tasks
.\gradlew.bat :app:testDebugUnitTest --rerun-tasks
.\gradlew.bat :app:compileReleaseKotlin --rerun-tasks
```

Em ambiente PostgreSQL isolado de produção, verificar: somente um familiar
(negado), dois familiares, QR, LINK antes e após 1h (preferir relógio
controlado), contestação autenticada, nonce repetido, chave familiar
revogada, aparelho trocado, revogação simultânea, falha do banco, PIN
errado, cópia V2 legada, exportação/importação V3, ACK duplicado/perdido,
suporte familiar sem vínculo (403), suporte com vínculo, consulta admin
sem autenticação (401), holds e reativação.


### Atualização adicional: estado do pedido, alerta e LINK web

- Após receber QR/LINK, o novo aparelho pode escolher **Iniciar recuperação**
  para vincular, com prova P-256, a autorização ao novo dispositivo ANTES de
  pedir o aval do segundo familiar. Após isto, pode consultar **Estado da
  recuperação** autenticado (`/api/family/recovery/status`): aguardando outro
  familiar, aguardando 1h, pronto, expirado, suspenso ou utilizado.
- O antigo aparelho conectado recebe `RECOVERY_ALERT` sem segredo; pode
  **Contestar** assinando `RECOVERY_CONTEST`. Isto invalida pedidos pendentes
  e suspende novas recuperações por 24 horas, sem apagar a conta.
- LINK remoto usa `/recover#key=PA-REC-...`, página web com
  `Cache-Control: no-store` e `Referrer-Policy: no-referrer`. O Android
  aceita App Link HTTPS ou esquema próprio. Testar a verificação de domínios,
  a abertura automática e o retorno ao app em aparelho real.
- **Bloqueadores ainda presentes:** recuperação com PIN esquecido sem segredo
  V3 previamente salvo, custódia de segredo por múltiplos familiares,
  suporte sem famílias e PIN de emergência ainda não implementados;
  compilação Android, testes Node, banco PostgreSQL real e testes em
  aparelhos também não foram executados. Não declarar homologado.


### Primitivo de compartilhamento recuperável (isolado, ainda não liberado)

Foi adicionado `RecoveryThresholdCodec` com partes Shamir 2-de-N em GF(256)
para material de PIN numérico e segredo V3. O algoritmo tem teste Kotlin
isolado de combinação de todos os pares (2-de-5) **executado em JVM local
fora do build Android**, além dos testes JUnit adicionados ao projeto.

**Não há ainda distribuição de partes entre familiares, autorização de
liberação vinculada à recuperação, recriptografia das partes para nova chave,
persistência segura das partes em cada aparelho ou UI completa de PIN esquecido.**
Sem esses componentes, nenhum familiar consegue recuperar PIN/segredo
automaticamente e a função permanece inativa. **Não afirmar que ela funciona.**

A política de papéis impõe um terceiro familiar para reconstrução com duas
partes, caso o familiar que emitia o QR/LINK não possa participar da liberação.
Com exatamente dois familiares, recuperação de identidade com PIN conhecido
pode ser autorizada; **PIN perdido não pode ser recuperado pelo método de
duas partes mantendo a separação estrita exigida**. O produto deve explicar
esta diferença na UI e não habilitar o caminho de esquecimento sem 3 familiares
ou outro fator independente forte pré-configurado.

O primitivo é código criptográfico sensível e **requer auditoria independente**,
além de testes de integração e dispositivos reais antes de ativação.


### Revisão adicional de migração V2→V3 (05/11/2026)

- O fluxo Android agora **não sobrescreve automaticamente o PIN existente**
  ao gerar/reenviar backup ou exportar arquivo. PIN cadastrado é verificado
  sem alteração do registro; um PIN diferente falha antes de cifrar o backup.
  Ao configurar o primeiro PIN, ele só é persistido depois da confirmação
  de replicação ou da exportação cifrada concluída.
- Cada tentativa de backup recebe **versão única reservada em SharedPreferences
  com commit durável**, mesmo que a replicação falhe. Evita impedir retransmissão
  por familiares que guardaram uma tentativa parcialmente entregue.
  O contador de sucesso de backup permanece independente da reserva de versão.
- Foram adicionados testes unitários para **V2→V3 com preservação de mensagens
  privadas, mural e atributos de parentesco/contatos**, abertura retrocompatível
  do cofre V2, falha segura de criação V3 e retries sem sucesso falso.
- **Status: implementado no código, não homologado.** Ainda é necessário
  executar a suíte Android em Antigravity e testar migração/resgate real entre
  celulares com familiares online/offline, perda de ACK e reinicializações,
  antes de autorizar release/Render.
- Ativar Cofre V3 ainda depende de guardar o segredo fora do aparelho;
  **recuperação familiar distribuída do próprio segredo/PIN não está ligada
  ao fluxo de produção**. O módulo de partes limiares continua isolado.


### Continuidade técnica — partes Shamir vinculadas a uma distribuição

O primitivo isolado `RecoveryThresholdCodec` passou a incluir identificador aleatório
de distribuição (16 bytes) e compromisso SHA-256 contextualizado de seu material
(PIN + segredo V3), rejeitando combinação entre distribuições diferentes e
alteração acidental/maliciosa de uma das partes. O formato de parte isolada
mudou de 42 para 90 bytes. Testes JUnit adicionados para rejeitar adulteração
e partes de inscrições distintas. O compromisso **não autentica familiares**:
envelopes autenticados, ligação à sessão/dispositivo, revogação, transporte
cifrado, armazenamento em aparelhos familiares e controle de acesso backend
continuam obrigatórios antes da ativação.

**Não ativar recuperação de PIN/segredo V3 na UI ou em produção.** A função de
distribuição ainda não foi conectada e nenhum PIN/segredo perdido passa a ser
recuperável apenas por este commit. É necessário testar compilação e JUnit no
Antigravity, realizar revisão criptográfica independente e homologação física.

**A publicação no Render permanece bloqueada** até comprovação dos contratos
HTTP/WS, migrações de esquema e compatibilidade do novo APK.
