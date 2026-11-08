# PessoasAqui — Diretrizes e Regras Permanentes do Projeto

## 🔒 Diretriz Crítica de Branding e Identidade Visual (Logotipo Oficial)
- **REGRA INVIOLÁVEL:** O logotipo oficial do aplicativo PessoasAqui (representado pelos arquivos `PessoasAqui-icon-512x512.png`, `PessoasAqui-logo-1024x1024.png`, `app/src/main/res/mipmap-*/ic_launcher*` e `app/src/main/res/drawable/ic_launcher*`) está **RIGOROSAMENTE CONGELADO E DEFINITIVO**.
- **PROIBIÇÃO:** **NÃO altere, não substitua, não recrie e não modifique este logotipo por nenhum motivo**, a menos que o usuário peça expressamente e de forma inequívoca em uma nova instrução.
- Todos os builds futuros (`bundleRelease`, `assembleDebug`), lançamentos e documentações devem preservar intacta essa identidade visual oficial.

## 🛡️ Diretrizes de Arquitetura e Segurança
1. **Privacidade e E2EE:** Manter sempre a criptografia de ponta a ponta (E2EE) nas mensagens e chamadas entre pares (ECDH P-256 + AES-256-GCM).
2. **Radar de Proximidade BLE (Modo A):** Preservar a detecção em raio de aproximadamente 10 metros, conexões mútuas, proteção por PIN, independência da internet e sessões temporárias com promoção autorizada do histórico.
3. **Conexões à Distância (Modo B):** Preservar conexões bilaterais permanentes, convites LINK + QR, envio pela internet e histórico criptografado.
4. **Recuperação Familiar V3:** Preservar PIN, cofres, guardiões, Shamir V3, rotação e restauração de identidade.
5. **Padrões de Segurança Infantil:** Preservar as páginas e rotas `/child-safety`, termos e políticas públicas de tolerância zero.
6. **Compatibilidade Google Play:** Manter `minSdkVersion >= 24` para compatibilidade com a Proteção Automática de Integridade do Google Play.

## ⚖️ Protocolo Permanente de Integridade e Prevenção de Regressões (Obrigatório)
1. **Regra Fundamental de Avaliação Multidirecional:** Toda alteração (correção, melhoria ou refatoração) DEVE ter seus efeitos avaliados em todas as direções da arquitetura:
   - **Para trás:** chamadores e produtores de dados.
   - **Para frente:** componentes, serviços e funções utilizados.
   - **Lateralmente:** fluxos paralelos, concorrência, transportes alternativos.
   - **Verticalmente:** interfaces, regras de negócio, persistência, criptografia, OS.
   - **Temporalmente:** retentativas, mensagens atrasadas, reinicializações, compatibilidade.
   - **Em Segurança:** autenticação, autorização, identidade, E2EE, proteção contra replay.
   - **Recursivamente:** consequências das próprias correções adicionais.
2. **Proteção contra Retrocessos:** NUNCA remover testes existentes para fazer uma alteração passar. Identificar garantias e comportamentos anteriores antes de qualquer edição.
3. **Ciclo Recursivo de Correções:** CORRIGIR → ANALISAR IMPACTOS → IDENTIFICAR CONSEQUÊNCIAS → CORRIGIR CONSEQUÊNCIAS → REANALISAR → TESTAR → VALIDAR.
4. **Contratos e Compatibilidade:** Revisar tipos de retorno, nulos, exceções, assincronismo, estados compartilhados, identificadores, tratamento de erros e concorrência.
5. **Testes e Evidências:** Testes unitários, de integração, concorrência e regressão. Usar PostgreSQL real isolado quando aplicável. Não afirmar que testes físicos foram realizados sem aparelhos reais.
6. **Matriz de Consequências Obrigatória:** Antes de cada publicação, documentar a matriz:
   `| Alteração | Dependência direta | Dependência indireta | Consequência possível | Consequência observada | Ação corretiva | Teste | Resultado |`
7. **Condições para Commit e Push:**
   - Trabalhar na branch `main`.
   - NUNCA usar force push.
   - NUNCA executar deploy no Render.
   - NUNCA modificar o Supabase de produção.
   - Validar testes no Android e Backend e compilar antes de publicar.
