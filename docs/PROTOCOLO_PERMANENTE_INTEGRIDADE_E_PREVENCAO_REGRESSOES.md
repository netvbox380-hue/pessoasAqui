# PESSOASAQUI — PROTOCOLO PERMANENTE DE INTEGRIDADE E PREVENÇÃO DE REGRESSÕES

## DETERMINAÇÃO EXPRESSA DO PROPRIETÁRIO

**Repositório:** `netvbox380-hue/pessoasAqui`  
**Branch autorizada:** `main`

O proprietário determina que toda implementação, correção, melhoria, otimização ou refatoração seja submetida a uma investigação completa de suas consequências.

Esta regra é obrigatória para todas as próximas modificações.

---

## 1. REGRA FUNDAMENTAL

**TODA AÇÃO NO CÓDIGO DEVE TER SEUS EFEITOS AVALIADOS EM TODAS AS DIREÇÕES RELEVANTES DA ARQUITETURA.**

A investigação deverá compreender:

- Dependências anteriores.
- Dependências posteriores.
- Componentes paralelos.
- Contratos compartilhados.
- Estados internos.
- Persistência.
- Concorrência.
- Segurança.
- Compatibilidade.
- Ciclo de vida.
- Comportamento após falhas.
- Consequências das próprias correções.

Não limitar a análise ao arquivo modificado.

Não considerar uma correção concluída apenas porque o problema original desapareceu.

---

## 2. ANÁLISE MULTIDIRECIONAL OBRIGATÓRIA

Para cada modificação, verificar:

- **PARA TRÁS:** todos os chamadores e produtores de dados relevantes.
- **PARA FRENTE:** todos os componentes, serviços e funções utilizados.
- **LATERALMENTE:** fluxos paralelos, consumidores compartilhados, concorrência e transportes alternativos.
- **VERTICALMENTE:** interfaces, regras de negócio, armazenamento, banco de dados, criptografia e sistema operacional.
- **TEMPORALMENTE:** mensagens atrasadas, retentativas, reinicializações, migrações e compatibilidade entre versões.
- **EM SEGURANÇA:** autenticação, autorização, identidade, chaves, E2EE, revogação e proteção contra reutilização.
- **RECURSIVAMENTE:** os efeitos provocados pelas correções realizadas durante a própria investigação.

---

## 3. PROTEÇÃO CONTRA RETROCESSOS

Nenhuma nova implementação poderá comprometer requisitos anteriormente atendidos.

### Antes de modificar um componente:
1. Identificar seu comportamento atual.
2. Identificar as garantias de segurança existentes.
3. Identificar funcionalidades dependentes.
4. Identificar cenários de falha já corrigidos.
5. Registrar os comportamentos que precisam ser preservados.

### Após modificar:
1. Executar os testes anteriores relevantes.
2. Criar testes específicos para a alteração.
3. Criar testes para consequências identificadas.
4. Verificar se algum comportamento anterior foi prejudicado.
5. Corrigir imediatamente qualquer regressão.
6. Reexecutar os testes afetados.

**Não remover testes existentes para fazer uma alteração passar.**

Se um teste precisar ser atualizado devido a uma mudança legítima de contrato, demonstrar a justificativa e preservar a cobertura da garantia anterior.

---

## 4. CONSEQUÊNCIAS DAS PRÓPRIAS CORREÇÕES

Toda correção adicional deverá ser tratada como uma nova alteração sujeita às mesmas regras.

O processo será:

**CORRIGIR → ANALISAR IMPACTOS → IDENTIFICAR CONSEQUÊNCIAS → CORRIGIR CONSEQUÊNCIAS → REANALISAR → TESTAR → VALIDAR.**

Repetir enquanto houver efeitos relevantes não tratados.

Não introduzir um segundo defeito para eliminar o primeiro.

Não aceitar simplificações que desativem funcionalidades ou reduzam segurança.

---

## 5. CONTRATOS E COMPATIBILIDADE

Toda alteração de função, evento, estrutura ou protocolo deverá ser acompanhada da revisão de seus produtores e consumidores.

Verificar:
- Tipos de retorno.
- Valores nulos.
- Exceções.
- Operações assíncronas.
- Estados compartilhados.
- Identificadores.
- Mensagens duplicadas.
- Tratamento de erros.
- Versões anteriores.
- Persistência.
- Autorização.
- Concorrência.

Nenhum consumidor poderá permanecer utilizando um contrato incompatível.

---

## 6. GARANTIAS INEGOCIÁVEIS DO PESSOASAQUI

Preservar integralmente:

- **MODO A:** BLE direto, aproximadamente 10 metros, independência da internet, sessões temporárias e promoção autorizada do histórico.
- **MODO B:** conexões bilaterais permanentes, LINK + QR, envio pela internet, histórico criptografado e revogação.
- **CRIPTOGRAFIA:** ECDH P-256, AES-256-GCM, identidades autenticadas, Android Keystore, proteção contra replay e verificação de chaves.
- **RECUPERAÇÃO:** PIN, cofres, guardiões, Shamir V3, rotação e restauração de identidade.
- **FUNCIONALIDADES:** textos, imagens, áudios, vídeos, documentos, chamadas, notificações, mensagens importantes e confirmações.
- **INTERFACE:** responsividade, organização de conversas e identidade visual oficial (logotipo oficial congelado).

Nenhum desses componentes poderá ser prejudicado para facilitar outra implementação.

---

## 7. TESTES E EVIDÊNCIAS

Executar testes em diferentes níveis:
- Unitários.
- Contratos entre componentes.
- Integração.
- Concorrência.
- Falhas de infraestrutura.
- Segurança.
- Regressão.
- Compatibilidade.

Quando necessário, utilizar PostgreSQL real isolado.

Não confundir simulações com execução real.

Não afirmar que testes físicos foram realizados sem utilizar dispositivos.

Não utilizar somente o número de testes aprovados como comprovação de integridade.

---

## 8. MATRIZ DE CONSEQUÊNCIAS

Antes de cada publicação, apresentar:

| Alteração | Dependência direta | Dependência indireta | Consequência possível | Consequência observada | Ação corretiva | Teste | Resultado |
|---|---|---|---|---|---|---|---|

A matriz deverá demonstrar que foram investigados os caminhos relevantes da arquitetura.

Também deverá registrar riscos não resolvidos e limitações de validação.

---

## 9. CONDIÇÃO PARA COMMIT E PUSH

Continuar trabalhando na `main`, conforme determinação do proprietário.

Entretanto, antes de publicar:
- Revisar o diff completo.
- Examinar as dependências.
- Validar os contratos.
- Investigar consequências.
- Testar regressões.
- Verificar segurança.
- Compilar Android.
- Validar backend.
- Documentar riscos remanescentes.

Não realizar push de alterações com defeitos críticos conhecidos.

Não utilizar force push.

Não executar deploy no Render.

Não modificar o Supabase de produção.

---

## 10. CRITÉRIO DE CONCLUSÃO

Uma alteração somente poderá ser declarada concluída quando:

1. O problema inicial estiver resolvido.
2. As dependências relevantes estiverem consistentes.
3. As consequências relevantes tiverem sido investigadas.
4. As regressões identificadas tiverem sido corrigidas.
5. Os contratos estiverem compatíveis.
6. As garantias de segurança estiverem preservadas.
7. Os testes necessários tiverem sido executados.
8. As limitações restantes estiverem documentadas.
9. Não houver defeitos críticos conhecidos introduzidos pela alteração.

Não declarar ausência absoluta de defeitos sem possibilidade de comprovação.
