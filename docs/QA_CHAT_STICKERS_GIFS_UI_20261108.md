# PessoasAqui — Homologação UI Chat, Figurinhas e GIFs (08/11/2026)

## Escopo implementado (após marco estável 1.3.3)
- Cabeçalho de contato mútuo: "Conexão Mútua", online/offline/última visualização. Não exibir "Qualquer distância" no título.
- Retirado o banner verde persistente de confirmação, pois eventos pontuais de vínculo já estão no histórico. Resgate de link com vínculo anteriormente mútuo não produz aviso duplicado.
- Campo de mensagem e descrições de mídia com redação acessível: "🔒 Criptografada" (ou concordância masculina); cadeado e recibos preservados.
- Picker de emoji/figurinhas ampliado em relação à tela, com abas explícitas Emojis, Figurinhas e GIFs. Acervo de figurinhas local mantido por StickerPersistenceManager, com criação, edição, exclusão e envio já existentes.
- Aba GIF: importar arquivo GIF local (até 8 MiB, GIF87a/GIF89a, até 2048x2048), enviar pela rota E2EE de documentos sem achatar a animação; visualizar inline por ImageDecoder nativo em Android 9+ e abrir no visor externo com MIME image/gif. O editor de GIFs próprios, catálogos/adesivos estilo MSN e pesquisa de GIFs online NÃO foram implementados; não afirmar que estão prontos.

## Não alterar
- Branch snapshot/pessoasaqui-1.3.3-field-approved-20261108.
- Contratos backend de invitação LINK+QR, conexão bilateral, revogação, BLE temporário.
- Chaves, keystore, esquema Supabase e identidade visual do aplicativo.

## Testes obrigatórios de Antigravity — somente leitura, sem correções
1. Compilar `:app:compileDebugKotlin`; executar `:app:testDebugUnitTest` e reportar contagens.
2. Conferir `ChatDetailScreen` em largura reduzida e grande, teclado aberto/fechado, orientação e dispositivos Android 14 e 16; garantir que a barra de envio não fique oculta e a área de mensagens continue rolável.
3. Painel de figurinhas: abrir, alternar abas, salvar nova figurinha, fechar/reabrir, confirmar visibilidade no acervo; editar, excluir e enviar. Não duplicar envios.
4. GIF: seleção e envio de arquivo animado de múltiplos frames; recebimento, reprodução inline e abertura externa; imagens GIF com assinatura falsa, dimensões inválidas, superiores a 8 MiB e com arquivo indisponível devem falhar sem crash/OOM. Testar envio a contato não mútuo como operação recusada.
5. Confirmar que texto comum, imagens, documentos e vídeo continuam operando com E2EE e com estados de entrega corretos.
6. Confirmação mútua: aviso aparece como evento de chat apenas após nova conexão; ao reabrir ou repetir reconciliação, não se duplica. Testar após revogação e nova aceitação.
7. Executar 27 suítes do backend para regressão; nenhuma alteração em produção.
8. Se os testes e a compilação passarem, gerar APK debug para homologação e registrar SHA-256, versão, commit, assinatura. Não publicar nem fazer deploy.

## Critério de aceite e limitações
A alteração ainda não está homologada: apenas commits feitos. Não prometer estabilidade ou publicação até validação independente. Para edição/criação de GIFs animados, elaborar proposta de implementação reutilizando a arquitetura de documentos, stickers e renderizador, sem instalar SDK externo ou fazer pesquisa em terceiros sem revisão explícita de privacidade.
