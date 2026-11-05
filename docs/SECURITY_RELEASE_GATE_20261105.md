# PessoasAqui — Gate de validação criptográfica (05/11/2026)

> **Status: NÃO HOMOLOGADO / NÃO PUBLICAR.** Código na branch `main`; os protocolos HTTP e WebSocket mudaram e o backend novo não é compatível com todos os APKs antigos. Render e Supabase NÃO foram atualizados por esta auditoria. O proprietário controla qualquer deploy.

## Escopo integrado na main

- `CLIENT_MUTUAL_SYNC` não recria vínculos revogados; estado das conexões depende do servidor e o acesso falha diante de indisponibilidade do PostgreSQL.
- `/api/identities/register`: prova ECDSA P-256 obrigatória, sem envio do PIN fixo `0000`.
- `/api/connections/mark`, `/unmark`: provas assinadas vinculadas ao autor, destinatário e operação, com proteção de nonce persistido em PostgreSQL.
- `/api/messages/send`, `/pending`, `/api/presence/heartbeat`, `/nearby`, `/api/connections/status`: requisições assinadas; as leituras confidenciais passaram para POST; GET legado foi bloqueado.
- WebSocket `AUTH`: desafio de 24 bytes por conexão, assinatura ECDSA e validação da chave ativa quando existe vínculo de recuperação.
- E2EE: **novos envios exigem ECDH**; não pode reduzir `ENC_ECDH` a uma chave determinística; servidor não deve decifrar envelopes legados `ENC:`.
- Armazenamento: novos envelopes `ENC_V2:` com Android Keystore AES-256-GCM; leitura histórica V1; PIN pessoal cifrado no armazenamento e verificador PBKDF2 com sal por registro.
- BLE: autenticidade por ECDSA do transcript do respondedor e correspondência entre identidade autocertificada e chave pública.
- Suporte: criação e consulta de chamados do usuário exigem prova de posse da identidade.

## Comandos de validação no Antigravity (Windows)

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
4. Convite LINK+QR: criação, scanner, aceite explícito, expiração, concorrência de resgates, token de uso único; **a correção 1.3.2 do Antigravity está em branch SEPARADA** e não deve ser considerada integrada aqui.
5. Mensagens: ECDH + AES-256-GCM com chave autenticada; retomada de chat com contato offline, após fechar app, recuperar aparelho e alterar chave; proibir plaintext ou downgrade.
6. Recuperação familiar: autorização, emissão de token, uso único, revogação, transferência de identidade e substituição do aparelho antigo; confirmar preservação de backup e histórico.
7. Armazenamento: migração `ENC_V1` para V2 e PIN anterior; corrupção/ausência de chave no Keystore deve falhar sem apagar dados, sem reiniciar identidade, sem aceitar PIN de fábrica.
8. Falha no PostgreSQL: operações não podem confirmar gravações inexistentes; reinício e recuperação dos dados; verificar rotas que retornam cache de memória durante outage.
9. Segurança da sessão: mensagens HTTP/WS com identidades trocadas rejeitadas; leitura de fila e chamados de terceiros rejeitada.
10. Confirmar configuração TLS de Supabase com validação do servidor, em ambiente isolado, não apenas cifragem de tráfego.
11. Auditar provas criptográficas de outros fluxos similares: denúncias, administração, contas legadas, presença, backups distribuídos.

## Limitações explícitas e bloqueadores conhecidos

- Testes backend em espelho local: **44 aprovados antes das últimas alterações de suporte**; testes da `main` atual precisam ser repetidos. Testes majoritariamente com simulações, sem PostgreSQL real.
- Android **não foi compilado nesta sessão**, devido à ausência de SDK Android. A integração completa precisa de Antigravity.
- ECDH depende de obtenção/autenticação da chave pública dos contatos; ausência de chave bloqueia novos envios, e ainda não há comprovação de pinning persistente confiável entre reinicializações.
- BLE autocertificação estrita não cobre automaticamente identidade familiar recuperada com chave rotacionada. Falta um caminho offline seguro de validação.
- O motor legado `ENC:` ainda é decifrável por quem conhece os identificadores; legado é só para leitura. Histórico não equivale a E2EE moderno.
- Derivação de chave dos backups distribuídos, tratamento de PIN em código legado e rotas de denúncia/administração ainda necessitam auditoria adicional.
- Nova API **incompatível com APK legado**; planejar implantação coordenada, janela de atualização ou rota de migração autenticada, não publicar backend sem APK pronto.

**Nenhuma alteração de banco, variáveis de ambiente ou deploy foi realizada nesta etapa.**
