# Guia de Formulários e Declarações — Google Play Console
### Como responder aos questionários obrigatórios para aprovação rápida sem rejeições

Este documento contém as respostas exatas para cada formulário exigido no painel da **Google Play Console** durante o envio do **PessoasAqui**.

---

## 🛡️ 1. Segurança dos Dados (Data Safety Form)

A Google Play exige que o desenvolvedor declare a coleta e o tratamento de dados.

### Visão Geral
- **O seu app coleta ou compartilha algum dado de usuário?**  
  👉 **SIM** *(Marque Sim, pois os registros de IP são retidos obrigatoriamente para o Art. 15 do Marco Civil da Internet).*
- **Todos os dados coletados são criptografados em trânsito?**  
  👉 **SIM** *(Todas as conexões usam TLS 1.3 / HTTPS e as mensagens usam E2EE com AES-256-GCM).*
- **O app oferece um método para os usuários solicitarem a exclusão de seus dados?**  
  👉 **SIM** *(Dentro do app há opção para redefinir/excluir conta e chaves).*
- **URL da solicitação de exclusão (se solicitada):**  
  👉 `https://pessoasaqui.onrender.com/privacy`

### Detalhamento por Categoria de Dado:

| Tipo de Dado | Coletado? | Compartilhado? | Finalidade | Criptografado em Trânsito? | Obrigatório ou Opcional? |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Localização Exata/Aproximada** | **NÃO** | **NÃO** | *Não há coleta de GPS contínuo.* | — | — |
| **Informações Pessoais (Nome, E-mail, Telefone, CPF)** | **NÃO** | **NÃO** | *Não há cadastro tradicional.* | — | — |
| **Mensagens e E-mails (Conteúdo de Chat)** | **NÃO** | **NÃO** | *O servidor é um envelope cego E2EE e não armazena o texto claro das conversas.* | SIM | — |
| **Fotos e Vídeos (Mídia do Chat)** | **NÃO** | **NÃO** | *Transmitidos criptografados ponta a ponta sem custódia do servidor.* | SIM | — |
| **Fotos do Mural de Ofertas** | **SIM** | **NÃO** | Funcionalidade do app (exibição no mural público voluntário). | SIM | Opcional |
| **Identificadores do Dispositivo (Hardware / IP)** | **SIM** | **NÃO** | 1. Cumprimento de obrigação legal (Art. 15 Marco Civil da Internet)<br>2. Prevenção a fraudes e segurança da rede (banimento de invasores). | SIM | Obrigatório |

---

## 🔞 2. Classificação de Conteúdo (Questionário IARC)

Ao preencher o questionário da IARC na Play Console:

1. **Categoria do Aplicativo:** Selecione **"Comunicação ou Social"** ou **"Utilitário"**.
2. **O app compartilha a localização física do usuário com terceiros?**  
   👉 **NÃO** *(O rádio BLE apenas sinaliza proximidade de 10m, sem coordenadas geográficas precisas compartilhadas).*
3. **O app permite que os usuários troquem mensagens de texto ou interajam?**  
   👉 **SIM**.
4. **O app possui mecanismos para filtrar ou moderar conteúdos impróprios?**  
   👉 **SIM** *(Possui bloqueio de contatos, sistema de denúncia formal e moderação ativa).*
5. **O app contém compras digitais (In-App Purchases)?**  
   👉 **SIM** *(Se utilizar a assinatura VIP via Google Play Billing).*
6. **Resultado Esperado:** Classificação indicativa **+18 anos (Maduro / Classificação Indicativa Adulta)** devido à liberdade de comunicação e diretriz de maioridade do PessoasAqui.

---

## 👥 3. Público-Alvo e Conteúdo (Target Audience)

- **Faixa etária do público-alvo:** Selecione exclusivamente **"18 anos ou mais"**.
- **O app foi desenvolvido intencionalmente para crianças?**  
  👉 **NÃO**.

---

## 🔑 4. Acesso ao App para Avaliação dos Revisores da Google (App Access)

A equipe do Google precisa testar o app para aprová-lo. Como o PessoasAqui **não pede e-mail ou senha**, explique no campo de instruções:

- Marque: **"Todas as funcionalidades estão disponíveis sem restrições de login especial"**  
  *(OU, se solicitar explicação)*:
- Texto explicativo para os revisores:
  > *"O PessoasAqui opera com criptografia ponta a ponta sem cadastro tradicional por e-mail ou telefone. Ao abrir o app, toque em 'Confirmar que tenho +18 anos', insira qualquer apelido de teste e defina um PIN numérico de 4 a 6 dígitos. O aplicativo concederá acesso imediato a todas as telas, radar de demonstração e chats."*

---

## 📡 5. Declaração de Permissões e Serviços em Primeiro Plano (Foreground Services)

O aplicativo utiliza permissões específicas do Android que devem ser justificadas na Play Console:

1. **Permissão de Bluetooth / Dispositivos Próximos (`BLUETOOTH_SCAN`, `BLUETOOTH_ADVERTISE`, `NEARBY_WIFI_DEVICES`):**  
   - **Justificativa:** *"Essencial para o recurso principal do aplicativo, que é a detecção por rádio de presença física entre dispositivos em um raio de até 10 metros sem rastreamento por satélite."*
2. **Serviço em Primeiro Plano (`FOREGROUND_SERVICE_CONNECTED_DEVICE` e `FOREGROUND_SERVICE_MEDIA_PROJECTION`):**  
   - **Justificativa:** *"Necessário para manter o sinal de rádio de proximidade ativo e garantir o recebimento ininterrupto de chamadas de voz e vídeo criptografadas quando o usuário opta por manter o radar ligado."*
3. **Câmera e Microfone (`CAMERA`, `RECORD_AUDIO`):**  
   - **Justificativa:** *"Utilizados exclusivamente quando o usuário decide iniciar uma chamada de voz/vídeo cifrada ou gravar notas de voz sob demanda."*

---

## 🛡️ 6. Declaração de Padrões de Segurança Infantil (Child Safety Standards) — [NOVO]

Esta declaração tornou-se obrigatória para apps sociais e de mensagens para cumprir a **Política de Padrões de Segurança Infantil (Prevenção ao CSAE)**.

### Onde acessar no Google Play Console:
1. No menu lateral esquerdo, clique em **Políticas e programas** (ou **Conteúdo do app**).
2. Na aba de avisos ou na lista de pendências, localize:  
   👉 **"Padrões de segurança infantil"** (ou clique diretamente na barra vermelha *"Corrija as violações da política"*).
3. Clique em **"Iniciar declaração"** ou **"Gerenciar"**.

### Respostas e Campos Obrigatórios para Preencher:

1. **O seu aplicativo possui padrões publicados que proíbem o abuso e exploração sexual infantil (CSAE)?**  
   👉 Selecione **SIM**.

2. **URL dos padrões publicados de segurança infantil:**  
   👉 Cole exatamente o link público direto:  
   ```text
   https://pessoasaqui.onrender.com/child-safety
   ```
   *(Link alternativo que também é válido: `https://pessoasaqui.onrender.com/legal#seguranca-infantil`)*

   > **Garantias atendidas por esta página:**
   > - Carrega em HTML válido (HTTP 200), sem PDFs ou formulários fechados;
   > - Cita nominalmente o aplicativo: `PessoasAqui — Presença & Chat` e o pacote `br.com.pessoasaqui`;
   > - Cita nominalmente o desenvolvedor: `fabio aquino` e `PessoasAqui Team`;
   > - Proíbe expressamente CSAE, CSAM, aliciamento (*grooming*) e pornografia infantil;
   > - Detalha o processo de denúncia in-app e cooperação com Polícia Federal, SaferNet e NCMEC.

3. **Ponto de contato de segurança infantil (Child safety point of contact):**  
   👉 Digite o e-mail de contato prioritário de segurança infantil:  
   ```text
   seguranca-infantil@pessoasaqui.com.br
   ```
   *(Ou, caso o Google exija o e-mail direto da sua conta de desenvolvedor: `ninfoboyn@gmail.com`)*.

4. **Finalização:**  
   - Clique em **"Salvar"** no canto inferior direito.
   - Em seguida, vá na **Visão geral da publicação** (Publishing overview) e clique em **"Enviar para análise"** se solicitado.
   - O aviso de violação com prazo de 19 de outubro será automaticamente resolvido e baixado pela equipe de políticas da Google Play.

