# REGISTRO DE LIÇÕES APRENDIDAS E PREVENÇÃO DE REGRESSÕES

**Projeto:** PessoasAqui  
**Repositório:** `netvbox380-hue/pessoasAqui`  
**Branch autorizada:** `main`  
**Data:** 08 de Novembro de 2026  
**Documento Mandatório de Conformidade Técnica:** Complemento obrigatório a `GEMINI.md` e `docs/PROTOCOLO_PERMANENTE_INTEGRIDADE_E_PREVENCAO_REGRESSOES.md`.

---

## 1. HISTÓRICO FORENSE DA FALHA DETECTADA

Durante o processo de fechamento da revogação bilateral atômica e proteção do banco relacional, foi identificada a seguinte falha de segurança e arquitetura:

> **O backend podia aceitar e persistir uma mensagem permanente (Modo B) mesmo sem existir vínculo bilateral prévio entre remetente e destinatário (ausência total de registros na tabela `connections`).**

### Como a falha ocorria no código
Na função `deliverMessageToTarget()` (`backend/src/server.js`), realizava-se a consulta aos registros em `connections`:
```sql
SELECT from_identity, to_identity, is_mutual FROM connections
WHERE ((LOWER(from_identity) = LOWER($1) AND LOWER(to_identity) = LOWER($2))
   OR (LOWER(from_identity) = LOWER($2) AND LOWER(to_identity) = LOWER($1)))
FOR SHARE;
```
Quando nenhum registro era retornado (`connCheck.rowCount === 0`), o bloco de tratamento verificava apenas se o par estava na lista de revogações em memória (`revokedConnections`) ou se existiam mensagens canceladas com `is_cancelled = TRUE`. Se nenhum cancelamento prévio fosse detectado (o que é o caso comum entre dois usuários desconhecidos), a execução prosseguia livremente para o `INSERT INTO e2ee_messages`.

---

## 2. ANÁLISE DE CAUSA-RAIZ E RESPOSTAS FUNDAMENTAIS DE APRENDIZAGEM (SEÇÃO 2)

### 1. O que nós assumimos como verdadeiro sem testar?
**Resposta:** Assumiu-se erroneamente que a única razão para barrar um envio no Modo B seria a presença de um evento explícito de revogação (`isTombstoned || hasCancelled`). Supôs-se tacitamente que o cliente móvel só chamaria o endpoint se houvesse uma amizade ativa, transferindo uma responsabilidade de autorização do servidor para a boa-fé do cliente.

### 2. Por que acreditamos que a solução anterior estava completa?
**Resposta:** Porque os testes anteriores foram formulados exclusivamente com foco no ciclo de vida de relacionamentos existentes (criar vínculo → revogar → tentar enviar e falhar). Todos os testes criavam o vínculo antes de testar a revogação. A ausência de testes partindo do "estado zero" (desconhecidos completos) criou uma falsa sensação de cobertura total (100% dos testes passando).

### 3. O que nos impediu de enxergar que ausência de vínculo permitia envio?
**Resposta:** O viés de confirmação centrado no caso de uso de revogação. O objetivo da tarefa era "garantir revogação atômica". O foco mental ficou restrito a garantir que *quem foi revogado não pudesse enviar*, negligenciando o princípio mais elementar de que *quem nunca se conectou também não pode enviar*.

### 4. Que classe de erro conceitual foi cometida?
**Resposta:** A clássica falha de **"Default Allow" (Permissão Padrão)** em vez de **"Default Deny" (Negação Padrão / Fail-Closed)**. O código tratava a autorização como uma lista de restrições (se revogado, bloqueie; senão, permita), quando sistemas de segurança devem tratar como autorização afirmativa (se mutuamente ativo, permita; senão, bloqueie por padrão).

### 5. Qual é o princípio fundamental violado?
**Resposta:** O princípio do **Privilégio Mínimo e Fail-Closed (Negação por Padrão)**. Nenhuma ação protegida deve ser executada sem a prova explícita e positiva de autorização prévia.

### 6. Que novo padrão de projeto ou verificação deve ser adotado para que esse tipo de falha nunca mais se repita?
**Resposta:** Padrão **Precondition Guard Fail-Closed Obrigatório**:
1. Toda rota ou função que processe dados bilaterais deve validar positivamente a existência do vínculo antes de qualquer efeito colateral.
2. É proibido inferir autorização pela ausência de evidências contrárias.
3. Toda tabela de permissão deve exigir linhas recíprocas explicitamente (`rowCount >= 2` com `is_mutual = true` em ambos os sentidos).

### 7. Como garantir que esse aprendizado seja aplicado nas próximas implementações?
**Resposta:**
- Revisão obrigatória de Pré-condições Fail-Closed em todo PR/commit.
- A Matriz de Comportamentos deve sempre incluir a dimensão obrigatória: **"Ausência de Estado (Estado Zero)"**.

### 8. Que testes adicionais são necessários para blindar definitivamente essa área?
**Resposta:**
- Teste de envio entre desconhecidos sem nenhum registro no banco relacional (HTTP 403 `NO_CONNECTION`).
- Teste de tentativa de chamada WebRTC efêmera/sinalização entre desconhecidos (HTTP 403 `NO_CONNECTION`).
- Teste com vínculo unilateral (apenas A marcou B, B não marcou A) rejeitado com HTTP 403 (`UNILATERAL_NOT_PERMITTED`).

---

## 3. PENSAMENTO ADVERSARIAL OBRIGATÓRIO (SEÇÃO 3 — 14 PERGUNTAS)

### 1. E se a premissa estiver errada?
- **Premissa testada:** Supor que se o cliente tem a chave pública do destinatário, eles são amigos.
- **Defesa:** O backend não confia no cliente. O backend consulta diretamente a tabela `connections` sob lock transacional (`FOR SHARE`) e exige 2 registros mútuos (`A->B` e `B->A`).

### 2. E se o estado inicial for nulo, vazio ou inexistente?
- **Defesa:** Se `connCheck.rowCount === 0` ou `rows == null`, a requisição é imediatamente rejeitada com `HTTP 403 Forbidden` (`NO_CONNECTION` ou `CONNECTION_REVOKED`).

### 3. E se não existir nenhum registro na tabela?
- **Defesa:** Rejeição imediata. Zero registros significa zero privilégios.

### 4. E se existir apenas um registro parcial ou unilateral?
- **Defesa:** O código verifica explicitamente:
  `hasDirect && hasReverse && allMutual && connCheck.rowCount >= 2`.
  Se apenas um lado marcou (`rowCount === 1` ou `!is_mutual`), lança exceção com status 403 e código `UNILATERAL_NOT_PERMITTED`.

### 5. E se a operação falhar na metade?
- **Defesa:** A autorização e a inserção ocorrem dentro de `withTransaction`. Se qualquer comando SQL falhar ou a conexão oscilar, o PostgreSQL executa `ROLLBACK` automático, impedindo estados parciais.

### 6. E se o processo for interrompido antes do commit?
- **Defesa:** O banco relacional descarta as alterações não commitadas. Nenhuma mensagem é gravada e nenhum vínculo é alterado indevidamente.

### 7. E se duas operações idênticas ocorrerem ao mesmo tempo?
- **Defesa:** Inserções de mensagem usam `ON CONFLICT (id) DO NOTHING` e locks idempotentes. O primeiro vence e o segundo reaproveita o registro com segurança.

### 8. E se duas operações conflitantes ocorrerem ao mesmo tempo? (Ex: Envio vs Revogação)
- **Defesa:** O envio adquire `SELECT ... FOR SHARE` na linha de `connections`. A revogação executa `DELETE FROM connections`. O PostgreSQL serializa o acesso: se o DELETE rodar primeiro, o SELECT do envio lerá zero registros e abortará com 403. Se o envio rodar primeiro, o DELETE aguarda o envio e depois revoga a conexão e cancela a mensagem gravada via `UPDATE e2ee_messages SET is_cancelled = TRUE`.

### 9. E se uma mensagem antiga chegar depois de uma revogação?
- **Defesa:** Ao tentar persistir, a ausência do registro em `connections` ou o tombstone em `revokedConnections` resultará em HTTP 403 `CONNECTION_REVOKED`. Se o ID da mensagem já havia sido cancelado, o reenvio retorna HTTP 410 `Gone`.

### 10. E se um cliente desatualizado tentar usar o protocolo antigo?
- **Defesa:** O protocolo exige assinaturas ECDSA P-256 (`verifyActiveInviteProof`), nonces anti-replay e payloads estruturados. Clientes que não apresentarem a autenticação necessária recebem HTTP 400 ou 401.

### 11. E se o banco de dados cair durante a operação?
- **Defesa:** O circuit breaker entra em fail-closed imediatamente. Rotas duráveis retornam HTTP 503 `Service Unavailable`. Nunca há vazamento nem degradação insegura para memória RAM transitória.

### 12. E se o banco de dados voltar após a falha?
- **Defesa:** O health check periódico do circuit breaker executa probe (`SELECT 1`) no pool. Uma vez restaurado, reseta o circuit breaker e restaura a integridade durável automaticamente.

### 13. E se a rede oscilar entre o envio e o recebimento?
- **Defesa:** O remetente reenvia a mensagem com o mesmo `messageId`. O `ON CONFLICT (id) DO NOTHING` do banco e a idempotência da fila garantem que duplicações sejam absorvidas sem duplicação de entrega ou violação E2EE.

### 14. E se um usuário tentar forjar os identificadores ou a criptografia?
- **Defesa:** As mensagens são cifradas de ponta a ponta (ECDH P-256 + AES-256-GCM). O backend funciona como retransmissor cego (blind relay) e não tem acesso às chaves simétricas. As assinaturas de cabeçalho garantem que apenas o titular da chave privada Ed25519/P-256 consiga postar mensagens no seu próprio nome.

---

## 4. INVARIANTES DO SISTEMA (SEÇÃO 4)

1. **Invariante 1 (Fail-Closed na Ausência de Vínculo):** A ausência total de registros na tabela `connections` impede estritamente qualquer envio de mensagens permanentes Modo B e qualquer sinalização de chamadas via servidor.
2. **Invariante 2 (Mutualidade Bilateral Estrita):** Uma conexão só confere autorização se e somente se existirem os dois sentidos (`A->B` e `B->A`) ambos com `is_mutual = true`.
3. **Invariante 3 (Isolamento Arquitetural do Modo A BLE):** O radar de proximidade local opera em Bluetooth Low Energy independente de conexão com a internet ou banco de dados. Suas regras locais de proximidade física (10 metros) e PIN temporário não são afetadas pelas regras de backend do Modo B.
4. **Invariante 4 (Atomicidade da Revogação):** A revogação bilateral via `/api/connections/unmark` exclui ambos os sentidos de vínculo e cancela todas as mensagens pendentes não entregues dentro de uma única transação atômica.
5. **Invariante 5 (Proibição de Vazamento de Fila em RAM):** Em caso de falha de conexão com PostgreSQL, o sistema jamais faz fallback de leitura/gravação durável para Maps em memória.

---

## 5. MATRIZ DE COMPORTAMENTOS (SEÇÃO 5)

| Cenário de Comportamento | Condição Inicial | Ação / Evento | Comportamento Esperado | Código HTTP / Retorno | Teste de Validação |
|---|---|---|---|---|---|
| **1. Positivo** | Conexão mútua ativa (`A<->B`, `is_mutual=true`) | Alice envia mensagem permanente para Bob | Mensagem inserida em `e2ee_messages` e entregue (ou enfileirada se Bob offline) | HTTP 200 `{ success: true, delivered: ... }` | `atomicRevocationAndSupabaseProtection.test.js` (Subtest 4) |
| **2. Negativo (Revogado)** | Conexão revogada previamente por unmark | Alice tenta enviar mensagem para Bob | Rejeitado transacionalmente pelo tombstone / mensagens canceladas | HTTP 403 `{ error: ..., code: 'CONNECTION_REVOKED' }` | `atomicRevocationAndSupabaseProtection.test.js` (Subtest 6) |
| **3. Ausência de Estado (Estado Zero)** | Zero registros em `connections` (nunca foram amigos) | Estranho envia mensagem permanente para usuário | Rejeitado imediatamente sem inserir no banco | HTTP 403 `{ error: ..., code: 'NO_CONNECTION' }` | `atomicRevocationAndSupabaseProtection.test.js` (Subtest 13) |
| **4. Estado Parcial (Unilateral)** | Apenas A marcou B (`rowCount=1` ou `is_mutual=false`) | A tenta enviar mensagem permanente para B | Rejeitado por falta de reciprocidade bilateral | HTTP 403 `{ error: ..., code: 'UNILATERAL_NOT_PERMITTED' }` | `atomicRevocationAndSupabaseProtection.test.js` (Subtest 12) |
| **5. Concorrência** | Vínculo ativo sendo desfeito por unmark simultâneo | Envio concorrente durante a transação de unmark | SELECT FOR SHARE serializa com DELETE; se commit do unmark vence, aborta envio | HTTP 403 ou rollback controlado | `atomicRevocationAndSupabaseProtection.test.js` (Subtest 5) |
| **6. Falha Intermediária** | Banco falha durante operação de escrita | Transação aborta antes de COMMIT | PostgreSQL faz ROLLBACK integral; nenhum dado parcial persistido | HTTP 500 / 503 com estado intacto | `atomicRevocationAndSupabaseProtection.test.js` (Subtest 2 e 3) |
| **7. Persistência** | PostgreSQL ativo e saudável | Operações de mensagens e vínculos | Persistência relacional durável com chaves primárias e integridade referencial | Operações consistentes | `realPostgresDeliveryChain.test.js` |
| **8. Recuperação** | Falha de rede temporária no PostgreSQL | Banco restabelece conectividade | Circuit breaker detecta recuperação via probe periódica e reabre | Operações retomadas sem reinício | `autoRecoveryAndRevocationRealPostgres.test.js` |
| **9. Compatibilidade** | Clientes Android / Web legados ou atualizados | Envio de mensagens e tratamento de erros | Códigos padronizados HTTP 403, 410, 503 com payload JSON `{ error, code }` | Contrato estável | `realHttpWsPostgresIntegration.test.js` |
| **10. Segurança** | Mensagens cifradas na ponta | Trânsito pelo servidor | Servidor não tem chave privada nem simétrica; verificação estrita de assinatura ECDSA | E2EE P-256 + AES-256-GCM inviolável | `serverE2eeRelay.test.js`, `signedHttpAuth.test.js` |
| **11. Regressão** | Alteração em `deliverMessageToTarget` | Execução de todas as 24 suítes de teste de backend e 301 testes de Android | 100% de aprovação sem alterar o comportamento do Modo A BLE | Todos os testes aprovados | Bateria completa automatizada |

---

## 6. ANÁLISE DAS PRÓPRIAS DECISÕES (SEÇÃO 7)

### 1. Por que esta solução é a correta e não apenas uma solução rápida?
Porque atacou diretamente a falha estrutural de lógica. Não foi colocada uma exceção pontual; foi estabelecido o princípio de negação padrão (*Default Deny*), exigindo bilateralidade e mutualidade comprovadas em duas vias (`rowCount >= 2` com `is_mutual = true`), abrangendo tanto mensagens de texto como sinalização de chamadas WebRTC.

### 2. Que suposições foram eliminadas nesta implementação?
Eliminou-se a suposição ingênua de que a inexistência de revogação equivalia à existência de amizade autorizada. Eliminou-se também a suposição de que clientes nunca chamariam o endpoint sem estarem conectados.

### 3. O que garante que esta alteração não introduz novas vulnerabilidades?
A alteração restringe o acesso em vez de expandi-lo. Todo o código utiliza parametrização estrita de SQL (`$1, $2`), locks de concorrência (`FOR SHARE`) e blocos atômicos dentro de `withTransaction`.

### 4. Como esta solução se comporta em cenários adversos?
Em caso de queda de banco, a rota falha de forma fechada (`fail-closed`), respondendo com 503 em vez de vazar dados. Se houver tentativa de injeção de ID inválido, a regex de sanitização bloqueia imediatamente.

### 5. Qual é o impacto no desempenho e na escalabilidade?
O overhead é mínimo. A verificação de vínculo já ocorria no fluxo transacional para aplicar o lock `FOR SHARE`. Apenas a lógica condicional sobre o resultado da contagem de linhas e sinalizadores foi corrigida de forma puramente computacional em milissegundos.

### 6. Como foi garantida a compatibilidade com o restante do sistema?
O payload JSON de erro mantém as chaves habituais (`error`, `details`) e adiciona a propriedade `code: 'NO_CONNECTION'`, permitindo que tanto clientes existentes quanto versões futuras compreendam exatamente o motivo da recusa. O Modo A BLE permanece totalmente desacoplado e intocado.

### 7. Que evidências comprovam que a causa-raiz foi eliminada?
Os novos testes automatizados no arquivo `backend/tests/atomicRevocationAndSupabaseProtection.test.js` (Subtest 13 e Subtest 14) executam tentativas reais de envio de mensagem permanente e sinalização de chamada entre duas identidades sem nenhum registro na tabela `connections`, comprovando que o servidor responde com HTTP 403 Forbidden (`NO_CONNECTION`) e que 0 linhas são gravadas no PostgreSQL.

### 8. Como esta alteração contribui para a estabilidade de longo prazo?
Elimina vetores de spam, vazamento de recursos e assédio entre desconhecidos via Modo B no servidor central, garantindo que o aplicativo PessoasAqui cumpra sua promessa fundamental de arquitetura de relacionamentos estritamente bilaterais e consentidos.

---

## 7. AUDITORIA E FECHAMENTO DOS CAMINHOS ALTERNATIVOS DE AUTORIZAÇÃO

Em cumprimento à ordem de fechamento sistêmico, foi realizada a auditoria exaustiva de todos os caminhos alternativos de autorização:

### 1. Desenvolvimento Local (`localStore.connections.size > 0`)
- **Causa da Falha Anterior:** A verificação bilateral em modo em memória estava condicionada a `localStore.connections.size > 0`. A premissa incorreta era de que se o mapa estivesse vazio, tratava-se de um ambiente de teste inicial permissivo. Essa exceção transformava a ausência de estado em um *Default Allow*.
- **Correção Definitiva:** A restrição `size > 0` foi eliminada. Toda mensagem permanente no modo local exige obrigatoriamente a presença de `conn1` e `conn2` mútuos (`isMutual === true`). Mapa vazio retorna estritamente `HTTP 403 Forbidden` (`code: 'NO_CONNECTION'`).

### 2. Autorização de Chamadas e Cache em Memória (`getConnectionStatus`)
- **Causa da Falha Anterior:** O método consultava `localStore.connections` antes do PostgreSQL para agilidade. Se o vínculo fosse desfeito ou revogado no PostgreSQL, o cache residual em RAM mantinha `isMutual = true`. Além disso, se a consulta SQL falhasse, o erro era capturado sem anular a mutualidade (*Fail-Open*).
- **Correção Definitiva:** Quando o PostgreSQL está configurado, o banco relacional é a **ÚNICA** fonte autoritativa de verdade. O cache residual em RAM não pode ressuscitar conexões revogadas ou excluídas. Em caso de falha de consulta ou queda do PostgreSQL, o retorno é estritamente **Fail-Closed** (`HTTP 503 DB_UNAVAILABLE`).

### 3. Classificação de Mensagens Efêmeras e Manipulação de Prefixos
- **Causa da Falha Anterior:** A autorização em mensagens efêmeras era aplicada apenas a `isCallSignaling` com PostgreSQL ativo, deixando frames de chamada (`call_frame_`) e prefixos manipulados (`ephem_`) sem validação de vínculo prévio. Além disso, o cliente poderia declarar prefixos efêmeros para tentar omitir a persistência durável de mensagens permanentes.
- **Correção Definitiva:**
  1. Qualquer tráfego efêmero ou de chamada (sinalização WebRTC ou frames de mídia) exige vínculo bilateral permanente confirmado via `getConnectionStatus()`. Desconhecidos recebem `HTTP 403 Forbidden` (`NO_CONNECTION`). Conexões revogadas recebem `HTTP 403 Forbidden` (`CONNECTION_REVOKED`).
  2. Comandos e payloads permanentes (`[VAULT_`, `[FAMILY_`, `[DELIVERY_ACK:`, `[READ_ACK]`, `[IMAGE:`, `[AUDIO:`, `[DOC:`, `[PA_REAL_PHOTO_V1:`, `[MSG_EDIT:`, `[MSG_DELETE:`) NUNCA são classificados como efêmeros, sendo obrigatoriamente persistidos de forma durável no PostgreSQL.

### 4. Bateria de 12 Testes Negativos Obrigatórios (`callAndLocalAuthSecurity.test.js`)
Criada suíte completa com 12 subtestes com 100% de aprovação:
1. `Desenvolvimento local sem conexões` -> HTTP 403 `NO_CONNECTION`.
2. `Desenvolvimento local com vínculo unilateral` -> HTTP 403 `UNILATERAL_NOT_PERMITTED`.
3. `Desenvolvimento local com conexão bilateral válida` -> Sucesso 200 e persistência durável local.
4. `Chamada com conexão bilateral válida` -> Autorizada 200 em RAM.
5. `Chamada sem vínculo` -> HTTP 403 `NO_CONNECTION`.
6. `Chamada após revogação` -> HTTP 403 `CONNECTION_REVOKED`.
7. `Chamada com cache desatualizado` -> Autoridade do banco rejeita HTTP 403 `NO_CONNECTION`.
8. `Chamada com PostgreSQL indisponível` -> Fail-closed imediato HTTP 503 `DB_UNAVAILABLE`.
9. `Mensagem permanente tentando utilizar prefixo efêmero` -> Desmascarada e persistida duravelmente em `e2ee_messages`.
10. `Mensagem efêmera legítima` -> Entregue via WebSocket sem persistir em disco.
11. `Reinicialização do backend` -> Estado restaurado do PostgreSQL sem vazamento em RAM.
12. `Compatibilidade com cliente Android` -> Contrato estruturado `{ success, delivered, persisted, code }` verificado via HTTP REST.

---

## 8. CLASSIFICAÇÃO SEGURA DE MENSAGENS POR CONTRATO AUTENTICADO E PREPARAÇÃO DOS ARTEFATOS 1.3.3

### 1. Auditoria e Causa-Raiz na Classificação de Mensagens
- **O Problema Forense:** A função `deliverMessageToTarget()` e os despachantes WebSocket/HTTP inferiam efemeridade por inspeção de prefixos em texto claro (`payloadStr.startsWith('[CALL_')` ou `effectiveMsgId.startsWith('call_frame_')`).
- **Insegurança do Modelo Anterior:**
  1. *Quebra da suposição de E2EE:* No Modo B real, os payloads trafegam integralmente cifrados (`ENC_ECDH:...`). O backend cego nunca vê strings como `[CALL_INIT:` ou `[VAULT_` dentro do ciphertext.
  2. *Vetor de Evasão por Prefixo Manipulado:* Um atacante podia forjar um identificador com prefixo `call_frame_` ou `ephem_` em uma mensagem permanente criptografada, fazendo o backend tratá-la indevidamente como efêmera e não persistir no banco.
  3. *Acúmulo Indevido de Sinalizações:* Clientes que enviavam sinalização legítima cifrada com IDs UUID tinham suas chamadas persistidas no PostgreSQL como mensagens de chat comuns, gerando toques fantasmas dias depois.

### 2. Resolução por Contrato Autenticado de Operação
- **Regra Fundamental de Persistência (Fail-Closed to Permanent):** Toda mensagem é **PERMANENTE POR PADRÃO**. Nenhuma mensagem deixa de ser persistida duravelmente a menos que o cliente autenticado tenha explicitamente declarado o contrato efêmero (`isEphemeralParam === true`).
- **Imunidade a Prefixos Forjados:** Ter prefixo `call_frame_` ou `ephem_` sem a declaração afirmativa de contrato efêmero (`isEphemeralParam === true`) não confere status efêmero; a mensagem é obrigatoriamente persistida no PostgreSQL.
- **Sobrescrita de Segurança para Comandos de Controle:** Comandos em texto claro conhecidos de controle (`[VAULT_`, `[DELIVERY_ACK:`, `[READ_ACK]`, `[FAMILY_`, `[PA_REAL_PHOTO_V1:`, etc.) NUNCA são classificados como efêmeros, mesmo se o remetente forjar sinalizadores efêmeros.
- **Compatibilidade Retroativa:** Payloads legados em texto claro com comandos de chamada (`[CALL_INIT:`, `[CALL_AUDIO_FRAME:`, etc.) continuam reconhecidos como efêmeros sem quebra de comportamento.
- **Alinhamento do Cliente Android (`PessoasAquiRepository.kt`):**
  - Isolamento estrito entre operações de chamada em tempo real (`isCallStream` e `isCallSignaling`) e mensagens de usuário.
  - Vínculos de cofre (`[VAULT_`), fotos reais (`[PA_REAL_PHOTO_V1:`), arquivos, mídias e recibos mantêm `isEphemeral = false` e IDs UUID permanentes.
  - Sinalizações de chamada usam `isEphemeral = true` com prefixo `ephem_call_`, e frames de mídia usam `call_frame_`.

### 3. Matriz Multidirecional de Consequências
| Alteração | Dependência direta | Dependência indireta | Consequência possível | Consequência observada | Ação corretiva | Teste | Resultado |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| Contrato autenticado em `deliverMessageToTarget` | `server.js` | WebSocket `E2EE_MESSAGE` e HTTP `/api/messages/send` | Rejeitar mensagens de clientes legados | Nenhuma; comandos legados preservados | Fallback para comandos legados em texto claro | `authenticatedMessageClassificationSecurity.test.js` | APROVADO (12/12) |
| Fail-closed para permanente | Persistência PostgreSQL | Fila offline em RAM (`pendingMessages`) | Gravação excessiva de chamadas | Nenhuma; sinalizações reais usam contrato efêmero | Android gera `ephem_call_` com `isEphemeral = true` | Subtest 4 e 5 | APROVADO |
| Rate-limit diferenciado para streaming | `checkRateLimit` | WebRTC audio/video | Bloqueio de chamadas por anti-flood | Chamadas legítimas fluem a até 120 fps; ataques travam em 25/s | Condicionar taxa de 120/s ao contrato efêmero ativo | Subtest 10 e 11 | APROVADO |
| Preservação de cofre Shamir e fotos reais | `isPermanentPayload` | Room DB e PostgreSQL | Perda de chave de resgate se marcado efêmero | Nenhuma; comandos são forçados a permanente | Verificação incondicional de comandos permanentes | Subtest 7 e 8 | APROVADO |
| Assinatura condicional de release no Gradle | `app/build.gradle.kts` | `bundleRelease` e `assembleRelease` | Falha de build por NPE se variáveis ausentes | Build release unsigned em desenvolvimento; oficial em CI/CD | Anexar `signingConfig` apenas com credenciais presentes | `bundleRelease` e `assembleRelease` | APROVADO |

### 4. Preparação e Validação de Artefatos de Homologação
- **Identificadores Oficiais:** `versionCode = 29`, `versionName = "1.3.3"`.
- **Compatibilidade:** `minSdk = 24`, `targetSdk = 36` (Compatível com Android 14 e 16).
- **Branding e Logotipo:** 100% preservados e intocados (`PessoasAqui-icon-512x512.png`, `ic_launcher*`).
- **Suítes de Testes Executadas:**
  - 26/26 suítes de testes de integração e segurança do backend aprovadas (100%).
  - 301/301 testes unitários do Android aprovados (100%).
- **Artefatos Gerados:**
  - APK de Debug: `app/build/outputs/apk/debug/app-debug.apk` (21.6 MB).
  - APK de Release: `app/build/outputs/apk/release/app-release-unsigned.apk` (14.5 MB).
  - Android App Bundle: `app/build/outputs/bundle/release/app-release.aab` (14.1 MB).

---

## 9. INTEGRIDADE CRIPTOGRÁFICA DO CONTRATO DE MENSAGENS E VINCULAÇÃO DE OPERAÇÃO (V2 DIGEST & FAIL-CLOSED LEGACY)

### 1. Auditoria Forense e Causa-Raiz
- **A Brecha Identificada:** Na rota HTTP `/api/messages/send`, o cálculo do hash (`digestHttpParts`) englobava apenas 6 campos: `senderHash`, `recipientHash`, `ciphertextPayload`, `ivNonce`, `messageId`, `senderAlias`. Os campos `isEphemeral` e `operation` eram lidos diretamente do corpo da requisição sem estarem assinados pela chave privada ECDSA P-256 do remetente.
- **Vetor de Ataque:** Um invasor man-in-the-middle ou cliente modificado poderia interceptar uma mensagem durável legítima assinada e alterar o parâmetro HTTP para `isEphemeral: true`. Como a assinatura era válida sobre as 6 partes, o backend validava a requisição, mas tratava a mensagem durável como efêmera, descartando a persistência no PostgreSQL sem que o destinatário guardasse a prova durável da mensagem.
- **WebSocket Desvinculado:** O WebSocket autenticava apenas a sessão técnica da identidade na conexão (`/ws?identity=...`), mas não vinculava cada frame `E2EE_MESSAGE` a uma declaração operacional inviolável. Um cliente conectado poderia enviar um chat de usuário permanente alegando ser frame efêmero.

### 2. Correção Arquitetural Sistêmica
1. **Digest Criptográfico V2 (8 Partes):**
   - Inclusão mandatória do contrato efêmero (`ephemContract`: `'ephemeral'` vs `'durable'`) e da operação (`operation`: `'MESSAGE_SEND'`, `'CALL_STREAM'`, `'CALL_SIGNAL'`) no digest SHA-256 canônico assinado.
   - Fórmula V2: `digestHttpParts([senderHash, recipientHash, ciphertextPayload, ivNonce, messageId, senderAlias, ephemContract, operation])`.
2. **Multi-Hash Verification & Fail-Closed to Durable para Clientes Legados:**
   - O backend calcula ambos os digests (`digestV2` e `digestV1` legado) e os submete à verificação de assinatura ECDSA via `verifySignedHttp()`.
   - Se verificado via `digestV2`: os parâmetros `isEphemeral` e `operation` declarados são aceitos integralmente.
   - Se verificado via `digestV1` (legado): a assinatura é reconhecida como autêntica, porém o backend **força incondicionalmente** `isEphemeralContract = false` e `effectiveOperation = 'MESSAGE_SEND'`. Assinaturas legadas jamais podem ser usadas para solicitar tratamento efêmero.
3. **WebSocket com Vinculação de Operação:**
   - O manipulador `E2EE_MESSAGE` extrai e valida `operation`. Mensagens de chat de texto/usuário (`MESSAGE_SEND`) são incondicionalmente forçadas a duráveis (`isEphemeral = false`). A efemeridade só é permitida para operações legítimas de mídia ou controle (`CALL_STREAM` e `CALL_SIGNAL`).
4. **Alinhamento do Cliente Android (`PessoasAquiNetworkClient.kt` e `PessoasAquiRepository.kt`):**
   - Atualizado `sendHttpMessage` para assinar o Digest V2 de 8 partes.
   - Preservadas as assinaturas polimórficas de `sendE2eeEnvelope` (4 argumentos clássicos delegando para `MESSAGE_SEND`) para assegurar 100% de compatibilidade binária e com todas as suítes de testes unitários do Android.
   - Classificação estrita de operações: `MESSAGE_SEND` para mensagens e anexos, `CALL_STREAM` para frames de áudio/vídeo, e `CALL_SIGNAL` para sinalização WebRTC (incluindo `[ICE_CANDIDATE:`).

### 3. Matriz Multidirecional de Consequências
| Alteração | Dependência direta | Dependência indireta | Consequência possível | Consequência observada | Ação corretiva | Teste | Resultado |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| Digest V2 com 8 partes assinadas | `server.js` (`/api/messages/send`) | `PessoasAquiNetworkClient.kt` | Quebra de clientes legados | Incompatibilidade se rejeitado | Aceitar V1 com Fail-Closed to Durable | `contractIntegrityAndAdversarialClassification.test.js` Subtest 12 | APROVADO |
| Adulteração de `isEphemeral` | `verifySignedHttp` | ECDSA P-256 | Assinatura continuar válida | Assinatura é imediatamente invalidada (HTTP 401) | Garantir que `ephemContract` integra o hash | Subtest 1 | APROVADO |
| Adulteração de `operation` | `verifySignedHttp` | ECDSA P-256 | Operação forjada ser aceita | Assinatura é invalidada (HTTP 401) | `operation` integra o hash V2 | Subtest 2 e 3 | APROVADO |
| Vinculação de operação no WebSocket | WebSocket `E2EE_MESSAGE` | `deliverMessageToTarget` | Descarte de chat permanente | Nenhuma; `MESSAGE_SEND` é forçado a durável | Incondicional `isEphemeral = false` para `MESSAGE_SEND` | Subtest 4 | APROVADO |
| Sobrecarga clássica 4-args no Android | `PessoasAquiNetworkClient` | `PessoasAquiRepository` e testes | Regressão de testes unitários | Subclasses de teste quebravam compilação | Adicionar overloads delegados | `.\gradlew.bat testDebugUnitTest` (301 testes) | APROVADO |
| Alinhamento de ICE Candidate | `PessoasAquiRepository` | WebRTC P2P | Poluição do banco com candidatos ICE | ICE candidatos despachados em tempo real sem persistir no DB | Classificar `[ICE_CANDIDATE:` como `CALL_SIGNAL` | Subtest 8 | APROVADO |

### 4. Bateria de 12 Testes Adversariais Conclusivos (`contractIntegrityAndAdversarialClassification.test.js`)
1. `Adversarial HTTP: Alterar isEphemeral após assinatura` -> Rejeitado com HTTP 401.
2. `Adversarial HTTP: Reutilizar assinatura com classificação diferente` -> Rejeitado com HTTP 401.
3. `Adversarial HTTP: Alterar tipo de operação após assinatura` -> Rejeitado com HTTP 401.
4. `Adversarial WebSocket: MESSAGE_SEND com flag isEphemeral = true` -> Forçado a PERMANENTE (persistido no PostgreSQL).
5. `Adversarial: Prefixo call_frame_ em mensagem permanente` -> Persistido duravelmente no PostgreSQL.
6. `Formato E2EE desconhecido ou futuro sem contrato efêmero` -> Persistido duravelmente sem quebra de sigilo.
7. `Frames legítimos de streaming (CALL_STREAM)` -> Despachados em tempo real sem persistência no banco.
8. `Sinalização de chamada e ICE Candidate legítimos (CALL_SIGNAL)` -> Despachados em tempo real sem poluição de banco.
9. `Repetição de mensagem (Replay e Anti-colisão)` -> Idempotência confirmada e colisões rejeitadas (HTTP 409).
10. `Chamada após revogação` -> Bloqueada imediatamente com HTTP 403 (`CONNECTION_REVOKED`).
11. `Chamada com banco de dados indisponível` -> Fail-closed imediato com HTTP 503 (`DB_UNAVAILABLE`).
12. `Compatibilidade com clientes legados (Digest V1 6 partes)` -> Aceito com fail-closed para DURÁVEL.





## Lição adicional — 08/11/2026: conflito entre compatibilidade legada e contrato autenticado

**Incidente:** a homologação independente do commit `a22e36f` detectou 25/27 suítes aprovadas e duas falhas em testes antigos: `authenticatedMessageClassificationSecurity.test.js` (subteste 9) e `callAndLocalAuthSecurity.test.js` (subteste 12). Os testes ainda exigiam que o prefixo em texto claro `[CALL_INIT:]` fosse suficiente para declarar uma mensagem efêmera, mesmo na ausência de contrato de operação assinado.

**Causa-raiz do desacordo:** coexistência de duas políticas contraditórias. A antiga inferia efemeridade pelo texto do payload; a atual exige classificar `MESSAGE_SEND` como durável e permite uso efêmero apenas mediante contrato de chamada adequado. A aceitação indiscriminada de inferências por prefixo contraria a exigência de não permitir descarte de mensagens permanentes por metadados não autenticados.

**Decisão:** manter a garantia de segurança, sem reintroduzir a classificação insegura por prefixo. Reclassificar os testes antigos como cenários de *fail-closed*: mensagens legadas Digest V1 e chamadas sem contrato autenticado explícito permanecem duráveis. Acrescentar um teste positivo de sinalização `CALL_SIGNAL` com Digest V2 incluindo operação e efemeridade assinadas, demonstrando que chamadas modernas continuam efêmeras. Distinguir compatibilidade de aceitação sintática e compatibilidade comportamental: clientes antigos podem não receber a mesma política de persistência, e isso precisa ser comunicado/gerenciado antes do deploy.

**Prevenção:** na mudança de contrato de segurança, inventariar todas as asserções legadas e decidir antecipadamente quais são invariantes de segurança, quais são expectativas de compatibilidade e quais devem ser migradas. Nunca modificar testes apenas para torná-los verdes sem justificar a mudança de requisito e acrescentar cobertura positiva e negativa.

**Evidência:** alterações em `authenticatedMessageClassificationSecurity.test.js` e `callAndLocalAuthSecurity.test.js`, submetidas à `main`. A nova bateria completa ainda deve ser executada pelo Antigravity; não registrar estes testes como aprovados antes de seu relatório.
