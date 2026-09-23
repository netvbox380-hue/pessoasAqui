const path = require('path');
const { server } = require(path.join(__dirname, '../backend/src/server.js'));

const PORT = process.env.PORT || 3000;

if (!server.listening) {
  server.listen(PORT, () => {
    console.log(`[PessoasAqui Backend] Servidor HTTP e WebSocket rodando na porta ${PORT}`);
    console.log(`[PessoasAqui Backend] Conectado ao Supabase com sucesso.`);
  });
}
