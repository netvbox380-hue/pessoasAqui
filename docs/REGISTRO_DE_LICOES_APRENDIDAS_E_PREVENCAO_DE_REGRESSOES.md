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

