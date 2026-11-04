# PessoasAqui — Auditoria da cadeia de recuperação familiar × autenticação de convites

**Data:** 03/11/2026. **Status:** PROJETO DE CORREÇÃO, **NÃO HOMOLOGADO/NÃO INTEGRADO AO FLUXO FUNCIONAL**.  
**Base auditada:** `3b23141c0513299541c02b5e5f9adc250dbf00f1`.  
**Ambiente protegido:** nenhuma mudança em main/feature, Render, banco ou APK.

## Objetivo original preservado

O usuário que perde, formata ou troca o aparelho pode pedir a **um familiar
previamente autorizado** para aprovar sua recuperação. O familiar não deve
conhecer o PIN nem ler mensagens. Backups de conversas e contatos são
armazenados criptografados em até três aparelhos familiares ou contatos
mútuos de confiança; o novo aparelho deve recuperar o mesmo identificador
e o histórico se disponível. O chat BLE temporário permanece independente.

## Constatações do código real

| Componente | Constatação |
| --- | --- |
| `core/backup/P2PVaultManager.kt`, `VaultContentData` | Cofre cifra **mensagens, mural e contatos** por AES-GCM/PBKDF2; **não salva par de chaves privadas**. |
| `data/repository/PessoasAquiRepository.kt` | `executeDistributedBackup` replica o cofre para até 3 contatos, priorizando familiares; `[VAULT_ACK]` existe, mas `sentCount` é incrementado antes de confirmação de guarda. |
| `core/backup/P2PVaultManager.kt` | PIN de 6 dígitos, PBKDF2 SHA-256 **10.000 iterações**. Esse custo é insuficiente para garantir resistência à força bruta offline de uma cópia vazada. |
| `core/crypto/PinSecurityManager.kt` | PIN tem fallback `0000`, e `vault_pin_encrypted` recebe PIN **em texto puro** em SharedPreferences; comentário não corresponde à implementação. |
| `data/repository/PessoasAquiRepository.kt` | `generateFamilyRecovery` gera autorização **local em ProximitySimulator**, não uma autorização persistente validada pelo servidor. `issueFamilyRecoveryRemote` existe, mas não integra o botão do familiar. |
| `backend/src/server.js` /family/recovery/issue | Emite chave `PA-REC-` com apenas **8 caracteres hex (~32 bits)**, aceita `familyIdentity` fornecida pelo cliente e não verifica credencial criptográfica nem vínculo pré-aprovado. |
| `backend/src/server.js` /family/recovery/claim | Verifica token + PIN; não exige prova de posse da nova chave, não rotaciona chave autorizada e revoga dispositivo antigo mesmo sem transferir titularidade criptográfica. |
| `core/crypto/CryptoIdentityManager.kt` | `setRecoveredIdentity` troca ID técnico preservado, mas mantém a chave EC P-256 criada no novo aparelho; ID antigo não corresponde ao SHA-256 da nova chave. |
| `backend/src/inviteAuth.js` | Intencionalmente rejeita o ID herdado com chave diferente. **Não relaxar** essa checagem sem autoridade persistente e assinatura de recuperação. |
| `data/repository/PessoasAquiRepository.kt` | `queryAndRestoreFromFamilyVault` procura familiares em `discoveredPeople`; novo aparelho pode não descobrir os pares antes de restaurar contatos (circularidade). |
| `app/src/main/java/br/com/pessoasaqui/ui/screens/EntreNaSuaRecoveryScreen.kt` | Recebe o token/PIN, transfere o ID e oferece restauração do cofre depois; a tela não apresenta uma cerimônia de troca de chaves autenticada. |

## Separar 2 recuperações

1. **Identidade/autenticador:** recuperar o mesmo identificador técnico e
   autorizar uma *nova chave pública*, mantendo histórico de épocas de chaves,
   somente após comprovação da aprovação de um familiar anteriormente
   autorizado. Nunca copiar cegamente o identificador e jamais aceitar uma
   nova chave apresentada pelo próprio solicitante sem uma autorização
   independente verificável.
2. **Histórico criptografado:** familiar armazena **somente ciphertext** do
   cofre de mensagens; restauração pode acontecer depois da recuperação da
   identidade. Não equiparar backup do histórico a backup da chave privada.

## Protocolo correto a implementar (fase funcional ainda pendente)

### Preparação enquanto o proprietário ainda possui o aparelho

- Registrar a chave P-256 do proprietário com prova de posse e
  vinculação ao ID original; verificador atual `/api/identities/register`
  não é suficiente porque historicamente aceita chaves sem autenticação.
- Estabelecer autorização de **um familiar escolhido** com consentimento
  verificável do proprietário e assinatura de posse da chave do familiar;
  guardar delegação em registro imutável/único persistente no PostgreSQL.
- Registrar método de recuperação secreto adicional com entropia adequada,
  ou outro fator independente seguro, pois PIN curto sozinho NÃO protege
  cópias offline da chave de identidade. Notificar e permitir revogar
  familiares autorizados.
- Na replicação, comprovar `[VAULT_ACK]` verificado e integridade/autoria;
  não contabilizar mera tentativa de envio como cópia segura.

### Perda, formatação ou troca do aparelho

1. Novo aparelho gera **nova chave EC P-256** localmente.
2. Novo aparelho cria sessão de recuperação pendente vinculada
   a ID original, hash da nova chave, nonce do servidor e validade.
3. Familiar **pré-registrado** aprova a sessão específica com assinatura
   da própria chave em seu aparelho, gerando QR/chave de recuperação de
   alta entropia e uso único com validade limitada.
4. Proprietário confirma com seu fator de recuperação; backend verifica
   prova do familiar, vínculo pré-estabelecido, provas de posse, limitação
   de tentativas e vigência. O familiar nunca vê PIN, chave privada nem
   mensagens; um token conhecido sem aprovação real não basta.
5. Transação PostgreSQL atômica consome autorização, vincula **nova chave
   ativa à identidade anterior**, incrementa geração/época da chave, invalida
   a anterior para todos os endpoints autenticados e revoga a sessão antiga.
6. Registrar trilha auditável, notificar contatos de confiança e suportar
   contestação da recuperação. Se a operação falhar, não revogar o aparelho.
7. Convites LINK+QR validam assinatura contra a chave ativa **autorizada
   pelo servidor** (para recuperadas); o caso normal mantém derivação ID =
   SHA-256 da chave. Nunca usar o registro legado não autenticado como prova.
8. Recuperar o cofre cifrado de contatos confiáveis autenticamente, com
   autorização da nova chave ativa; rejeitar cofre de owner diverso,
   rollback ou origem não autorizada. O familiar não lê o conteúdo.
9. Estabelecer nova sessão E2EE; informar mudança de chave para contatos e
   tratar mensagens antigas destinadas à chave revogada separadamente.

### Migração e falhas fechadas

- Usuários atuais: ativar **enrolment dos familiares enquanto a identidade
  original ainda possui a chave privada**. Sem delegação anterior verificável
  ou outro fator de recuperação pré-provisionado, não declarar recuperação
  da mesma identidade como segura; solicitar revisão assistida ou nova conta.
- Não relaxar `verifyInviteIdentityProof` para aceitar uma chave pública
  arbitrária ou JWT construído pelo próprio cliente.
- Compatibilidade de backups legados: leitura sob PIN legado **somente com
  restrições e após provar titularidade**; planejar upgrade do vault para
  KDF moderno e segredo independente, com recriptografia ao restaurar.
- Clientes antigos sem prova continuam rejeitados para operações sensíveis.
- Proteger o banco em falha: não reabilitar fallback em memória quando
  `DATABASE_URL` estiver configurada.
- Não alterar esta documentação em autorização de deploy.

## Critérios de aceitação

- (A) Um familiar delegado aprova, outro desconhecido é rejeitado;
  (B) token forjado/repetido/expirado rejeitado;
  (C) PIN fraco/padrão não autoriza rotação; tentativas limitadas no servidor;
  (D) nova chave assina CREATE+REDEEM de convites **na identidade antiga**
  após rotação; chave antiga é rejeitada em todos endpoints sensíveis;
  (E) falha no banco deixa vínculo original intacto;
  (F) dois pedidos concorrentes não conseguem reivindicar mesma identidade;
  (G) backup restaurado só para dono autenticado, com integridade e sem
  vazar plaintext ao familiar; (H) sem familiar online há alternativa
  segura com cofre/exportação e código pré-provisionado;
  (I) testes automatizados em Windows/Linux + dispositivos Android 14/16;
  (J) BLE/Modo A e conversas temporárias não sofrem alterações.
