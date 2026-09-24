require('dotenv').config();
const express = require('express');
const http = require('http');
const WebSocket = require('ws');
const cors = require('cors');
const { initDatabase, localStore, isPostgres, query } = require('./db');
const { hashPin, generateNonce, calculateLockoutDurationSeconds } = require('./crypto');

const app = express();
const server = http.createServer(app);
const wss = new WebSocket.Server({ server });

const PORT = process.env.PORT || 3000;

app.use(cors());
app.use(express.json({ limit: '25mb' }));
app.use(express.urlencoded({ extended: true, limit: '25mb' }));

// Inicia conexão com banco (Supabase PostgreSQL ou local)
initDatabase();

// Mapa de conexões WebSocket ativas: identityHash -> WebSocket (1 dispositivo ativo por identidade)
const activeSockets = new Map();
// Mapa de presença ativa em tempo real: identityHash -> { identityHash, alias, intent, lastSeen }
const activePresence = new Map();
// Fila de mensagens pendentes para entrega garantida: recipientHash -> [messages]
const pendingMessages = new Map();
// Cache de deduplicação de mensagens (evita mensagens repetidas/duplicadas)
const recentMessageIds = new Map();

function isDuplicateMessage(msgId) {
  if (!msgId) return false;
  const now = Date.now();
  if (recentMessageIds.has(msgId)) return true;
  recentMessageIds.set(msgId, now);
  // Limpeza de mensagens com mais de 5 minutos
  if (recentMessageIds.size > 5000) {
    for (const [id, ts] of recentMessageIds.entries()) {
      if (now - ts > 300000) recentMessageIds.delete(id);
    }
  }
  return false;
}

wss.on('connection', (ws, req) => {
  let authenticatedIdentity = null;

  ws.on('message', (data) => {
    try {
      const msg = JSON.parse(data.toString());

      if (msg.type === 'AUTH') {
        authenticatedIdentity = (msg.identityHash || '').replace('peer-', '').trim();
        // Regra 18: 1 Identidade = 1 Dispositivo Ativo. Fecha socket anterior se houver
        if (activeSockets.has(authenticatedIdentity)) {
          const oldWs = activeSockets.get(authenticatedIdentity);
          if (oldWs && oldWs !== ws) {
            try { oldWs.close(1000, "Replaced by active device connection"); } catch (_) {}
          }
        }
        activeSockets.set(authenticatedIdentity, ws);

        activePresence.set(authenticatedIdentity, {
          identityHash: authenticatedIdentity,
          alias: msg.alias || 'Usuário PessoasAqui',
          intent: msg.intent || 'QUERO_CONVERSAR',
          lastSeen: Date.now()
        });

        ws.send(JSON.stringify({ type: 'AUTH_SUCCESS', identityHash: authenticatedIdentity }));
        console.log(`[WS] Dispositivo conectado para identidade: ${authenticatedIdentity} (${msg.alias || 'Anônimo'})`);

        // Entrega mensagens pendentes para este dispositivo
        if (pendingMessages.has(authenticatedIdentity)) {
          const pending = pendingMessages.get(authenticatedIdentity) || [];
          pending.forEach((pm) => {
            ws.send(JSON.stringify(pm));
          });
          pendingMessages.delete(authenticatedIdentity);
        }

        // Envia lista atual de usuários presentes no radar para o novo cliente
        const now = Date.now();
        const existingPeers = Array.from(activePresence.values())
          .filter(p => p.identityHash !== authenticatedIdentity && (now - p.lastSeen < 120000));
        ws.send(JSON.stringify({ type: 'PRESENCE_SYNC', peers: existingPeers }));

        // Transmite anúncio de presença para os demais aparelhos conectados
        const peerData = activePresence.get(authenticatedIdentity);
        wss.clients.forEach((client) => {
          if (client !== ws && client.readyState === WebSocket.OPEN) {
            client.send(JSON.stringify({ type: 'PEER_ONLINE', peer: peerData }));
          }
        });
      }

      // Heartbeat periódico pelo WebSocket
      if (msg.type === 'HEARTBEAT' && authenticatedIdentity) {
        if (activePresence.has(authenticatedIdentity)) {
          const current = activePresence.get(authenticatedIdentity);
          current.lastSeen = Date.now();
          if (msg.alias) current.alias = msg.alias;
          if (msg.intent) current.intent = msg.intent;
        }
      }

      // Relay Cego de Mensagem Cifrada Ponta a Ponta (E2EE)
      if (msg.type === 'E2EE_MESSAGE' && authenticatedIdentity) {
        const { recipientHash, ciphertextPayload, ivNonce, messageId } = msg;
        const cleanRecipient = (recipientHash || '').replace('peer-', '').trim();
        const msgId = messageId || `${authenticatedIdentity}_${Date.now()}_${Math.random()}`;

        if (isDuplicateMessage(msgId)) {
          return;
        }

        const senderAlias = activePresence.get(authenticatedIdentity)?.alias || 'Usuário';

        const msgObj = {
          type: 'E2EE_MESSAGE_RECEIVED',
          messageId: msgId,
          senderHash: authenticatedIdentity,
          senderAlias: senderAlias,
          ciphertextPayload,
          ivNonce: ivNonce || '',
          timestamp: Date.now()
        };

        const targetWs = activeSockets.get(cleanRecipient);
        if (targetWs && targetWs.readyState === WebSocket.OPEN) {
          targetWs.send(JSON.stringify(msgObj));
        } else {
          if (!pendingMessages.has(cleanRecipient)) {
            pendingMessages.set(cleanRecipient, []);
          }
          pendingMessages.get(cleanRecipient).push(msgObj);
        }
      }
    } catch (e) {
      console.error('[WS] Erro no processamento de mensagem:', e.message);
    }
  });

  ws.on('close', () => {
    if (authenticatedIdentity && activeSockets.get(authenticatedIdentity) === ws) {
      activeSockets.delete(authenticatedIdentity);
      activePresence.delete(authenticatedIdentity);

      // Notifica aos outros que o usuário saiu do radar
      wss.clients.forEach((client) => {
        if (client.readyState === WebSocket.OPEN) {
          client.send(JSON.stringify({
            type: 'PEER_OFFLINE',
            identityHash: authenticatedIdentity
          }));
        }
      });
    }
  });
});

// ==========================================
// ROTAS REST DA API DO PESSOASAQUI
// ==========================================

app.get('/', (req, res) => {
  res.json({
    app: 'PessoasAqui Backend',
    slogan: 'PessoasAqui — converse com quem está perto.',
    version: '1.0.0',
    mode: isPostgres() ? 'Produção (Supabase / Render)' : 'Desenvolvimento Local',
    activeConnections: wss.clients.size,
    activePresenceCount: activePresence.size
  });
});

/**
 * Heartbeat de Presença (Dispositivos ativos no radar)
 */
app.post('/api/presence/heartbeat', (req, res) => {
  const { identityHash, alias, intent } = req.body;
  if (!identityHash) {
    return res.status(400).json({ error: 'identityHash obrigatório.' });
  }

  activePresence.set(identityHash, {
    identityHash,
    alias: alias || 'Usuário PessoasAqui',
    intent: intent || 'QUERO_CONVERSAR',
    lastSeen: Date.now()
  });

  res.json({ success: true, activePresenceCount: activePresence.size });
});

/**
 * Listagem de Pessoas Online no Radar de Proximidade
 */
app.get('/api/presence/nearby', (req, res) => {
  const myHash = req.query.myIdentity;
  const now = Date.now();
  const peers = Array.from(activePresence.values())
    .filter(p => p.identityHash !== myHash && (now - p.lastSeen < 120000));

  res.json({ success: true, count: peers.length, people: peers });
});

/**
 * Envio de Mensagem Cifrada via HTTP (Fallback redundante para WebSockets)
 */
app.post('/api/messages/send', (req, res) => {
  const { senderHash, recipientHash, ciphertextPayload, ivNonce, senderAlias, messageId } = req.body;
  if (!recipientHash || !ciphertextPayload) {
    return res.status(400).json({ error: 'recipientHash e ciphertextPayload são obrigatórios.' });
  }

  const cleanRecipient = (recipientHash || '').replace('peer-', '').trim();
  const cleanSender = (senderHash || '').replace('peer-', '').trim();
  const msgId = messageId || `${cleanSender}_${Date.now()}_${Math.random()}`;

  if (isDuplicateMessage(msgId)) {
    return res.json({ success: true, delivered: true, duplicate: true });
  }

  const msgObj = {
    type: 'E2EE_MESSAGE_RECEIVED',
    messageId: msgId,
    senderHash: cleanSender,
    senderAlias: senderAlias || activePresence.get(cleanSender)?.alias || 'Usuário',
    ciphertextPayload,
    ivNonce: ivNonce || '',
    timestamp: Date.now()
  };

  let delivered = false;
  const targetWs = activeSockets.get(cleanRecipient);

  if (targetWs && targetWs.readyState === WebSocket.OPEN) {
    targetWs.send(JSON.stringify(msgObj));
    delivered = true;
  } else {
    if (!pendingMessages.has(cleanRecipient)) {
      pendingMessages.set(cleanRecipient, []);
    }
    pendingMessages.get(cleanRecipient).push(msgObj);
  }

  res.json({ success: true, delivered });
});

/**
 * Consulta de Mensagens Pendentes via HTTP
 */
app.get('/api/messages/pending', (req, res) => {
  const rawId = req.query.identityHash;
  if (!rawId) {
    return res.status(400).json({ error: 'identityHash obrigatório.' });
  }
  const cleanId = rawId.replace('peer-', '').trim();
  const pending = pendingMessages.get(cleanId) || [];
  pendingMessages.delete(cleanId);
  res.json({ success: true, messages: pending });
});

/**
 * Registro / Inicialização da Identidade Técnica Criptográfica (Sem dados tradicionais)
 */
app.post('/api/identities/register', async (req, res) => {
  const { identityHash, publicKeyEd25519, publicKeyX25519, pin, alias } = req.body;

  if (!identityHash || !publicKeyEd25519 || !pin) {
    return res.status(400).json({ error: 'Parâmetros de identidade criptográfica incompletos.' });
  }

  const pinHash = hashPin(pin);

  if (isPostgres()) {
    try {
      await query(
        `INSERT INTO identities (id, public_key_ed25519, public_key_x25519, pin_salted_hash, alias)
         VALUES ($1, $2, $3, $4, $5)
         ON CONFLICT (id) DO UPDATE SET alias = EXCLUDED.alias, updated_at = NOW()`,
        [identityHash, publicKeyEd25519, publicKeyX25519 || '', pinHash, alias || 'Eu']
      );
    } catch (e) {
      return res.status(500).json({ error: 'Erro ao gravar identidade no Supabase: ' + e.message });
    }
  } else {
    localStore.identities.set(identityHash, {
      id: identityHash,
      publicKeyEd25519,
      publicKeyX25519,
      pinHash,
      alias: alias || 'Eu'
    });
  }

  res.json({ success: true, identityHash, message: 'Identidade técnica registrada com sucesso.' });
});

/**
 * Marcação Unilateral e Conexão Mútua (Seções 10 e 11)
 * 10 metros = descoberta | Marcação mútua = vínculo à distância
 */
app.post('/api/connections/mark', async (req, res) => {
  const { fromIdentity, toIdentity } = req.body;

  if (!fromIdentity || !toIdentity) {
    return res.status(400).json({ error: 'Identidades de origem e destino obrigatórias.' });
  }

  // Verifica se o contato de destino já havia marcado a origem
  let isReciprocal = false;

  if (isPostgres()) {
    const existing = await query(
      `SELECT * FROM connections WHERE from_identity = $1 AND to_identity = $2`,
      [toIdentity, fromIdentity]
    );
    if (existing.rows.length > 0) {
      isReciprocal = true;
    }

    await query(
      `INSERT INTO connections (from_identity, to_identity, is_mutual)
       VALUES ($1, $2, $3)
       ON CONFLICT (from_identity, to_identity) DO NOTHING`,
      [fromIdentity, toIdentity, isReciprocal]
    );

    if (isReciprocal) {
      await query(
        `UPDATE connections SET is_mutual = TRUE
         WHERE (from_identity = $1 AND to_identity = $2) OR (from_identity = $2 AND to_identity = $1)`,
        [fromIdentity, toIdentity]
      );
    }
  } else {
    const reverseKey = `${toIdentity}->${fromIdentity}`;
    if (localStore.connections.has(reverseKey)) {
      isReciprocal = true;
    }
    localStore.connections.set(`${fromIdentity}->${toIdentity}`, { fromIdentity, toIdentity, isMutual: isReciprocal });
    if (isReciprocal) {
      localStore.connections.get(reverseKey).isMutual = true;
    }
  }

  res.json({
    success: true,
    isMutualConnection: isReciprocal,
    message: isReciprocal
      ? '✨ Conexão Mútua Estabelecida! Vínculo à distância liberado.'
      : 'Marcação registrada. Aguardando marcação recíproca.'
  });
});

/**
 * Emissão de Autorização de Recuperação por Familiar Autorizado (Seções 19 e 20)
 */
app.post('/api/family/recovery/issue', async (req, res) => {
  const { targetIdentity, familyIdentity } = req.body;

  if (!targetIdentity || !familyIdentity) {
    return res.status(400).json({ error: 'Identidade alvo e familiar obrigatórios.' });
  }

  const recoveryKey = `PA-REC-${generateNonce().slice(0, 8).toUpperCase()}`;
  const expiresAt = new Date(Date.now() + 15 * 60 * 1000); // 15 minutos

  if (isPostgres()) {
    await query(
      `INSERT INTO recovery_authorizations (id, target_identity, issued_by_family, recovery_key, expires_at)
       VALUES ($1, $2, $3, $4, $5)`,
      [generateNonce().slice(0, 16), targetIdentity, familyIdentity, recoveryKey, expiresAt]
    );
  } else {
    localStore.recoveryAuthorizations.set(recoveryKey, {
      targetIdentity,
      familyIdentity,
      recoveryKey,
      expiresAt: expiresAt.getTime(),
      failedAttempts: 0,
      lockedUntil: 0,
      isUsed: false
    });
  }

  res.json({
    success: true,
    recoveryKey,
    expiresAt,
    notice: 'Válido por 15 minutos • Uso único • O familiar NÃO deve saber o PIN do usuário.'
  });
});

/**
 * Validação de PIN e Transferência de Identidade no Novo Aparelho (“🔐 Entre na sua”)
 * Aplica estritamente a escada de bloqueios (Seção 23) e o isolamento de dispositivo (Seção 24).
 */
app.post('/api/family/recovery/claim', async (req, res) => {
  const { recoveryKey, pin, newDeviceFingerprint } = req.body;

  if (!recoveryKey || !pin) {
    return res.status(400).json({ error: 'Chave de recuperação e PIN obrigatórios.' });
  }

  let authRecord = null;

  if (isPostgres()) {
    const result = await query(
      `SELECT * FROM recovery_authorizations WHERE recovery_key = $1 AND is_used = FALSE`,
      [recoveryKey]
    );
    authRecord = result.rows[0];
  } else {
    authRecord = localStore.recoveryAuthorizations.get(recoveryKey);
  }

  if (!authRecord) {
    return res.status(404).json({ error: 'Chave de recuperação inválida, já utilizada ou inexistente.' });
  }

  const now = Date.now();
  const expiresAtMs = new Date(authRecord.expires_at || authRecord.expiresAt).getTime();

  if (now > expiresAtMs) {
    return res.status(410).json({ error: 'Esta autorização de recuperação expirou (limite de 15 minutos).' });
  }

  const lockedUntilMs = authRecord.locked_until ? new Date(authRecord.locked_until).getTime() : (authRecord.lockedUntil || 0);
  if (now < lockedUntilMs) {
    const remainingSec = Math.ceil((lockedUntilMs - now) / 1000);
    return res.status(429).json({
      error: `Aguarde o término do bloqueio de segurança: ${remainingSec}s restantes.`,
      remainingSeconds: remainingSec
    });
  }

  // Busca o hash do PIN da identidade alvo
  let expectedPinHash = '';
  if (isPostgres()) {
    const identRes = await query(`SELECT pin_salted_hash FROM identities WHERE id = $1`, [authRecord.target_identity]);
    expectedPinHash = identRes.rows[0]?.pin_salted_hash;
  } else {
    expectedPinHash = localStore.identities.get(authRecord.targetIdentity)?.pinHash;
  }

  const candidatePinHash = hashPin(pin);

  if (candidatePinHash !== expectedPinHash) {
    // Incrementa erros de tentativa
    const currentAttempts = (authRecord.failed_pin_attempts || authRecord.failedAttempts || 0) + 1;
    const lockoutSec = calculateLockoutDurationSeconds(currentAttempts);

    if (currentAttempts >= 10 || lockoutSec === -1) {
      if (isPostgres()) {
        await query(`UPDATE recovery_authorizations SET is_used = TRUE WHERE recovery_key = $1`, [recoveryKey]);
      } else {
        authRecord.isUsed = true;
      }
      return res.status(403).json({ error: '10ª tentativa incorreta: processo de recuperação encerrado definitivamente.' });
    }

    const newLockedUntil = lockoutSec > 0 ? new Date(now + lockoutSec * 1000) : null;

    if (isPostgres()) {
      await query(
        `UPDATE recovery_authorizations SET failed_pin_attempts = $1, locked_until = $2 WHERE recovery_key = $3`,
        [currentAttempts, newLockedUntil, recoveryKey]
      );
    } else {
      authRecord.failedAttempts = currentAttempts;
      authRecord.lockedUntil = newLockedUntil ? newLockedUntil.getTime() : 0;
    }

    return res.status(401).json({
      error: `PIN incorreto. Tentativa ${currentAttempts} de 10.`,
      lockoutSeconds: lockoutSec
    });
  }

  // ========================================================
  // SUCESSO! A IDENTIDADE É TRANSFERIDA PARA O NOVO APARELHO
  // REGRA 18: 1 IDENTIDADE = 1 DISPOSITIVO ATIVO
  // ========================================================
  const targetId = authRecord.target_identity || authRecord.targetIdentity;

  if (isPostgres()) {
    await query(`UPDATE recovery_authorizations SET is_used = TRUE WHERE recovery_key = $1`, [recoveryKey]);
    await query(`UPDATE devices SET is_active = FALSE, revoked_at = NOW() WHERE identity_id = $1`, [targetId]);
    await query(
      `INSERT INTO devices (id, identity_id, device_fingerprint, is_active)
       VALUES ($1, $2, $3, TRUE)`,
      [generateNonce().slice(0, 16), targetId, newDeviceFingerprint || 'device-fingerprint']
    );
  } else {
    authRecord.isUsed = true;
  }

  // Emite sinal imediato via WebSocket para revogar a sessão do aparelho antigo
  const oldDeviceSockets = activeSockets.get(targetId);
  if (oldDeviceSockets) {
    oldDeviceSockets.forEach((client) => {
      if (client.readyState === WebSocket.OPEN) {
        client.send(JSON.stringify({
          type: 'SESSION_REVOKED',
          message: '🔐 Sua identidade PessoasAqui foi transferida para um novo aparelho.'
        }));
      }
    });
  }

  res.json({
    success: true,
    targetIdentity: targetId,
    message: 'Identidade recuperada e transferida com sucesso. O dispositivo antigo foi revogado.'
  });
});

if (require.main === module) {
  server.listen(PORT, () => {
    console.log(`[PessoasAqui Backend] Servidor rodando na porta ${PORT}`);
    console.log(`[PessoasAqui Backend] Pronto para desenvolvimento local ou deploy no Render/Supabase.`);
  });
}

module.exports = { app, server, activeSockets, wss };
