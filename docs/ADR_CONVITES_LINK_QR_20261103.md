# PessoasAqui — Convites por link e QR (03/11/2026)

**Estado:** candidato à homologação, **não liberado para Render ou Play Store**.
**Branch:** `fix/pessoasaqui-convites-link-qr-20261103`.
**Base:** `be8434863d369ab76e78e83975647f42202c0007` (layout/estrelas/figurinhas ainda pendentes de homologação).

## Diagnóstico do código anterior

* Convite era criado no backend como ID, token aleatório, HMAC e URL HTTPS
  com expiração de 7 dias. **Assinatura HMAC não equivale a criptografia.**
* O link continha o token e deve ser tratado como segredo/bearer: qualquer
  pessoa que o obtenha pode tentar resgatá-lo primeiro.
* Backend afirmava "uso único", porém permitia fallback **sem registro no
  banco** se a assinatura fosse válida. Após reinício/replicação, esse
  fallback poderia reativar um convite já usado.
* Atualização do convite e criação do vínculo ocorriam em comandos SQL
  independentes e marcavam o mapa local antes de confirmação, abrindo
  possibilidade de race-condition e confirmação falsa.
* Código de frontend só compartilhava URL; o gerador ZXing de QR já existia,
  mas não estava conectado aos convites.
* Não havia leitor de QR integrado à gaveta de convites.

## Mudanças propostas/implementadas na branch

1. Mesmo URL para compartilhar à distância ou exibir como QR em proximidade.
   Um único `inviteId/token` para os dois caminhos: consumir qualquer um
   invalida ambos. QR **não** é um segundo convite.
2. Compartilhamento por sistema Android, cópia marcada como sensível quando
   API >=33 e modal com aviso de acesso, validade e confirmação.
3. Leitor oficial Google Code Scanner 16.1.0, somente QR, zoom automático.
   Google Play Services pode baixar o módulo no primeiro uso se ainda não
   estiver instalado. Fallback sempre disponível por colagem de URL.
4. Leitura nunca estabelece conexão automaticamente: usuário revisa e
   confirma a ação. URLs arbitrárias ou HTTP são recusadas.
5. Deep links, QR e clipboard usam o mesmo parser restritivo; sem abrir
   navegador em sites não oficiais.
6. Token novo de 256 bits; armazenamento do **SHA-256** em PostgreSQL.
   Compatibilidade de resgate com tokens plaintext antigos.
7. **Sem fallback stateless**: banco configurado e inacessível => erro 503,
   nunca confirmar vínculo. Em desenvolvimento sem DATABASE_URL, memória local
   é possível, mas convites se perdem ao reiniciar.
8. PostgreSQL usa uma única instrução com `WITH claimed` e
   `INSERT ... ON CONFLICT ...`: resgate único transacional, inclusive sob
   solicitações concorrentes; falha de vínculo reverte consumo do token.
9. Página /invite com No-Store/No-Referrer/No-Index, host de fallback fixo,
   alias escapado e linguagem correta ("uso único", não "criptografado").
10. Expiração continua de 7 dias, para manter a regra existente.

## Limitações e bloqueios de segurança pendentes

* **A criação do convite ainda usa identidade fornecida pelo cliente.**
  O backend possui chaves públicas registradas em WebSocket, mas a criação
  HTTP ainda não exige prova criptográfica de posse da identidade emissora.
  Um agente malicioso pode tentar representar outra identidade. Antes do
  rollout de produção, implementar autenticação vinculada à identidade,
  com plano de compatibilidade dos clientes já publicados.
* Token em URL é credencial de posse; HTTP Referrer/clipboard/apps de
  terceiros podem expô-lo. Não é mensagem criptografada. Preferir
  compartilhamento apenas com destinatários de confiança e expiração curta.
* O QR usa backend/internet para criar e resgatar um vínculo **Modo B**;
  não tem relação com o Modo A BLE temporário e não funciona como
  confirmação offline de vínculo.
* Google Code Scanner depende de Google Play Services; o módulo pode ser
  baixado sob demanda. Testar em aparelho sem o módulo e sem internet,
  garantindo alternativa manual.

## Testes obrigatórios do Antigravity (sem escrita)

* Compilar `app:compileDebugKotlin`, executar `app:testDebugUnitTest`;
  rodar `node --test backend/tests/oneTimeInvites.test.js` e
  `node --test backend/tests/connectionRevocation.test.js`.
* Somente após aprovação, `assembleDebug` e calcular SHA-256 do APK.
* Verificar geração do link, QR visualmente legível, leitura, tela de
  confirmação, importação de link por compartilhamento/deep link, saída
  ao cancelar, duas pessoas tentando resgatar simultaneamente, reuso,
  expiração e servidor indisponível.
* Testar Google Play Services com módulo pré-instalado e ainda não instalado;
  testar aplicativo sem câmera, texto com fonte 200%, tablet e janela dividida.
* No backend testar consumo em PostgreSQL real de homologação, **não** no
  PostgreSQL de produção. Simular perda de banco e reinício do processo.
* Não modificar `main`, `feature/proximity-invites-master`, Render, Play
  Store, PostgreSQL ou a versão anterior do APK.
