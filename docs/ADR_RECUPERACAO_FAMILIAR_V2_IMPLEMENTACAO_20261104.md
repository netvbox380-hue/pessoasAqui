# PessoasAqui — Recuperação familiar V2: implementação candidata à homologação

**Status: implementada em branch isolada, NÃO HOMOLOGADA, NÃO APROVADA para produção.**
Nenhum deploy, merge, acesso ao Render ou PostgreSQL de produção. APK proibido nesta etapa.

## Objetivo e arquitetura

A recuperação de identidade e a restauração do histórico são processos distintos:

- **Identidade:** a chave privada original perdida não está no backup. O novo
  Android gera outra chave EC P-256 e a assina para demonstrar sua posse.
- **Delegação prévia:** o dono assina com SHA256withECDSA uma autorização para
  familiar específico. Chave do familiar é validada como self-certifying ou
  como chave ativa após recuperação já registrada no servidor. A delegação
  fica em `recovery_delegations` e tem nonce anti-replay persistente.
- **Emissão pelo familiar:** familiar autenticado assina
  `RECOVERY_ISSUE` para a identidade do dono. O servidor exige delegação
  ativa e responde código de 256 bits `PA-REC-<64 hex>`, com 15 minutos
  de validade. Só o SHA-256 do código fica no PostgreSQL.
- **Revogação explícita do familiar:** o titular assina
  `RECOVERY_REVOKE`; o servidor desativa a delegação e invalida os códigos
  pendentes em uma única transação. A autorização de recuperação pode ser
  desativada sem desfazer a conexão familiar ou o chat.
- **Reivindicação no aparelho novo:** assina `RECOVERY_CLAIM` vinculando
  a própria identidade recém-criada ao hash do código. O servidor confere
  token, uso único, validade, vínculo familiar vigente e geração da
  identidade. Em transação PostgreSQL com bloqueios de linha por grant e
  identidade, registra a nova chave, invalida grants antigos e revoga os
  dispositivos anteriores.
- **Convites LINK+QR:** passam a conferir a assinatura contra a chave
  ativa persistente em `identity_key_bindings`. Usuários não recuperados
  ainda exigem vínculo hash-chaves normal, exatamente como antes.
- **WebSocket:** AUTH da identidade recuperada exige prova da chave ativa;
  operação falha fechada quando PostgreSQL não está acessível.
- **Modo A BLE:** permanece isolado de alterações e não exige servidor.
- **QR de recuperação:** utiliza o mesmo scanner Google Code Scanner do
  fluxo LINK+QR, com fallback de copiar/colar código.

### Operações assinadas, com separação de domínio

Usa `InviteIdentityProof.canonical` + ECDSA P-256 / SHA-256, com operações
`RECOVERY_DELEGATE`, `RECOVERY_ISSUE`, `RECOVERY_CLAIM` e nonce e
timestamp ±120 segundos. A operação de delegação vincula identidade familiar
e hash da chave pública; a emissão vincula o alvo; a aceitação vincula o
segredo e a nova identidade. Nunca aceitar mera declaração de identityHash.

### Esquema e migração

Adicionadas tabelas (sem modificar existentes):
`identity_key_bindings`, `recovery_delegations`,
`recovery_proof_nonces`, `recovery_grants`. O servidor tenta provisionar
essas tabelas no bootstrap. A autorização e a transferência exigem
PostgreSQL; NÃO usar memória volátil como substituto em ambiente real.
`backend/schema.sql` reproduz o DDL aditivo.

**O usuário precisa autorizar previamente seus familiares enquanto dispõe
da identidade original**. Familiares antigos podem ser credenciados pelo
novo botão explícito na conversa, desde que as duas identidades estejam
autenticáveis. Não inferir autorização somente do rótulo familiar local.
Solicitações `FAMILY_ACCEPT` não solicitadas deixam de criar vínculo.

### Compatibilidade e restrições de segurança

- **Clientes e códigos de recuperação antigos:** o protocolo curto de 8
  caracteres (~32 bits), PIN padrão ou autorização local do simulador NÃO
  podem transferir contas. Aplicativos antigos precisam atualizar-se para
  usar a recuperação V2. A compatibilidade insegura não deve ser reaberta.
- **Backups antigos:** cofre AES-GCM/PBKDF2 e restauração permanecem legíveis
  com o PIN correto, se o familiar estiver acessível; não armazenam
  automaticamente as chaves privadas de identidade.
- **PIN do backup:** continua destinado à abertura do cofre. A transferência
  de identidade V2 **NÃO autentica o proprietário com o PIN**, por causa do
  PIN legado `0000` e do registro do backend que nunca verificou sua
  titularidade. A autorização do familiar + código secreto de 256 bits
  é a autoridade. **Consequência:** familiar delegado malicioso que emita e
  controle a chave temporária pode transferir a conta; exige fator
  independente do titular ou confirmação da nova chave pelo familiar numa
  versão futura antes de aprovar produção.
- **Segurança do cofre:** backups legados usam PIN curto, PBKDF2 10.000
  iterações e `PinSecurityManager` mantém PIN em SharedPreferences;
  esses riscos são **não resolvidos** nesta etapa. Não declarar garantia de
  que um familiar com ciphertext não possa tentar força bruta offline.
- **Autenticação global:** rotas HTTP antigas e AUTH WebSocket para
  identidades não recuperadas precisam de auditoria distinta.
- **Dispositivos e replicação:** contador de backups enviados não equivale
  a confirmações independentes de armazenamento; cenário offline ou família
  sem chave pública acessível deve falhar de maneira clara.
- **Reconexão e sessões simultâneas:** notificação de revogação WebSocket
  é feita por processo. Uma implantação com múltiplas instâncias necessita
  invalidação global de sessões para todas as rotas sensíveis.
- **Sem familiar previamente delegado e sem outro fator provisionado, a
  recuperação criptográfica segura do ID antigo não pode ser garantida.**

## Homologação obrigatória

1. `./gradlew :app:compileDebugKotlin --rerun-tasks`.
2. `./gradlew :app:testDebugUnitTest --rerun-tasks`.
3. `node --test backend/tests/familyRecoveryV2.test.js`.
4. `node --test backend/tests/oneTimeInvites.test.js`.
5. `node --test backend/tests/connectionRevocation.test.js`.
6. Banco **PostgreSQL descartável**, nunca produção: migrations, autorização,
   concessão e rotação atômicas, corrida de grants, falha de transação,
   reinício do backend, chave antiga rejeitada em convites e WebSocket.
7. Android Zenfone 9 / S24: recuperação com QR, fallback manual,
   autenticação pós-recuperação, backup disponível e indisponível.
8. Segurança: convite pré/pós recuperação, família desconhecida,
   família revogada, solicitação solicitada e não solicitada,
   token antigo/forjado/repetido/expirado, prova adulterada,
   identificador não autocertificado, relógio fora de janela.
9. Garantir código/recursos UI em 320/360/600/840dp e fonte 200%.

**Não publicar.** Aprovado em testes unitários NÃO significa pronto para
produção: as ressalvas de fator independente, backup legado e autenticação
global são bloqueadoras para uma liberação segura.
