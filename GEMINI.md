# PessoasAqui — Diretrizes e Regras Permanentes do Projeto

## 🔒 Diretriz Crítica de Branding e Identidade Visual (Logotipo Oficial)
- **REGRA INVIOLÁVEL:** O logotipo oficial do aplicativo PessoasAqui (representado pelos arquivos `PessoasAqui-icon-512x512.png`, `PessoasAqui-logo-1024x1024.png`, `app/src/main/res/mipmap-*/ic_launcher*` e `app/src/main/res/drawable/ic_launcher*`) está **RIGOROSAMENTE CONGELADO E DEFINITIVO**.
- **PROIBIÇÃO:** **NÃO altere, não substitua, não recrie e não modifique este logotipo por nenhum motivo**, a menos que o usuário peça expressamente e de forma inequívoca em uma nova instrução.
- Todos os builds futuros (`bundleRelease`, `assembleDebug`), lançamentos e documentações devem preservar intacta essa identidade visual oficial.

## 🛡️ Diretrizes de Arquitetura e Segurança
1. **Privacidade e E2EE:** Manter sempre a criptografia de ponta a ponta (E2EE) nas mensagens e chamadas entre pares.
2. **Radar de Proximidade BLE:** Preservar a detecção em raio de 10 metros, conexões mútuas e proteção por PIN.
3. **Padrões de Segurança Infantil:** Preservar as páginas e rotas `/child-safety`, termos e políticas públicas de tolerância zero.
4. **Compatibilidade Google Play:** Manter `minSdkVersion >= 24` para compatibilidade com a Proteção Automática de Integridade do Google Play.
