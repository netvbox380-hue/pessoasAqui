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
