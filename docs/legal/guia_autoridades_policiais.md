# Guia Prático e Jurídico para Autoridades Policiais e Judiciais
### PessoasAqui — Informações Periciais, Cumprimento de Ordens e Limites da Criptografia Ponta a Ponta
**Data de Emissão:** Setembro de 2026  
**Destinatários:** Magistrados, Membros do Ministério Público, Delegados de Polícia Civil e Federal, e Peritos Criminais Oficiais.

---

### 1. Finalidade deste Documento

O presente guia tem por objetivo prestar esclarecimentos técnicos, periciais e operacionais detalhados às autoridades públicas encarregadas da aplicação da lei no Brasil sobre o funcionamento do aplicativo **PessoasAqui**, em conformidade com:
- A **Lei Federal nº 12.965/2014** (Marco Civil da Internet);
- O **Decreto Federal nº 8.771/2016** (Regulamentação do Marco Civil da Internet);
- A **Lei Federal nº 13.709/2018** (Lei Geral de Proteção de Dados Pessoais - LGPD);
- A **Constituição da República Federativa do Brasil de 1988** (Art. 5º, incisos X e XII).

O PessoasAqui adota uma postura de **cooperação institucional transparente e imediata** com ordens judiciais emitidas por juízos competentes, fornecendo todas as informações legalmente mantidas em sua infraestrutura, ao mesmo tempo em que esclarece os limites matemáticos e arquiteturais decorrentes do uso da criptografia de ponta a ponta.

---

### 2. Natureza Tecnológica da Plataforma: Arquitetura de Conhecimento Zero (*Zero-Knowledge*)

Para a correta instrução de procedimentos investigatórios criminais (Inquéritos Policiais, PICs e Ações Penais), é imprescindível compreender a modelagem computacional da plataforma:

2.1. **Inexistência de Cadastro Convencional:**  
Diferente de redes sociais tradicionais ou aplicativos legados de mensagens, o PessoasAqui **não opera com cadastro por número de telefone celular (SIM card), endereço de e-mail, nome civil ou CPF**.

2.2. **Identidades Criptográficas Assimétricas:**  
Cada conta é identificada tecnicamente pelo *hash* criptográfico SHA-256 de sua chave pública Ed25519 (denominada "Identidade Técnica" ou `identityHash`). As chaves privadas correlatas são geradas por algoritmos no próprio *hardware* do aparelho móvel e **jamais saem da custódia do aparelho do usuário**.

2.3. **Comunicação por Rádio Local e Envelopes Cegos:**  
- A aproximação física entre usuários é intermediada por sinais de rádio Bluetooth Low Energy (BLE) efêmeros, sem guarda de coordenadas GPS contínuas em servidores.
- O tráfego de mensagens em trânsito pela internet utiliza a técnica de **Envelopes Cegos (*Blind Relays*)**: o servidor atua como mero comutador de pacotes cifrados, conhecendo apenas o destinatário técnico (chave pública) para encaminhar o fluxo de bits, sem ter como decodificar a carga útil (*payload*).

---

### 3. Criptografia de Ponta a Ponta: Impossibilidade Técnica de Interceptação ou Decifração

3.1. **Padrão Criptográfico Utilizado:**  
Todas as mensagens privadas, chamadas de voz e vídeo e arquivos multimídia são protegidos de ponta a ponta por uma combinação de:
- **X25519 (Curvas Elípticas Curve25519):** Para troca sigilosa de chaves de sessão efêmeras através do algoritmo Diffie-Hellman;
- **AES-256-GCM (Galois/Counter Mode):** Cifra simétrica autenticada de 256 bits para o conteúdo.

3.2. **Inexistência de Portas dos Fundos (*No Backdoors / No Master Keys*):**  
A plataforma **não possui chaves-mestras, mecanismos de quebra forçada, custódia de chaves (*key escrow*) ou qualquer porta de acesso secundário**.  
A decifração de uma mensagem privada depende exclusivamente da chave privada que reside na memória protegida do aparelho do destinatário ou do remetente.

3.3. **Aplicação do Princípio Jurídico *Impossibilium Nulla Obligatio Est*:**  
O ordenamento jurídico pátrio e a doutrina processual penal consolidada reconhecem que ordens de interceptação telemática ou de fornecimento de conteúdo que exijam a quebra da criptografia ponta a ponta são de **cumprimento materialmente impossível** pelo provedor de aplicação (*ad impossibilia nemo tenetur*).  
A impossibilidade de fornecimento do conteúdo das conversas decorre de uma barreira matemática e da integridade do código, e **não de recusa ou desobediência a determinações judiciais**.

> 💡 **Nota Pericial para Investigadores:**  
> A obtenção do conteúdo das conversas em investigações criminais deve ser realizada por meio de **apreensão e perícia forense direta no dispositivo físico do investigado** (mediante mandado judicial de busca e apreensão fundamentado no Art. 240 do Código de Processo Penal), com extração física ou lógica da memória do aparelho, ou através da colaboração de uma das partes envolvidas.

---

### 4. Dados que a Plataforma PODE e DEVE Fornecer Mediante Ordem Judicial

Em estrito cumprimento aos **Artigos 10, § 1º, 11 e 15 da Lei nº 12.965/2014 (Marco Civil da Internet)**, mediante expressa ordem judicial emanada por autoridade judiciária competente (Art. 22 do Marco Civil), o PessoasAqui fornecerá:

| Categoria do Dado | Descrição Técnica | Base Legal | Prazo Máximo de Retenção |
| :--- | :--- | :--- | :--- |
| **Registros de Conexão à Aplicação** | Endereços IP de origem, porta lógica associada, data e hora UTC de início e término das sessões vinculadas à Identidade Técnica sob investigação. | Art. 15, Lei 12.965/2014 | **6 (seis) meses** |
| **Data e Hora de Registro** | Momento em que a chave pública foi inserida pela primeira vez na rede. | Art. 15, Lei 12.965/2014 | Durante a vigência da conta |
| **Assinatura do Aparelho (*Fingerprint*)** | Código alfanumérico identificador do hardware utilizado para controle de banimentos. | Art. 10, § 1º, Lei 12.965/2014 | Enquanto persistir sanção |
| **Ofertas Públicas no Mural** | Textos, categorias e valores anunciados publicamente pelo investigado na aba "Ofertas" do aplicativo. | Art. 10, § 1º, Lei 12.965/2014 | Enquanto ativas ou em cache de moderação |
| **Histórico de Sanções e Denúncias** | Relatórios de denúncias formais registradas contra a identidade, notas de moderação e motivos de banimento prévio. | Art. 10, § 1º, Lei 12.965/2014 | 6 (seis) meses |

---

### 5. Dados que a Plataforma NÃO POSSUI e NÃO PODE Fornecer

Em virtude de sua arquitetura de privacidade e minimização, o PessoasAqui informa que **não possui em seus bancos de dados**:
- ❌ Conteúdo em texto claro de mensagens trocadas no chat privado;
- ❌ Gravações em áudio ou vídeo de chamadas realizadas entre usuários;
- ❌ Chaves privadas criptográficas dos interlocutores;
- ❌ Nomes civis, números de CPF, cópias de documentos de identificação ou filiação;
- ❌ Números de telefone celular (linhas móveis) ou operadoras de telefonia;
- ❌ Endereços de e-mail de usuários;
- ❌ Histórico geográfico de trajetos ou registros contínuos de coordenadas GPS de aparelhos;
- ❌ Agendas telefônicas ou listas de contatos armazenadas no celular dos usuários.

---

### 6. Procedimento para Requisições Judiciais e Preservação de Registros

#### 6.1. Requisitos Formais da Ordem Judicial
Para que a requisição de quebra de sigilo de registros de aplicação seja processada com agilidade e segurança jurídica, o mandado ou ofício judicial deve conter:
1. **Identificador Técnico Preciso:** O *hash* completo da chave pública do investigado (Identidade Técnica no formato hexadecimal de 64 caracteres) OU o endereço IP de conexão e a porta lógica com a data/hora exata em fuso horário UTC;
2. **Delimitação Temporal:** Indicação precisa do período de registros pretendido (respeitado o limite legal de até 6 meses pretéritos, salvo se houver pedido de preservação anterior);
3. **Assinatura Digital Válida:** Documento assinado digitalmente por autoridade judiciária competente (com certificado digital ICP-Brasil);
4. **Indicação do Inquérito ou Processo:** Número do processo judicial, vara/comarca de origem e órgão policial requisitante.

#### 6.2. Pedidos Cautelares de Preservação de Registros (Art. 15, § 2º do Marco Civil)
A autoridade policial ou o Ministério Público poderão requerer formalmente, de forma cautelar, a **preservação de registros de acesso a aplicações** por prazo superior a 6 (seis) meses, a fim de viabilizar a posterior obtenção de ordem judicial de quebra de sigilo.  
O pedido de preservação deve especificar o identificador técnico da identidade investigada e será atendido prontamente, mantendo-se os dados em sigilo até ulterior decisão judicial.

#### 6.3. Casos de Emergência com Risco Iminente à Vida ou Exploração Infantil (CSAM)
Em casos que envolvam risco iminente de morte, sequestro em andamento ou identificação de materiais de abuso e exploração sexual infantil (CSAM), a equipe jurídica e de segurança do PessoasAqui atua com regime de **prioridade máxima e resposta emergencial ininterrupta**.

---

### 7. Canal Oficial de Comunicação Jurídica

Todas as ordens judiciais, ofícios de preservação e notificações das autoridades públicas devem ser encaminhadas exclusivamente pelo canal oficial:

- **E-mail Institucional:** `autoridades@pessoasaqui.com.br`  
  *(Apenas serão aceitas mensagens originadas de domínios oficiais de tribunais, ministérios públicos ou corporações policiais: ex: `@tjsp.jus.br`, `@pf.gov.br`, `@policiacivil.sp.gov.br`, `@mpf.mp.br`)*
- **Endereço para Intimações Formais:** São Paulo - SP, Brasil.
