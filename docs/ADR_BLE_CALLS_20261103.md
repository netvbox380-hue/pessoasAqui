# ADR — Descoberta BLE e sessões de chamadas (2026-11-03)

## Escopo e reversibilidade
Mudanças isoladas na branch `fix/pessoasaqui-arquitetura-ble-chamadas-20261103`,
partindo da versão anterior `ba26e1425b9370e2ee67882848fa1eccedce0bf2`.
Não migrar nem implantar servidor, PostgreSQL, Render ou main sem homologação.
O comportamento em aparelhos físicos não está comprovado por testes JVM.

## Descoberta BLE (Modo A)
- Usar filtro de **service data 0xFA01**, correspondente ao formato realmente
  anunciado, não o filtro de UUID da lista de serviços. Manter o parser
  existente e a identidade técnica como chave de descoberta.
- O Android suspende buscas BLE **sem filtro** com tela apagada; o filtro
  precisa ser validado no Galaxy S24 Android 16 e Zenfone 9 Android 14.
- Alterar modo de scan por eventos de `Activity.onStart/onStop`:
  `LOW_LATENCY` quando visível, `BALANCED` quando em segundo plano.
  Não reiniciar GATT em cada transição da Activity.
- Renovação de scan somente em transições de ciclo de vida, mudança do
  estado do adaptador ou falha real notificada pelo sistema. Falhas devem
  usar backoff limitado. Nenhum restart incondicional a cada 90 segundos.
- `ForegroundService` com tipo `connectedDevice` no Android 14+,
  observando permissões e limitações de inicialização em segundo plano.
  **Não há garantia de varredura ininterrupta quando o processo morre**.
- A política de 45 s de expiração de sessões temporárias não deve ser usada
  como indicador de falha do scanner: ausência de pessoas não prova falha.
- Dados de presença pela internet nunca revogam presença física nem alteram
  o relógio de último anúncio BLE. Wi-Fi e Render não são dependências do Modo A.

## Sessão de chamada (Modo B)
- Um `callId` UUID, separado do ID de envelope/mensagem, correlaciona
  convite, aceite, confirmação e encerramento.
- Compatibilidade de wire com clientes existentes:
  - `[CALL_INIT:true:alias:callId]` (3º campo opcional);
  - `[CALL_ACCEPT]#callId`, `[CALL_ACK]#callId`, `[CALL_END]#callId`.
  Clientes antigos continuam detectando os prefixos; clientes novos
  recusam eventos com callId não correspondente.
- Máquina de estados de sessão valida direção, remetente, IDs e transições.
  Apenas aceite explícito do destinatário conecta uma chamada. Mídia ou
  ACK recebido antecipadamente não equivale ao aceite.
- Eventos duplicados e atrasados não devem abrir histórico repetido nem
  encerrar a sessão de outra ligação.
- Resultados em histórico local criptografado: realizada, recebida,
  perdida, recusada, cancelada e não atendida.
- `ENC_ECDH:` não decifrado não pode virar texto. Conteúdos antigos com
  prefixo cifrado são ocultados na UI sem apagar dados do disco.
- A chamada só existe para contatos confirmados em Modo B. O Modo A BLE
  segue temporário, sem chamada, sem servidor e sem convite obrigatório.
- Media engine atual é transporte próprio (não WebRTC). Migrar para
  WebRTC/Core-Telecom requer projeto de compatibilidade e homologação
  separados; não alterar a mídia e a sinalização simultaneamente.

## Riscos residuais / gates
1. **Compilar e executar todos os testes Android**, não apenas testes
   novos. Compilar APK sem teste não prova entrega Bluetooth.
2. Testar BLE com **Wi-Fi e dados móveis desligados** por 1, 10, 30 e
   120 minutos, com ambos aparelhos minimizados/bloqueados e depois abertos.
3. Verificar scanner filtered real em ambos chipsets; registrar
   `onScanFailed`, `onStartFailure`, GATT connection/CCCD e eventos do
   `ForegroundService` em Logcat, sem dados pessoais.
4. Chamadas: atender, recusar, cancelar, timeout, atraso de sinal,
   mesmo `callId` retransmitido, trocas de rede, vários convites,
   reinício do processo e interop com APK antigo.
5. **Segurança pendente de migração independente**: existe fallback de
   derivação simétrica legada a partir de identificadores. Não alterar
   isso silenciosamente sem versionar envelopes, negociar chaves seguras
   e preservar histórico/correspondência.
6. Deploy de Render, WebRTC com STUN/TURN e updates de banco **não**
   fazem parte desta etapa; precisam de autorização e projeto próprios.
7. Não fazer merge/push de APK para produção antes de homologação em
   Zenfone 9 (Android 14) e Samsung S24 (Android 16).

## Referências
- https://developer.android.com/reference/android/bluetooth/le/BluetoothLeScanner
- https://developer.android.com/reference/android/bluetooth/le/ScanFilter.Builder
- https://developer.android.com/develop/connectivity/bluetooth/ble/background
- https://developer.android.com/develop/background-work/services/fgs/service-types
- https://developer.android.com/develop/connectivity/telecom/voip-app/telecom
