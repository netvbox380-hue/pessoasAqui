# PessoasAqui — Layout adaptável, mensagens importantes e figurinhas

Status: **candidato a homologação, não aprovado para produção**.

Branch isolada: `fix/pessoasaqui-adaptive-important-stickers-20261103`.
Base: `2c21096e9fae45fd81a2d72e23ee0c10c50531e8` (202/202 testes anteriores).

## Princípios de arquitetura

* Medir o tamanho **da janela disponível** (`BoxWithConstraints`), sem usar
  dimensões físicas, DPI fixo ou travar orientação. NavigationBar em largura
  compacta e NavigationRail a partir de 600dp.
* Manter conteúdo legível centralizado e com largura máxima em janelas grandes;
  preservar rolagem quando altura, teclado ou escala da fonte reduzirem espaço.
* Bolhas de chat com largura proporcional à janela (máximo 560dp);
  editor de figurinhas, lembretes, suporte, convites, backup e planos com limites
  de largura adequados.
* Não alterar cores oficiais, identidade visual, criptografia, convenções de
  navegação do aplicativo, backend, banco ou contratos de transporte.
* Candidata a evolução futura: leiaute canônico **list-detail** em tablets e
  dobráveis abertos; requer testes de estado ao redimensionar antes de liberar
  uma mudança estrutural na navegação.

## Inbox de mensagens importantes

A aba Conversas exibe:
1. Destaques pessoais com estrelas, texto/preview seguro, contato e data.
   Quatro mensagens visíveis inicialmente e comando para ver todas.
2. Conversas com mensagens importantes (ordenadas por atividade);
3. Conversas com mensagens de usuário não lidas;
4. Demais conversas lidas/sem mensagens, por atividade.

O canal de suporte oficial participa da mesma ordenação; não fica mais fixado
acima das conversas não lidas. Status de chamada e eventos de sistema não contam
como mensagens de usuário não lidas.

Mensagens apagadas e envelopes `ENC_ECDH:` inválidos nunca são destacados.
Chaves de peer alternativas são normalizadas e IDs de mensagem são deduplicados.
A origem continua sendo a coleção local já criptografada; a priorização não
transmite dados a Render. Somente contatos do **Modo B** e o canal de suporte
são persistentes nesta aba. Não modificar a regra de expiração do Modo A.

Na caixa da conversa, ações existentes de marcar/desmarcar e copiar continuam
disponíveis. Um comando **Agendar alerta** inicia o fluxo de lembretes existente
com o texto pré-preenchido, mas não agenda notificações automaticamente.
Usuário escolhe horário e confirma o agendamento.

## Figurinhas (criar, editar, excluir)

* Biblioteca local privada legada (`filesDir/user_stickers`) preservada.
* IDs passam a usar UUID, evitando colisões de milissegundos.
* PNG 512x512 transparente; imagens não quadradas são enquadradas sem esticar.
* Escrita com arquivo temporário e backup; edição substitui a versão local;
  exclusão requer confirmação e usa transação recuperável.
* ID validado antes de acessar caminhos, arquivo decodificado só quando seguro;
  bibliotecas grandes geram miniaturas de 128px no worker de IO.
* Pressão longa na figurinha: editar ou excluir. Toque simples: enviar.
* Exclusão/edição do acervo não apaga nem altera mensagens já enviadas,
  cujos payloads são independentes.
* Acervo tem limite de 180 itens para restringir memória/armazenamento.
  Mensagem explícita quando atingir limite.

## Matriz de homologação obrigatória

Executar no Android 14 e 16, com verificação **visual**:

| Janela | Orientação/estado | Casos |
| --- | --- | --- |
| 320x568 dp | retrato compacto | menus, texto 200%, rolagem, teclado, botões |
| 360x800 dp | retrato comum | cadastro, Radar, Conversas, Ofertas, chat |
| 600x960 dp | largura média | navigation rail e conteúdo responsivo |
| 840x1180 dp | tablet/aberto | textos, cards, máximo de largura |
| 1280x800 dp | paisagem expandida | diálogos, edição, menus, insets |
| 450x720 dp | tela dividida | resize durante chat e edição |
| 800x360 dp | paisagem compacta | scroll de botões/formulários, teclado |

Também validar:
- Fonte 100/150/200%, navegação de três botões e gestos;
- Multiwindow, rotação, troca de tema/locale e teclado em primeiro plano;
- Iniciar em Conversas após reabertura e destacar estrelas persistidas;
- Marcar qualquer mensagem, desmarcar, editar/apagar, voltar, reiniciar;
- Ordenação: importante > não lida > lida, incluindo suporte;
- Mensagens vindas da internet sem novas estrelas;
- Modo A BLE temporário não grava estrelas permanentes;
- Criar 10 figurinhas rapidamente sem colisão, editar uma no mesmo ID,
  excluir com confirmação, cancelar exclusão, enviar antes/depois de editar;
- Não alterar stickers já enviados; imagens grandes e PNG corrompido;
- Biblioteca legada mantém os arquivos/IDs existentes;
- Reexecutar regressões de BLE, chamadas, revogação, E2EE e backend.

## Gate de release

Antigravity: worktree isolado, **somente leitura/build/testes**, proibido editar.
Executar `compileDebugKotlin`, `testDebugUnitTest`, testes backend, depois
`assembleDebug`. Gerar log, caminho/SHA do APK. Não instalar sem plano de
preservação de dados nem mesclar/atualizar Play Store ou Render.

Aprovação automatizada **não** comprova a qualidade visual, legibilidade com
fonte grande nem preservação de estado em dispositivos físicos. Qualquer falha
deve ser registrada antes da promoção para produção.

Referência: https://developer.android.com/develop/adaptive-apps/guides/adaptive-dos-and-donts
