# PESSOASAQUI — Homologação independente de Online/Offline (08/11/2026)

## Causa comprovada no código anterior

- `NearbyPerson.isOnlineNow` era verdadeiro pelo `lastSeenEpochMs` de qualquer origem, inclusive criação de contato sintético e heartbeat/cloud, não necessariamente aplicativo aberto.
- O backend renovava `activePresence.lastSeen` com heartbeat HTTP/WS mesmo com o aplicativo em segundo plano.
- `handlePeerOffline` preservava o contato permanente (corretamente), mas não desmarcava sua indicação visual de online.
- O vínculo E2EE e o BLE de 10 m NÃO devem ser derivados do estado de presença online.

## Alterações implementadas (escopo restrito)

1. Estado visual assinado `POST /api/presence/visibility` para primeiro plano/segundo plano; o WS e a fila E2EE permanecem ativos.
2. Backend preserva o campo `visible` entre heartbeat HTTP/WS e reconexões. A lista online e seus eventos excluem `visible=false`.
3. Android distingue `remotePresenceEpochMs` de `lastSeenEpochMs` usado pelo BLE e marca `isOnline=false` em evento `PEER_OFFLINE` ou snapshot autenticado sem o par.
4. Chat exibe Online somente com presença remota explicitamente confirmada em até 30 s; a tela reavalia a cada 5 s.
5. Compatibilidade: clientes antigos sem endpoint de visibilidade ainda operam com comportamento legado até atualização; não alterar o mecanismo de autenticação E2EE nem as relações mútuas.

## Matriz obrigatória de validação (Antigravity em modo somente-leitura)

- Confirmar HEAD, conferir snapshot `snapshot/pessoasaqui-1.3.3-field-approved-20261108` intacto.
- Executar `node --check backend/src/server.js` e todas as suítes backend.
- Executar `:app:compileDebugKotlin`, `:app:testDebugUnitTest` e `:app:assembleDebug`.
- Em S24 ↔ Zenfone 9, dois aparelhos conectados: aberto em ambos => Online. Minimizar/fechar A => B deve indicar Offline mesmo quando o serviço de background permanece ligado; não destruir a conversa. Reabrir A => Online.
- Desligar apenas Wi-Fi/dados do A: B deve sair de Online após expiração sem renovação (até 30 segundos na UI após a última confirmação), sem apagar vínculo.
- Testar término abrupto/forçar parada, reconexão rápida, rede instável, troca de abas e rotação do aparelho.
- Confirmar que mensagens pendentes, E2EE, revogação, recuperação V3 e BLE não regrediram.
- Confirmar que o indicador `Visto` não usa um timestamp BLE falsamente atualizado por HTTP.
- Verificar sessão E2EE em segundo plano e que nenhum desconhecido surge no Radar devido ao backend.
- Testar versão cliente antiga contra o backend novo; endpoint novo não pode ser obrigatório para envio/recebimento de mensagens.
- Não modificar código nem produção na homologação. Registrar logs, evidências, números de testes e ambiente.
- O comportamento novo de fechamento requer **APK atualizado e backend Render implantado a partir da main**; o deploy deve ser realizado pelo proprietário depois de validar testes.

**Estado:** integração no GitHub concluída. Não houve compilação Android nem testes físicos executados nesta sessão. Sintaxe de `server.js` verificada no ambiente de análise, mas o comportamento ainda não foi homologado.
