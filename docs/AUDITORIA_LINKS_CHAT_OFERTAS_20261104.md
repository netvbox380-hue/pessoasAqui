# PessoasAqui — Links clicáveis no chat e auditoria funcional da aba Ofertas

**Base da revisão:** `026cebff83cc0f7dc9e62f70a5319d8989e96916`.  
**Status:** correções locais integradas em branch isolada, **não compiladas nem homologadas no APK**.  
**Render, main, feature/proximity-invites-master e banco de produção:** inalterados.

## 1. Links compartilhados no chat

Já existia `LinkifiedMessageText` no `ChatDetailScreen`, que abria
`http(s)://` e `www.` com `LocalUriHandler.openUri`. Faltava reconhecer
endereços comuns sem esquema (como `loja.com.br/oferta`) e remover sinais
de pontuação no fim da frase.

- `WebLinkPolicy.findWebLinks` extrai e localiza URLs HTTP(S), WWW e
  domínios comuns sem prefixo, preservando os offsets no texto.
- `normalizeWebUrl` aceita apenas HTTP(S); rejeita esquemas perigosos,
  credenciais embutidas, URLs malformadas, controle e espaços. Substitui
  `www.`/domínio sem esquema por `https://`.
- `ChatDetailScreen.LinkifiedMessageText` utiliza o helper, anota links
  no texto, abre o link tocado e apresenta um botão de prévia para o primeiro.
- O toque não envia mensagens ou abre convite automaticamente; o Android
  decidirá qual navegador/aplicativo pode abrir aquele endereço.
- O usuário escolhe se abre URLs recebidas; não presumir confiabilidade
  de conteúdos externos. A verificação da interação física ainda é necessária.

## 2. Aba Ofertas: conexões já existentes

`MainScreen` já passava `offers`, `onPostOffer`, `onUpdateOffer`,
`onDeleteOffer` e `onToggleMarkAuthor` para `OffersTab`.

`PessoasAquiRepository` já salvava as próprias ofertas em
`SharedPreferences` e tentava sincronizar via
`PessoasAquiNetworkClient.publishOffer/deleteOffer/fetchNearbyOffers`.
O backend possui `/api/offers/publish`, `/api/offers/nearby`,
`DELETE /api/offers/:id` e emite eventos WebSocket.

### Correções pontuais realizadas

1. Botão **Conversar por Bluetooth** abre o chat do profissional **somente
   se ele estiver no Radar e houver presença BLE nativa fresca**. Não exige
   convite mútuo para a sessão temporária (Modo A).
2. **Solicitar conexão permanente** chama `setMarkPerson(mark=true)`,
   impedindo o antigo `toggleMarkPerson` de desmarcar um vínculo por
   engano. Só exibe a ação quando o profissional está realmente no Radar.
3. **Editar/Excluir** anúncios próprios na UI usa identidade técnica
   exata; não compara apelido ou um selo `Seu anúncio`.
4. Links externos passam por `WebLinkPolicy` antes da publicação,
   edição e abertura; falhas exibem mensagem em vez de falhar silenciosamente.
5. O rótulo **10m** foi substituído por presença BLE observada ou
   **Anúncio da rede**. Publicar pelo backend não comprova distância física.
6. Datas de publicação são decodificadas do servidor para diminuir
   recomposições desnecessárias e alterações remotas existentes atualizam a UI.

## 3. Limitações importantes NÃO resolvidas nesta branch

**A. Ofertas offline entre aparelhos (bloqueador funcional):** o app ainda
NÃO troca anúncios com outros celulares via BLE/GATT. Sem Render ou backend
local, cada usuário consegue ver e editar seus próprios anúncios salvos no
aparelho, mas NÃO distribuir ou descobrir anúncios de terceiros automaticamente.
Não confundir o chat BLE, que está conectado, com descoberta BLE de ofertas.
Desenhar e homologar protocolo de anúncios BLE independente e limitado
em tamanho antes de afirmar que a aba é offline.

**B. Segurança das rotas (bloqueador de produção):** o backend aceita
`authorId` fornecido pelo cliente no POST e qualquer ID no DELETE,
sem autenticar a chave do proprietário. Um cliente malicioso poderia criar,
substituir ou excluir publicações de outros. A correção exige prova ECDSA
domínio-separada, vínculo com chave ativa da identidade e autorização de
deleção. Não ativar o endpoint atual como sistema de ofertas público.

**C. Alcance (bloqueador da promessa 10m):** `GET /api/offers/nearby`
retorna ofertas da instância do backend sem filtragem por posição física:
o servidor não conhece o rádio BLE. Broadcast WebSocket alcança todos os
clientes conectados, não só os fisicamente próximos. O texto da UI não deve
prometer que essa lista é exclusivamente de pessoas a 10m.

**D. Persistência e sincronização:** `activeOffers` é um `Map` em
memória, não PostgreSQL; reiniciar o servidor apaga essa lista. Não existe
comprovação de confirmação de publicação no servidor para o usuário,
sincronização robusta após reinício nem garantia de ordenação de offers.

**E. Arquivos de imagem:** a foto via Base64 é enviada em toda publicação,
sem limite validado pelo servidor de ofertas. Adotar tamanho máximo,
validação de MIME e proteção contra abuso antes de liberar produção.

## 4. Homologação recomendada ao Antigravity

- `./gradlew :app:compileDebugKotlin --rerun-tasks`.
- `./gradlew :app:testDebugUnitTest --rerun-tasks` (inclui
  `WebLinkPolicyTest`, 7 novos testes).
- `node --test backend/tests/oneTimeInvites.test.js`,
  `node --test backend/tests/familyRecoveryV2.test.js`,
  `node --test backend/tests/connectionRevocation.test.js`.
- Android 14/16: mensagem com `https://exemplo.com`, `www.site.com`,
  `site.com/caminho`, URL com pontuação, URL malformada, emoji e texto
  com várias URLs; tocar e abrir navegador. Testar seleção/estrela sem
  regressão, inclusive tela pequena e retorno da atividade de navegador.
- Ofertas: publicar, editar e excluir apenas anúncios próprios; validar
  link WhatsApp/Instagram/portfólio; confirmar erro de URL.
- Dois celulares sem Render: publicar oferta no A **NÃO** deverá aparecer
  automaticamente no B (limitação documentada), mas botão Conversar no
  anúncio de B só deve aparecer quando B está no Radar BLE real.
- Banco/servidor descartáveis: verificar publicação/edição/exclusão
  enquanto conectados e registrar riscos de autenticação.
- **Não gerar APK para produção.** Produzir APK debug novo apenas após
  compilação e testes; nunca realizar merge ou deploy.
