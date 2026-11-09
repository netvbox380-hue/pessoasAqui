# PessoasAqui — GIF Studio local: implementação e homologação pendente

**Base:** main @ `8a869783d5e0913d33b61a1ebe44b9f0b09828cd`  
**Snapshot de segurança (não alterar):** `snapshot/pessoasaqui-1.3.3-field-approved-20261108` (`c0eac494710905815b20be0e41e8d02be6b5b310`)

## Integração entregue

- `PrivateGifStudio.kt`: frames de fotos (máximo 18), extração de vídeo local de até 15 s/40 MiB, leitura de GIFs, criação GIF89a LZW/RGB332 local com loop; legenda; validação e limite de 8 MiB; acervo em `filesDir/private_gif_studio`; thumbnails locais.
- `LocalGifEditorDialog.kt`: prévia, velocidade, legenda, ordem e exclusão de quadros, salvar localmente; rolagem em telas compactas.
- `ChatDetailScreen.kt`: criação por fotos/vídeos, edição de GIF importado ou salvo, listagem e envio pelo mesmo canal E2EE de documentos usado anteriormente; sem novas APIs/serviços externos.
- `PrivateGifStudioInstrumentedTest.kt`: verificação de GIF codificado, decodificável, limitado e privado.

## Para o Antigravity — homologação independente, somente leitura

1. Confirmar HEAD da main, integridade do snapshot, ausência de modificações em identidade visual, Render, Supabase, identidade E2EE e configurações de assinatura.
2. Executar `:app:compileDebugKotlin`, `:app:testDebugUnitTest`, `:app:assembleDebug` e `:app:connectedDebugAndroidTest` quando houver dispositivo. Reportar números reais e logs; se ambiente indisponível, indicar explicitamente.
3. No Zenfone 9 (Android 14) e S24 (Android 16): criar GIF com 2 a 18 fotos; rearranjar/excluir quadros, editar legenda e velocidade; salvar; reabrir acervo; editar GIF salvo; excluir sem apagar histórico prévio.
4. Criar GIF a partir de vídeo de 3–15 segundos e importar GIF existente para edição; confirmar preservação de frames, sem travamentos e limite de 8 MiB.
5. Enviar GIF salvo entre dois contatos mútuos com E2EE existente; verificar transmissão bilateral, recepção offline/online e reprodução inline. Testar bloqueio de envio sem vínculo.
6. Testar dispositivo com pouca memória, arquivos corrompidos, MIME falso, vídeo com duração excedida e biblioteca cheia; sem falhas permanentes e sem uploads para serviços externos.
7. Verificar que o painel de emojis, figurinhas e GIFs e o teclado continuam responsivos em telas pequenas, com fonte ampliada.
8. Não corrigir código durante a homologação. Entregar relatório reproduzível com commit, erros e testes executados.

**Status:** código integrado, aguardando compilação e homologação independente; nenhum teste Android foi executado nesta sessão.
