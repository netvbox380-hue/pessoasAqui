# PessoasAqui — Radar BLE e sinalização de chamadas (branch de validação)

Este documento descreve as invariantes implementadas na branch
`fix/pessoasaqui-ble-call-fsm-20261103`. **Não significa homologação física.**
Não realizar merge/deploy sem validação da equipe e dos dois aparelhos.

## Invariantes do Radar

- **Modo A**: anúncio/scan Bluetooth LE e GATT direto, sem Render, internet,
  convite mútuo ou persistência de chat temporário.
- Identificação de anúncios por **Service Data** UUID `0xFA01` — não confundir
  com a lista de Service UUIDs. Filtro usa padrão de comprimento zero para
  aceitar qualquer payload autenticável do aplicativo. Validação de identidade
  e RSSI permanece no parser local.
- **Activity visível**: `ScanCallback` com baixa latência.
- **Segundo plano**: `PendingIntent` explícito e não exportado; scan filtrado
  balanceado com resultados agrupados e receiver com `goAsync` serializado.
  O Android pode acordar o processo; nunca iniciar FGS por broadcast.
- **FGS**: tipo `connectedDevice` no Android 14+ com requisito de permissão;
  app visível inicia o serviço. `dataSync` só é fallback quando necessário.
- O gerenciador distingue `STOPPED/STARTING/ACTIVE/FAILED`, reage a callbacks,
  ao status do adaptador e à visibilidade. Falhas são recuperadas com backoff
  exponencial limitado. **Ausência de peers não é falha de rádio**.
- Retorno do background pode reinscrever o scanner; não reinicia o GATT ou
  anuncia novamente se os componentes estão saudáveis.
- Perda de Wi-Fi/Render não apaga descoberta física; sessão temporária expira
  pelo tempo local de ausência (~45 s). RSSI não garante fronteira exata de 10 m.
- Permissões Android reais são rechecadas, sem tratar um flag persistido como
  autorização atual.
- Receiver BLE passivo não inicia polling do backend; presença da internet é
  iniciada por UI ou serviço em primeiro plano ativo.

## Invariantes de chamadas do Modo B

- Chamadas de áudio/vídeo exigem vínculo mútuo permanente confirmado.
- Envelope de sinalização deve estar autenticado por E2EE. Nenhum `ENC_ECDH`
  falho ou comando `CALL_` deve ser exibido nem salvo como mensagem do usuário.
- Novo protocolo usa `callId` UUID compartilhado e timestamp de emissão
  (convites fora da janela são ignorados). O parser aceita formato legado para
  migração gradual, sem enviar comandos novos desconhecidos aos clientes legados.
- Estados separados: `OUTGOING`, `RINGING`, `INCOMING`, `ACTIVE`;
  terminal idempotente. Um evento de peer ou ID divergente não muda a sessão.
- Protocolo tipado: `INIT`, `RINGING`, `ACCEPT`, `ACK`, `END(reason)`.
  Chamadas recusadas, canceladas, ocupadas, não atendidas e perdidas têm
  tratamento distinto. Um pacote de mídia não pode responder à ligação.
- V2 inclui `callId` também nos frames de áudio/vídeo, evitando misturar
  quadros atrasados de outras ligações. Cliente legado recebe formato legado.
- Retransmissões de CALL_INIT/ACCEPT/END usam o mesmo `callId`.
  CallIds encerrados ficam em um cache de replay (até 128, ~10min), com IDs
  aleatórios persistidos em SharedPreferences; sem armazenar conteúdo/chaves.
- Histórico de chamadas: exatamente um evento `SYSTEM` local por participante
  e por `callId`, com descrição legível. O chat permanente continua usando
  persistência criptografada. A outra ponta não recebe uma bolha de sistema.
- Temporizador de ausência de atendimento de 45 segundos, cancelado após
  atender/encerrar. Encerrar o vínculo termina a chamada antes de revogar.

## Limitações assumidas e NÃO corrigidas silenciosamente

- Android pode restringir scan/anúncios e matar processo em Doze ou com bateria
  restrita. `PendingIntent` não revive aplicativo **forçadamente parado** pelo
  usuário. Reentrada deve funcionar, mas continuidade permanente não é garantida.
- Distância inferida por RSSI oscila com aparelhos, paredes e interferências.
- Protocolo legado sem `callId` não possui a mesma proteção contra atraso;
  atualizar ambos os aparelhos é indispensável para homologar V2.
- `CallMediaEngine` permanece no transporte de mídia existente; **WebRTC
  com ICE/STUN/TURN ainda não foi integrado**. Não confundir melhorias de
  sinalização com homologação da qualidade de áudio/vídeo.
- O mecanismo E2EE legado de derivação determinística ainda requer plano de
  migração criptográfica versionado; removê-lo imediatamente quebraria
  clientes/históricos antigos.
- Nenhuma mudança no Render/PostgreSQL, nenhum deploy e nenhum merge nesta
  branch. A estabilidade depende de compilação, testes e homologação física.

## Homologação obrigatória

1. Compilar Kotlin, executar testes prioritários de BLE e protocolo de chamadas,
   testes Android completos, testes backend e gerar APK **somente se tudo passar**.
2. Zenfone 9 Android 14 ↔ Galaxy S24 Android 16, ambos com mesma versão nova.
3. Wi-Fi + dados móveis desligados: descoberta bilateral e chat temporário BLE.
4. Minimizar/bloquear 10 e 30 minutos; retorno ao radar sem fechar app nem
   desligar Bluetooth. Testar também tela ligada, Bluetooth alternado e processo
   terminado pelo sistema (não force-stop).
5. Modo B com internet: chamada de voz/vídeo recebida, feita, perdida,
   não atendida, cancelada, recusada, ocupada; timeout, duplicação, atraso,
   interrupção de rede e duas tentativas simultâneas.
6. Confirmar histórico sem `ENC_ECDH`, sem comandos internos e sem duplicação,
   preservando contatos e conversas anteriores.
7. Em caso de falha, manter a branch e retornar logs à equipe; **não implantar**.
