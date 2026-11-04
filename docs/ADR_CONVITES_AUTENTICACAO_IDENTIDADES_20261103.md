# PessoasAqui — autenticação criptográfica para convites LINK + QR (v1)

**Estado:** candidata à homologação. **Não fazer deploy, merge, nem gerar APK nesta etapa.**

## Problema identificado
`POST /api/invites/create` aceitava `senderIdentity` sem prova criptográfica; o
`/api/invites/redeem` igualmente permitia ao portador do token escolher
`receiverIdentity` arbitrariamente. Além disso, a autenticação WebSocket
atual utiliza um nonce escolhido pelo próprio cliente e registro temporário
de chave; não é uma base de confiança adequada para proteger os endpoints.

## Protocolo implementado
- O Android assina cada solicitação com **SHA256withECDSA**, usando a chave
  EC P-256 já existente em `CryptoIdentityManager`.
- A requisição carrega `identityProof: {v:1, publicKey, timestamp, nonce, signature}`.
- Assinatura cobre domínio e versão, operação (`CREATE` ou `REDEEM`),
  ID técnico, alias, ID de convite, SHA-256 do token (no resgate), timestamp
  e nonce aleatório, com campos codificados por comprimento.
- O servidor confere curva, assinatura, janela temporal de ±120 s e
  **vínculo de identidade**: ID técnico deve ser o prefixo de 64 bits do
  SHA-256 do SPKI DER da mesma chave pública usada na assinatura.
- Não existe fallback permissivo, mesmo para clientes antigos.
- Para `CREATE`, o ID do convite deriva de identidade+nonce e a restrição
  UNIQUE de `invites.id` rejeita replay entre instâncias PostgreSQL.
  No modo local de desenvolvimento, o mapa em memória recusa a repetição.
- Para `REDEEM`, a assinatura vincula o resgate ao ID do destinatário;
  o consumo atômico do convite preserva a proteção contra replay.
- Links e QR permanecem idênticos; antigos convites ainda podem ser resgatados
  **por clientes atualizados** que assinem a aceitação.

## Compatibilidade e limite deliberado
- **Clientes Android anteriores:** BLE/Modo A não é modificado, mas as rotas
  de convite passarão a exigir atualização da versão Android. Não se pode
  preservar o resgate inseguro antigo sem restabelecer a vulnerabilidade.
- **Identidades recuperadas ou com chave privada perdida:** se a chave atual
  não corresponde ao ID técnico original, a operação de convite responde
  401. Não se deve aceitar prova de qualquer outra chave por confiar apenas
  em `/api/identities/register`, que historicamente aceitava chave fornecida
  sem autenticação. A transferência segura de chave exige uma etapa própria
  de recuperação autorizada e não está implementada aqui.
- **Não equivale à correção global da autenticação WebSocket e do registro
  de identidades.** O endurecimento de outros endpoints requer auditoria
  própria.
- Clock do aparelho deve estar sincronizado em até dois minutos.

## Testes obrigatórios antes de homologar
`node --test backend/tests/oneTimeInvites.test.js`;
`node --test backend/tests/connectionRevocation.test.js`;
`./gradlew :app:compileDebugKotlin --rerun-tasks`;
`./gradlew :app:testDebugUnitTest --rerun-tasks`.

Testar autenticação do emissor e do destinatário, assinatura adulterada,
chave estranha, expiração, replay idempotente na criação, reutilização de
token, concorrência, legacy com cliente atualizado, banco indisponível,
identidade recuperada (deve falhar de forma segura), e tamanhos de tela.
Executar sempre em worktree isolado. Render, PostgreSQL de produção, main
e feature/proximity-invites-master permanecem inalterados.
