# PessoasAqui — Ofertas BLE e perfis com privacidade progressiva

**Estado:** implementação candidata em branch isolada. **Não compilada/não homologada**; nenhuma alteração de Render, main ou produção.

## Regras obrigatórias da identidade visual

- **Modo A, <= ~10m:** o perfil exibe somente (a) ícone neutro, ou (b)
  o animal selecionado pelo titular. A galeria local contém 8 animais
  originais feitos em vetor, renderizados sem perda em alta resolução e
  **100% offline**. A foto real NÃO trafega no BLE/GATT, nem na oferta,
  nem em heartbeat, nem em endpoints públicos.
- **Modo B, vínculo mútuo confirmado:** o titular pode selecionar foto
  real. Uma miniatura JPEG até 512px é cifrada por E2EE para os contatos
  mutual-confirmed. Não compartilhar a imagem com contatos apenas marcados
  unilateralmente, desconhecidos ou que apenas declaram ser familiares.
- **Disco:** foto real e cópias recebidas são cifradas em repouso por
  AES-256-GCM com chave exclusiva Android Keystore; sem fallback de texto
  puro. Animais selecionados são IDs de oito ilustrações embutidas.
- **Revogação:** o app deixa de exibir foto privada imediatamente, descarta
  a imagem cifrada do ex-contato e não transfere novas versões. Usuários
  maliciosos ainda podem registrar/screenshots anteriores: revogação não
  retira informação já vista.

## Ofertas BLE

- O servidor tem endpoints autenticados por ECDSA P-256/SHA-256 do
  proprietário (`OFFER_PUBLISH` e `OFFER_DELETE`), digest SHA-256 dos
  dados, timestamp/nonce e vínculo à chave ativa autorizada (inclui
  identidades recuperadas), sem fallback permissivo.
- Tabelas aditivas `offers_secure` e `offer_action_nonces`: persistência
  PostgreSQL e replay recusado por chave primária.
- Endpoints antigos de exclusão sem assinatura são rejeitados. Clientes
  antigos requerem atualização para publicar/excluir anúncios seguros.
- O serviço NÃO conhece o alcance físico, portanto
  `GET /api/offers/nearby` não oferece lista mundial de anúncios.
- **Anúncios de terceiros são exibidos apenas se recebidos por canal BLE
  criptografado da identidade rádio detectada**, com intervalo máximo
  12s desde a última varredura e RSSI estimado <= ~10m.
- Um snapshot `[PA_OFFERS_V1:<base64url(JSON)>]` com até 3 anúncios
  é transportado pelo canal GATT já autenticado. Ele não envia foto
  real de perfil ou dados de GPS; oferece nome, profissão, descrição
  breve e URL da oferta. O envio é limitado por taxa a 75s e 3500 bytes.
  Snapshot vazio elimina anúncios antigos quando oferta é excluída.
- Dentro do Radar, selecionar um anúncio abre chat direto BLE (Modo A),
  sem criar relação mútua; o pedido de vínculo permanente é separado.
- Anúncios próprios persistem no aparelho; sem backend de homologação,
  a sincronização online falha com mensagem visível, sem bloquear BLE.

## Limitações que impedem alegar 100% pronto

1. **10 metros é estimativa física a partir de RSSI**; não há
   garantia métrica exata em ambientes com paredes, reflexões e rádios
   heterogêneos (Android 14/16).
2. **Galeria vetorial em alta definição, não banco de fotografias reais**
   de animais. O usuário pode escolher foto real da galeria, visível
   somente a mútuos; o app a reduz para transporte E2EE.
3. **Ofertas BLE v1 levam apenas metadados**, não a imagem original da
   publicação. Imagens HD de anúncios precisam protocolo separado
   fragmentado, sujeito a consentimento e limites de banda BLE.
4. **Máximo de 3 ofertas por snapshot BLE v1**; demais ofertas próprias
   continuam armazenadas. Ampliar depois de validar GATT em dois aparelhos.
5. A persistência online só funciona quando o **backend atualizado e
   PostgreSQL de homologação** estiverem disponíveis; Render não foi usado.
6. Não há garantia de que backups criptografados, convites ou WebSocket
   globais estejam liberados para produção. Verificar riscos já listados.

## Homologação obrigatória pelo Antigravity

- Android: `./gradlew :app:compileDebugKotlin --rerun-tasks`;
  `./gradlew :app:testDebugUnitTest --rerun-tasks`.
- Backend: `node --test backend/tests/secureOffers.test.js`,
  `node --test backend/tests/oneTimeInvites.test.js`,
  `node --test backend/tests/familyRecoveryV2.test.js`,
  `node --test backend/tests/connectionRevocation.test.js`.
- Testes físicos no Zenfone 9 Android 14 e Samsung S24 Android 16,
  ambos sem internet: observar um anúncio remoto enviado por BLE,
  remoção após afastamento, remoção após exclusão, conversa direta do
  anúncio sem convite, falha de outro ID de origem.
- Galeria: sem animal, cada animal em 320dp e tablet, foto real escolhida
  no dispositivo A; B desconhecido nunca a vê; B mutual-confirmed vê
  foto após entrega E2EE; revogar, validar ocultação e exclusão local.
- PostgreSQL descartável: publicar/editar/excluir com assinatura,
  adulterar anúncio, forjar autor, replay, expiração e banco indisponível.
- Não tocar em Render, main, feature/proximity-invites-master; nenhum
  deploy ou APK de produção. Não alegar sucesso sem logs dos testes.
