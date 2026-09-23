# PessoasAqui — Servidor Backend, Banco Relacional & Motor Criptográfico

Este é o servidor backend em tempo real do **PessoasAqui**, construído em Node.js com WebSockets e suporte nativo ao banco de dados relacional **Supabase (PostgreSQL)** e hospedagem no **Render**.

---

## 🏗️ 1. Como Rodar Localmente no seu Computador

O servidor pode ser testado localmente sem precisar configurar nada externo imediatamente (ele possui fallback inteligente em memória).

1. Abra o terminal na pasta `backend`:
   ```bash
   cd backend
   npm install
   npm start
   ```
2. O servidor estará rodando em: `http://localhost:3000`
3. Teste no navegador ou com `curl http://localhost:3000`.

---

## ⚡ 2. Como Configurar o Banco no Supabase (Produção)

1. Crie um projeto gratuito no [Supabase](https://supabase.com).
2. No painel do seu projeto Supabase, acesse **SQL Editor** no menu lateral esquerdo.
3. Abra o arquivo `backend/schema.sql`, copie todo o conteúdo e cole no SQL Editor do Supabase.
4. Clique em **Run**.
5. Todas as 8 tabelas relacionais (`identities`, `devices`, `connections`, `family_relationships`, `recovery_authorizations`, `e2ee_messages`, `moderation_reports`, etc.) serão criadas com índices otimizados!
6. Copie a **Connection String (URI)** em:  
   *Project Settings → Database → Connection string (Node.js)*.

---

## 🚀 3. Como Fazer o Deploy no Render (Produção)

1. Crie uma conta no [Render](https://render.com).
2. Clique em **New +** → **Web Service**.
3. Conecte o repositório do projeto.
4. Configure os campos:
   - **Name**: `pessoasaqui-backend`
   - **Root Directory**: `backend`
   - **Environment**: `Node`
   - **Build Command**: `npm install`
   - **Start Command**: `node src/server.js`
5. Na seção **Environment Variables**, adicione:
   - `DATABASE_URL`: Cole a Connection String obtida no Supabase.
6. Clique em **Deploy Web Service**.
7. O Render gerará uma URL segura HTTPS/WSS (ex: `https://pessoasaqui-backend.onrender.com`), pronta para ser usada pelos celulares Android em qualquer lugar do mundo!

---

## 🔐 4. Garantias Criptográficas Implementadas

- **Relay Cego (Zero-Knowledge)**: O servidor nunca descriptografa o conteúdo das mensagens privadas; ele atua apenas como canal de trânsito de envelopes cifrados.
- **1 Identidade = 1 Dispositivo Ativo**: Quando uma identidade é recuperada em um novo celular, o servidor emite imediatamente um evento via WebSocket revogando a sessão do celular antigo.
- **Proteção Progressiva contra Força Bruta no PIN**: Aplica a escada de bloqueios (3ª tentativa=5h, 4ª=10h, etc.) apenas na tentativa de recuperação, mantendo o celular original ativo e conectado.
