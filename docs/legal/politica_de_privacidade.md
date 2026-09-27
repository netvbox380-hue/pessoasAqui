# Política de Privacidade e Proteção de Dados — PessoasAqui
**Última atualização:** 27 de setembro de 2026  
**Versão:** 1.0 (Conformidade com a LGPD — Lei nº 13.709/2018 e Marco Civil da Internet — Lei nº 12.965/2014)

---

### 1. Compromisso com a Privacidade e o Princípio de Minimização

O **PessoasAqui** foi concebido sob a premissa de **Privacidade por Padrão e por Design (*Privacy by Design and by Default*)**. Acreditamos que a forma mais eficaz e segura de proteger os dados dos cidadãos é **não coletá-los quando desnecessários**.

Esta Política de Privacidade descreve com transparência e clareza técnica:
- Quais dados são necessários para a operação do Aplicativo;
- Quais dados são expressamente **inacessíveis** à nossa infraestrutura;
- Como cumprimos com rigor os deveres de guarda de registros previstos no **Marco Civil da Internet (Lei nº 12.965/2014)**;
- Como asseguramos o pleno exercício dos direitos dos titulares previstos na **Lei Geral de Proteção de Dados Pessoais (LGPD — Lei nº 13.709/2018)**.

---

### 2. Dados que NÃO Coletamos e NÃO Processamos

Em consonância com o princípio da **necessidade e minimização de dados (Art. 6º, inciso III da LGPD)**, o PessoasAqui **NÃO** coleta, não solicita e não armazena:
- ❌ **Dados Cadastrais Civis:** Nome completo, filiação, RG, CPF, título de eleitor ou data de nascimento.
- ❌ **Dados de Contato Tradicionais:** Número de telefone celular (SIM card) ou endereço de e-mail.
- ❌ **Agenda Telefônica ou Contatos do Aparelho:** O Aplicativo não requer nem faz leitura da lista de contatos do seu celular.
- ❌ **Senhas em Texto Claro:** Seu PIN pessoal é protegido no próprio aparelho via função criptográfica de derivação de chaves (Argon2 / SHA-256 com sal) e nunca trafega legível na rede.
- ❌ **Rastreamento Geográfico Contínuo ou Histórico de Localização (GPS):** Não monitoramos suas rotas diárias nem guardamos histórico de onde você esteve. A presença local é baseada estritamente em sinais efêmeros de proximidade física (rádio BLE em raio de ~10m) e expira quando você se afasta.

---

### 3. Dados e Metadados Técnicos Coletados e Sua Finalidade

Para viabilizar a conexão entre dispositivos e cumprir obrigações legais indelegáveis, processamos estritamente as seguintes categorias de informações técnicas:

#### A. Identidade Técnica Criptográfica (Pseudônimo Matemático)
- **O que é:** O identificador da sua conta é o *hash* criptográfico SHA-256 derivado da sua chave pública Ed25519 (exemplo: `a3f89e21...b47c`).
- **Finalidade:** Endereçamento técnico para encaminhamento de envelopes criptografados no servidor.
- **Base Legal (LGPD):** Execução de contrato e procedimentos preliminares (Art. 7º, inciso V).

#### B. Registros de Acesso a Aplicações de Internet (Dever Legal — Marco Civil da Internet)
- **O que é:** Em cumprimento obrigatório ao **Artigo 15 da Lei Federal nº 12.965/2014 (Marco Civil da Internet)**, mantemos os registros de acesso à nossa aplicação:
  1. Endereço IP (Protocolo de Internet) de origem da conexão;
  2. Porta lógica de origem associada à conexão;
  3. Data, hora de início e término de cada sessão (com indicação do fuso horário UTC);
  4. Identificador técnico de sessão e da conexão WebSocket.
- **Prazo Estrito de Guarda:** Os registros de acesso a aplicações são mantidos sob sigilo, em ambiente controlado e de segurança, **pelo prazo legal mínimo de 6 (seis) meses**.
- **Finalidade e Base Legal:** Cumprimento de obrigação legal ou regulatória pelo controlador (Art. 7º, inciso II da LGPD c/c Art. 15 do Marco Civil da Internet).

#### C. Assinatura Digital do Dispositivo (*Device Fingerprint*)
- **O que é:** Um identificador criptográfico unilateral gerado a partir de parâmetros técnicos do hardware do aparelho.
- **Finalidade:** Aplicação de sanções de moderação contra violações críticas (impedindo a evasão de banimentos de abusadores graves que desinstalem e reinstalem o aplicativo).
- **Base Legal (LGPD):** Exercício regular de direitos e legítimo interesse na segurança da comunidade (Art. 7º, incisos IX e VI).

#### D. Conteúdos Públicos no Mural de Ofertas
- **O que é:** Textos, categorias, fotos de produtos e preços que o próprio Usuário opte voluntariamente por divulgar na aba "Ofertas" do Aplicativo.
- **Finalidade:** Exibição pública no radar da localidade enquanto a oferta estiver ativa.

---

### 4. Criptografia de Ponta a Ponta (E2EE) e Inviolabilidade das Conversas

4.1. **Inacessibilidade Absoluta do Conteúdo:**  
Todas as mensagens privadas de texto, áudio gravado, imagens, documentos e fluxos de chamadas de voz e vídeo transmitidos pelo PessoasAqui utilizam **Criptografia de Ponta a Ponta (End-to-End Encryption - E2EE)** de padrão militar:
- Protocolo Diffie-Hellman em curvas elípticas (X25519) para negociação secreta de chaves de sessão efêmeras;
- Cifra simétrica autenticada **AES-256-GCM** para proteção do conteúdo das mensagens e integridade contra adulterações.

4.2. **Envelopes Cegos e Descarte Transitório:**  
Os servidores do PessoasAqui atuam como meros repetidores de pacotes (*blind relays*). O servidor recebe um envelope opaco cifrado, verifica apenas o identificador da chave pública do destinatário para roteamento e repassa o pacote.  
Assim que a mensagem é entregue ao aparelho de destino, ela é **imediatamente eliminada da memória e das filas do servidor**.

4.3. **Chaves Privadas Não Custodiadas:**  
As chaves privadas necessárias para descriptografar os dados são geradas e armazenadas exclusivamente na *sandbox* protegida do dispositivo do Usuário. **Nenhum operador, engenheiro, administrador de sistema ou autoridade governamental tem capacidade técnica de ler ou descriptografar as conversas no trânsito dos servidores**.

---

### 5. Exceção de Moderação por Denúncia Formal de Abuso

A única hipótese em que um trecho de mensagem é analisado por seres humanos é quando um dos participantes da própria conversa utiliza a função **"Denunciar abuso"**:
- Neste caso, o aplicativo do denunciante empacota as últimas 5 (cinco) mensagens daquela conversa específica como pacote de prova assinado digitalmente e o transmite à equipe de moderação.
- A finalidade estrita deste envio é coibir crimes graves, assédio, extorsão e fraudes, protegendo a integridade da plataforma. As mensagens de outras conversas não denunciadas permanecem 100% privadas e invioladas.

---

### 6. Compartilhamento de Dados com Terceiros

O PessoasAqui **NÃO vende, não aluga, não compartilha e não monetiza** dados pessoais ou metadados de seus usuários com empresas de publicidade, corretores de dados (*data brokers*) ou parceiros comerciais.

O compartilhamento de dados ocorre unicamente nas seguintes hipóteses estritas e delimitadas por lei:
1. **Ordens Judiciais Específicas:** Fornecimento exclusivo dos registros de conexão (IPs, portas lógicas e horários) previstos no Art. 15 do Marco Civil da Internet, mediante ordem judicial fundamentada emanada por autoridade judiciária competente (Art. 10, § 1º da Lei 12.965/2014);
2. **Provedores de Infraestrutura Técnica:** Servidores de computação em nuvem (hospedagem de bancos de dados relacionais e roteamento WebSocket), que atuam como meros operadores sob cláusulas contratuais estritas de confidencialidade e segurança da informação.

---

### 7. Segurança da Informação e Padrões Técnicos

Adotamos medidas técnicas e administrativas aptas a proteger os dados contra acessos não autorizados e situações acidentais ou ilícitas:
- Protocolos de comunicação protegidos por Transport Layer Security (TLS 1.3 / WSS) para todas as requisições web;
- Isolamento de banco de dados com credenciais criptográficas rotativas;
- Mecanismo de *Circuit Breaker* para evitar vazamentos em caso de falhas de conectividade;
- Proteção progressiva contra ataques de força bruta no PIN numérico.

---

### 8. Direitos dos Titulares de Dados (Art. 18 da LGPD)

Em conformidade com o Artigo 18 da LGPD, o Usuário possui o direito de obter da Plataforma:
- **Confirmação e Acesso:** Saber quais dados técnicos vinculados à sua chave pública estão mantidos no sistema;
- **Eliminação dos Dados:** Solicitar a exclusão de sua identidade técnica, registros de presença e ofertas do servidor, o que pode ser feito a qualquer momento através da opção "Redefinir / Excluir Conta" dentro do próprio Aplicativo;
- **Portabilidade:** Exportar suas conversas e configurações locais através da função de Cofre P2P Distribuído em formato JSON cifrado.

---

### 9. Contato do Encarregado de Proteção de Dados (DPO)

Para exercer seus direitos de privacidade, esclarecer dúvidas sobre o tratamento de metadados ou enviar notificações formais sobre proteção de dados, entre em contato através de:
- **Canal Integrado no App:** Menu Superior → *"Petição & Suporte Oficial"*;
- **E-mail do Encarregado:** `privacidade@pessoasaqui.com.br` (ou canal direto de suporte da plataforma).
