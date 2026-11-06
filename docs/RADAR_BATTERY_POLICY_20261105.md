# PessoasAqui — Política de energia do Radar BLE (candidata à homologação)

**Estado:** código integrado na `main`; NÃO homologado no Android. Sem deploy Render/Supabase.
**Objetivo:** suspender descoberta BLE ociosa sem desligar Bluetooth do sistema, apagar
conversas, impedir Modo B ou desconectar sessões GATT existentes.

## Operação implementada

| Situação | Resultado |
|---|---|
| Abertura do aplicativo | Aba **Conversas**. Descoberta BLE inicialmente pausada. |
| Usuário entra na aba **Pessoas** | Banner com motivo da pausa e botão **Ativar Radar novamente**. |
| Usuário ativa Radar | Scanner filtrado + advertising conectável retomam; GATT existente não é encerrado. |
| Usuário sai do Radar para Conversas | Tolerância de 30 segundos; depois suspendem scanner + advertising. |
| Usuário muda para Ofertas | Mantém discovery enquanto a aba Ofertas está visível (ofertas próximas dependem de BLE). |
| Usuário minimiza sem chat temporário aberto | Tolerância de 60 segundos; depois suspende discovery. |
| Usuário retorna antes da suspensão | Cancela o timer, caso tenha voltado ao Radar; não reinicia o rádio desnecessariamente. |
| Usuário retorna após suspensão | Permanece pausado até reativação explícita; sem reativação silenciosa. |
| Conversa temporária BLE aberta | Mantém descoberta e permite reconexão, mesmo ao minimizar; evita cortar Modo A em uso. |
| Conversa permanente / familiar aberta | Modo B usa a internet; não prende a descoberta BLE por causa do chat. |
| Aplicativo recebe `Bluetooth ON` | Respeita estado pausado; não reativa discovery contra a escolha do usuário. |
| Falha BLE com retentativa pendente | Suspensão cancela retentativas; recuperação automática não supera pausa intencional. |
| Serviço em primeiro plano | Mantido sem locks permanentes de CPU/Wi-Fi. |

**Nunca desligar o Bluetooth do aparelho.** O mecanismo chama somente
`BleManager.stopScanning()` e `stopAdvertising()`. O coordenador GATT não
é fechado pela suspensão e as conversas permanentes continuam no WebSocket.
Quando o Radar é pausado, a UI esconde descobertas; as entradas físicas são
mantidas internamente até a limpeza por ausência de 45 segundos, evitando
burla da expiração das conversas temporárias.

## Arquivos principais

- `MainActivity.kt`: navegação, aba inicial Conversas e eventos da Activity.
- `PessoasAquiRepository.kt`: decisão de suspensão, cancelamento de timers,
  proteção do chat temporário e inicialização do hardware.
- `BleManager.kt`: bloqueio central de scanner, advertising e reativação
  automática; publicidade serializada para evitar corrida com pausa.
- `RadarIdlePolicy.kt`: política pura de 30 s / 60 s e exceções.
- `MainScreen.kt` / `PeopleRadarTab.kt`: filtro de Radar pausado, banner,
  botão de reativação e animação interrompida durante a pausa.
- `RadarIdlePolicyTest.kt`: regressões de foreground/background, chats,
  ofertas e janelas de tolerância.

## Critérios de aceitação — executar no Antigravity

```powershell
.\gradlew.bat :app:compileDebugKotlin --rerun-tasks
.\gradlew.bat :app:testDebugUnitTest --rerun-tasks
.\gradlew.bat :app:assembleDebug
```

1. Samsung S24 Android 16 e Zenfone 9 Android 14: abrir Conversas sem scanning
   ativo; entrar em Pessoas, ativar; verificar discovery bilateral e nome correto.
2. Pessoas → Conversas: scanner e advertising continuam 29 s e param após 30 s;
   retorno em 20 s cancela o timer; retorno após 35 s exige botão.
3. Radar ativo → minimizar: suspender após 60 s, reabrir e exibir estado pausado;
   conferir que fones/relógios Bluetooth continuam funcionando.
4. Durante chat **Modo A** aberto: minimizar, retornar e enviar/receber com
   GATT direto; confirmar que não foi exigido convite e nenhuma mensagem
   temporária foi salva em disco. Ao sair do chat, contar 30 s/60 s conforme tela.
5. **Modo B**: receber mensagens e chamadas via internet durante pausa, inclusive
   tela bloqueada/Doze; medir latência da nova política sem WakeLocks.
6. Tela Ofertas ativa: verificar anúncios BLE e que navegação para Conversas
   permite suspensão após 30 s; registrar se a preservação contínua em Ofertas
   representa consumo excessivo.
7. Repetir com Bluetooth desligado/religado, localização/permissões negadas,
   falha de startScan, alternância rápida de abas e app em segundo plano;
   scanner nunca deve ressuscitar após pausa intencional.
8. Verificar expiração de pares temporários em 45 s e descarte de mensagens
   atrasadas; preservar contatos permanentes, ofertas salvas e histórico válido.
9. Usar Battery Historian / Android Studio Profiler / `dumpsys batterystats`
   para medir consumo antes/depois em cenário controlado, e registrar crash/ANR.
10. Fontes grandes, rotação, tela pequena e multiwindow: banner e botão
    acessíveis sem corte; screenshot do QR e dos estados vazios.

## Limitações de homologação

Os 30/60 segundos são parâmetros iniciais aceitos como candidatos ao teste,
não resultado de medição. O app ainda pode consumir bateria por outros fatores,
incluindo ticker do serviço, rede, anúncios de Ofertas ou chamadas.

**Compilação Android, testes JUnit no ambiente do projeto, instrumentação,
consumo real e funcionamento físico não foram executados por esta integração.**
A política de decisão pura foi compilada separadamente com Kotlin/JVM e 7
verificações básicas passaram; isso NÃO prova compilação do aplicativo.
Não publicar APK/AAB nem alterar backend até o gate de homologação.
