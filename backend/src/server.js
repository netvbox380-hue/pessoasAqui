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
// Mapa de ofertas e divulgações locais ativas: offerId -> offerData
const activeOffers = new Map();
// Mapa de convites criptografados de uso único: inviteId -> inviteData
const activeInvites = new Map();
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

function cleanId(id) {
  return (id || '').replace(/^peer-/, '').trim();
}

function keyId(id) {
  return cleanId(id).toLowerCase();
}

async function getConnectionStatus(myId, peerId) {
  const from = cleanId(myId);
  const to = cleanId(peerId);
  if (!from || !to) return { isMarkedByMe: false, isMarkingMe: false, isMutual: false };

  let isMarkedByMe = false;
  let isMarkingMe = false;
  let isMutual = false;

  const kFrom = keyId(from);
  const kTo = keyId(to);

  if (isPostgres()) {
    try {
      const res = await query(
        `SELECT from_identity, to_identity, is_mutual FROM connections
         WHERE (LOWER(from_identity) = LOWER($1) AND LOWER(to_identity) = LOWER($2))
            OR (LOWER(from_identity) = LOWER($2) AND LOWER(to_identity) = LOWER($1))`,
        [from, to]
      );
      for (const row of res.rows) {
        const rowFrom = keyId(row.from_identity);
        const rowTo = keyId(row.to_identity);
        if (rowFrom === kFrom && rowTo === kTo) isMarkedByMe = true;
        if (rowFrom === kTo && rowTo === kFrom) isMarkingMe = true;
        if (row.is_mutual) isMutual = true;
      }
    } catch (e) {
      console.error('[DB] Erro ao consultar status de conexão:', e.message);
    }
  } else {
    const k1 = `${kFrom}->${kTo}`;
    const k2 = `${kTo}->${kFrom}`;
    if (localStore.connections.has(k1)) {
      isMarkedByMe = true;
      if (localStore.connections.get(k1).isMutual) isMutual = true;
    }
    if (localStore.connections.has(k2)) {
      isMarkingMe = true;
      if (localStore.connections.get(k2).isMutual) isMutual = true;
    }
  }

  if (isMarkedByMe && isMarkingMe) isMutual = true;
  return { isMarkedByMe, isMarkingMe, isMutual };
}

wss.on('connection', (ws, req) => {
  let authenticatedIdentity = null;

  ws.on('message', async (data) => {
    try {
      const msg = JSON.parse(data.toString());

      if (msg.type === 'AUTH') {
        authenticatedIdentity = cleanId(msg.identityHash);
        const incomingAlias = (msg.alias || 'Usuário PessoasAqui').trim();
        const kAuth = keyId(authenticatedIdentity);

        // Regra 18: 1 Identidade = 1 Dispositivo Ativo. Fecha socket anterior se houver
        if (activeSockets.has(kAuth)) {
          const oldWs = activeSockets.get(kAuth);
          if (oldWs && oldWs !== ws) {
            try { oldWs.close(1000, "Replaced by active device connection"); } catch (_) {}
          }
        }
        activeSockets.set(kAuth, ws);

        // Remove qualquer presença antiga/duplicada que compartilhe o mesmo apelido
        for (const [idKey, peer] of activePresence.entries()) {
          if (idKey !== kAuth && peer.alias && incomingAlias && peer.alias.toLowerCase() === incomingAlias.toLowerCase()) {
            console.log(`[WS] Removendo presença fantasma para o apelido "${incomingAlias}" (id antigo: ${peer.identityHash})`);
            activePresence.delete(idKey);
            wss.clients.forEach((client) => {
              if (client !== ws && client.readyState === WebSocket.OPEN) {
                client.send(JSON.stringify({ type: 'PEER_OFFLINE', identityHash: peer.identityHash }));
              }
            });
          }
        }

        activePresence.set(kAuth, {
          identityHash: authenticatedIdentity,
          alias: incomingAlias,
          intent: msg.intent || 'QUERO_CONVERSAR',
          lastSeen: Date.now()
        });

        ws.send(JSON.stringify({ type: 'AUTH_SUCCESS', identityHash: authenticatedIdentity }));
        console.log(`[WS] Dispositivo conectado para identidade: ${authenticatedIdentity} (${incomingAlias})`);

        // Entrega mensagens pendentes para este dispositivo
        if (pendingMessages.has(kAuth)) {
          const pending = pendingMessages.get(kAuth) || [];
          pending.forEach((pm) => {
            ws.send(JSON.stringify(pm));
          });
          pendingMessages.delete(kAuth);
        }

        // Envia lista atual de usuários presentes para o novo cliente (excluindo a si mesmo por id e por apelido)
        const now = Date.now();
        const existingPeers = Array.from(activePresence.values())
          .filter(p => keyId(p.identityHash) !== kAuth &&
                       (!p.alias || !incomingAlias || p.alias.toLowerCase() !== incomingAlias.toLowerCase()) &&
                       (now - p.lastSeen < 120000));

        const enrichedPeers = await Promise.all(existingPeers.map(async (p) => {
          const status = await getConnectionStatus(authenticatedIdentity, p.identityHash);
          return {
            ...p,
            isMutual: status.isMutual,
            isMarkedByMe: status.isMarkedByMe,
            isMarkingMe: status.isMarkingMe
          };
        }));

        ws.send(JSON.stringify({ type: 'PRESENCE_SYNC', peers: enrichedPeers }));

        // Transmite anúncio de presença para os demais aparelhos conectados
        const peerData = activePresence.get(kAuth);
        wss.clients.forEach((client) => {
          if (client !== ws && client.readyState === WebSocket.OPEN) {
            client.send(JSON.stringify({ type: 'PEER_ONLINE', peer: peerData }));
          }
        });
      }

      // Heartbeat periódico pelo WebSocket
      if (msg.type === 'HEARTBEAT' && authenticatedIdentity) {
        const kAuth = keyId(authenticatedIdentity);
        if (activePresence.has(kAuth)) {
          const current = activePresence.get(kAuth);
          current.lastSeen = Date.now();
          if (msg.alias) current.alias = msg.alias;
          if (msg.intent) current.intent = msg.intent;
        }
      }

      // Relay Cego de Mensagem Cifrada Ponta a Ponta (E2EE)
      if (msg.type === 'E2EE_MESSAGE' && authenticatedIdentity) {
        const { recipientHash, ciphertextPayload, ivNonce, messageId } = msg;
        const cleanRecipient = cleanId(recipientHash);
        const kRecip = keyId(cleanRecipient);
        const kAuth = keyId(authenticatedIdentity);
        const msgId = messageId || `${authenticatedIdentity}_${Date.now()}_${Math.random()}`;

        if (isDuplicateMessage(msgId)) {
          return;
        }

        const senderAlias = activePresence.get(kAuth)?.alias || 'Usuário';

        const msgObj = {
          type: 'E2EE_MESSAGE_RECEIVED',
          messageId: msgId,
          senderHash: authenticatedIdentity,
          senderAlias: senderAlias,
          ciphertextPayload,
          ivNonce: ivNonce || '',
          timestamp: Date.now()
        };

        const targetWs = activeSockets.get(kRecip);
        if (targetWs && targetWs.readyState === WebSocket.OPEN) {
          targetWs.send(JSON.stringify(msgObj));
        } else {
          if (!pendingMessages.has(kRecip)) {
            pendingMessages.set(kRecip, []);
          }
          pendingMessages.get(kRecip).push(msgObj);
        }
      }
    } catch (e) {
      console.error('[WS] Erro no processamento de mensagem:', e.message);
    }
  });

  ws.on('close', () => {
    if (authenticatedIdentity) {
      const kAuth = keyId(authenticatedIdentity);
      if (activeSockets.get(kAuth) === ws) {
        activeSockets.delete(kAuth);
        activePresence.delete(kAuth);

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

  const cleanIdentity = cleanId(identityHash);
  const cleanAlias = (alias || 'Usuário PessoasAqui').trim();

  // Remove qualquer presença fantasma com o mesmo apelido
  for (const [id, peer] of activePresence.entries()) {
    if (id !== cleanIdentity && peer.alias && cleanAlias && peer.alias.toLowerCase() === cleanAlias.toLowerCase()) {
      activePresence.delete(id);
    }
  }

  activePresence.set(cleanIdentity, {
    identityHash: cleanIdentity,
    alias: cleanAlias,
    intent: intent || 'QUERO_CONVERSAR',
    lastSeen: Date.now()
  });

  res.json({ success: true, activePresenceCount: activePresence.size });
});

/**
 * Listagem de Pessoas Online no Radar de Proximidade
 */
app.get('/api/presence/nearby', async (req, res) => {
  const myHash = cleanId(req.query.myIdentity);
  const myAlias = (req.query.myAlias || '').trim().toLowerCase();
  const now = Date.now();
  const peers = Array.from(activePresence.values())
    .filter(p => cleanId(p.identityHash) !== myHash &&
                 (!myAlias || !p.alias || p.alias.trim().toLowerCase() !== myAlias) &&
                 (now - p.lastSeen < 120000));

  if (myHash) {
    const enriched = await Promise.all(peers.map(async (p) => {
      const status = await getConnectionStatus(myHash, p.identityHash);
      return {
        ...p,
        isMutual: status.isMutual,
        isMarkedByMe: status.isMarkedByMe,
        isMarkingMe: status.isMarkingMe
      };
    }));
    return res.json({ success: true, count: enriched.length, people: enriched });
  }

  res.json({ success: true, count: peers.length, people: peers });
});

/**
 * Serviços e Divulgações Locais
 */
app.post('/api/offers/publish', (req, res) => {
  const { id, authorId, authorAlias, profession, description, imageBase64, externalLink } = req.body;
  if (!authorId || !profession || !description) {
    return res.status(400).json({ error: 'authorId, profession e description são obrigatórios.' });
  }

  const offerId = id || `offer_${Date.now()}`;
  const offer = {
    id: offerId,
    authorId: cleanId(authorId),
    authorAlias: authorAlias || 'Profissional',
    profession: profession.trim(),
    description: description.trim(),
    imageBase64: imageBase64 || null,
    externalLink: externalLink || null,
    createdAt: Date.now()
  };

  activeOffers.set(offerId, offer);

  // Broadcast em tempo real para os dispositivos conectados
  const payload = JSON.stringify({
    type: 'OFFER_PUBLISHED',
    offer
  });
  for (const client of activeSockets.values()) {
    if (client.readyState === WebSocket.OPEN) {
      client.send(payload);
    }
  }

  res.json({ success: true, offer });
});

app.get('/api/offers/nearby', (req, res) => {
  const now = Date.now();
  // Retorna ofertas ativas (últimas 48 horas)
  const offers = Array.from(activeOffers.values())
    .filter(o => (now - o.createdAt) < 172800000);
  res.json({ success: true, count: offers.length, offers });
});

app.delete('/api/offers/:id', (req, res) => {
  const { id } = req.params;
  activeOffers.delete(id);

  const payload = JSON.stringify({
    type: 'OFFER_DELETED',
    offerId: id
  });
  for (const client of activeSockets.values()) {
    if (client.readyState === WebSocket.OPEN) {
      client.send(payload);
    }
  }

  res.json({ success: true, deleted: id });
});

/**
 * Envio de Mensagem Cifrada via HTTP (Fallback redundante para WebSockets)
 */
app.post('/api/messages/send', (req, res) => {
  const { senderHash, recipientHash, ciphertextPayload, ivNonce, senderAlias, messageId } = req.body;
  if (!recipientHash || !ciphertextPayload) {
    return res.status(400).json({ error: 'recipientHash e ciphertextPayload são obrigatórios.' });
  }

  const cleanRecipient = cleanId(recipientHash);
  const cleanSender = cleanId(senderHash);
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
  const cleanKey = cleanId(rawId);
  const pending = pendingMessages.get(cleanKey) || [];
  pendingMessages.delete(cleanKey);
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

  const cleanIdentity = cleanId(identityHash);
  const pinHash = hashPin(pin);

  if (isPostgres()) {
    try {
      await query(
        `INSERT INTO identities (id, public_key_ed25519, public_key_x25519, pin_salted_hash, alias)
         VALUES ($1, $2, $3, $4, $5)
         ON CONFLICT (id) DO UPDATE SET alias = EXCLUDED.alias, updated_at = NOW()`,
        [cleanIdentity, publicKeyEd25519, publicKeyX25519 || '', pinHash, alias || 'Eu']
      );
    } catch (e) {
      return res.status(500).json({ error: 'Erro ao gravar identidade no Supabase: ' + e.message });
    }
  } else {
    localStore.identities.set(cleanIdentity, {
      id: cleanIdentity,
      publicKeyEd25519,
      publicKeyX25519,
      pinHash,
      alias: alias || 'Eu'
    });
  }

  res.json({ success: true, identityHash: cleanIdentity, message: 'Identidade técnica registrada com sucesso.' });
});

/**
 * Consulta de Status de Conexões (Mútuas e Marcações)
 */
app.get('/api/connections/status', async (req, res) => {
  const myId = cleanId(req.query.myIdentity);
  if (!myId) {
    return res.status(400).json({ error: 'myIdentity é obrigatório.' });
  }

  let markedByMe = [];
  let markingMe = [];
  let mutual = [];

  if (isPostgres()) {
    try {
      const result = await query(
        `SELECT from_identity, to_identity, is_mutual FROM connections
         WHERE from_identity = $1 OR to_identity = $1`,
        [myId]
      );
      for (const row of result.rows) {
        const f = cleanId(row.from_identity);
        const t = cleanId(row.to_identity);
        if (f === myId && !markedByMe.includes(t)) markedByMe.push(t);
        if (t === myId && !markingMe.includes(f)) markingMe.push(f);
        if (row.is_mutual) {
          const partner = f === myId ? t : f;
          if (!mutual.includes(partner)) mutual.push(partner);
        }
      }
    } catch (e) {
      console.error('[DB] Erro ao buscar connections:', e.message);
    }
  } else {
    for (const [key, val] of localStore.connections.entries()) {
      const f = cleanId(val.fromIdentity);
      const t = cleanId(val.toIdentity);
      if (f === myId && !markedByMe.includes(t)) markedByMe.push(t);
      if (t === myId && !markingMe.includes(f)) markingMe.push(f);
      if (val.isMutual) {
        const partner = f === myId ? t : f;
        if (!mutual.includes(partner)) mutual.push(partner);
      }
    }
  }

  res.json({ success: true, markedByMe, markingMe, mutual });
});

/**
 * Marcação Unilateral e Conexão Mútua (Seções 10 e 11)
 * 10 metros = descoberta | Marcação mútua = vínculo à distância
 */
app.post('/api/connections/mark', async (req, res) => {
  const { fromIdentity, toIdentity, senderAlias } = req.body;
  const from = cleanId(fromIdentity);
  const to = cleanId(toIdentity);

  if (!from || !to) {
    return res.status(400).json({ error: 'Identidades de origem e destino obrigatórias.' });
  }

  // Verifica se o contato de destino já havia marcado a origem
  let isReciprocal = false;

  const kFrom = keyId(from);
  const kTo = keyId(to);

  if (isPostgres()) {
    const existing = await query(
      `SELECT * FROM connections WHERE (LOWER(from_identity) = LOWER($1) AND LOWER(to_identity) = LOWER($2))`,
      [to, from]
    );
    if (existing.rows.length > 0) {
      isReciprocal = true;
    }

    await query(
      `INSERT INTO connections (from_identity, to_identity, is_mutual)
       VALUES ($1, $2, $3)
       ON CONFLICT (from_identity, to_identity) DO UPDATE SET is_mutual = EXCLUDED.is_mutual`,
      [from, to, isReciprocal]
    );

    if (isReciprocal) {
      await query(
        `UPDATE connections SET is_mutual = TRUE
         WHERE (LOWER(from_identity) = LOWER($1) AND LOWER(to_identity) = LOWER($2))
            OR (LOWER(from_identity) = LOWER($2) AND LOWER(to_identity) = LOWER($1))`,
        [from, to]
      );
    }
  } else {
    const reverseKey = `${kTo}->${kFrom}`;
    if (localStore.connections.has(reverseKey)) {
      isReciprocal = true;
    }
    localStore.connections.set(`${kFrom}->${kTo}`, { fromIdentity: from, toIdentity: to, isMutual: isReciprocal });
    if (isReciprocal && localStore.connections.has(reverseKey)) {
      localStore.connections.get(reverseKey).isMutual = true;
    }
  }

  const myAlias = senderAlias || activePresence.get(kFrom)?.alias || 'Alguém';

  // Notificações realtime via WebSocket e fila de mensagens pendentes:
  if (isReciprocal) {
    const targetWs = activeSockets.get(kTo);
    const msgForTo = {
      type: 'MUTUAL_CONNECTION_ESTABLISHED',
      partnerIdentity: from,
      partnerAlias: myAlias,
      timestamp: Date.now()
    };
    if (targetWs && targetWs.readyState === WebSocket.OPEN) {
      targetWs.send(JSON.stringify(msgForTo));
    } else {
      if (!pendingMessages.has(kTo)) pendingMessages.set(kTo, []);
      pendingMessages.get(kTo).push({
        type: 'E2EE_MESSAGE_RECEIVED',
        senderHash: from,
        senderAlias: myAlias,
        ciphertextPayload: `[MARK_MUTUAL:${myAlias}]`,
        ivNonce: '',
        messageId: `mut_${Date.now()}`
      });
    }

    const callerWs = activeSockets.get(kFrom);
    if (callerWs && callerWs.readyState === WebSocket.OPEN) {
      callerWs.send(JSON.stringify({
        type: 'MUTUAL_CONNECTION_ESTABLISHED',
        partnerIdentity: to,
        partnerAlias: activePresence.get(kTo)?.alias || 'Conexão',
        timestamp: Date.now()
      }));
    }
  } else {
    const targetWs = activeSockets.get(kTo);
    const msgForTo = {
      type: 'PEER_MARKED_YOU',
      partnerIdentity: from,
      partnerAlias: myAlias,
      timestamp: Date.now()
    };
    if (targetWs && targetWs.readyState === WebSocket.OPEN) {
      targetWs.send(JSON.stringify(msgForTo));
    } else {
      if (!pendingMessages.has(kTo)) pendingMessages.set(kTo, []);
      pendingMessages.get(kTo).push({
        type: 'E2EE_MESSAGE_RECEIVED',
        senderHash: from,
        senderAlias: myAlias,
        ciphertextPayload: `[MARK_ON:${myAlias}]`,
        ivNonce: '',
        messageId: `mark_${Date.now()}`
      });
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
 * Geração de Convite Criptografado de Uso Único para Conexão Mútua à Distância
 */
app.post('/api/invites/create', async (req, res) => {
  const { senderIdentity, senderAlias } = req.body;
  const sender = cleanId(senderIdentity);
  if (!sender) {
    return res.status(400).json({ error: 'Identidade do remetente obrigatória.' });
  }

  const inviteId = `inv_${generateNonce().slice(0, 12).toLowerCase()}`;
  const token = generateNonce().slice(0, 32);
  const alias = (senderAlias || 'Usuário').trim();
  const createdAt = Date.now();
  const expiresAt = createdAt + (7 * 24 * 60 * 60 * 1000); // 7 dias de validade

  const inviteData = {
    inviteId,
    token,
    senderIdentity: sender,
    senderAlias: alias,
    isUsed: false,
    usedBy: null,
    usedAt: null,
    createdAt,
    expiresAt
  };

  activeInvites.set(inviteId, inviteData);

  const baseUrl = process.env.BASE_URL || 'https://pessoasaqui.onrender.com';
  const inviteUrl = `${baseUrl}/invite?id=${encodeURIComponent(inviteId)}&token=${encodeURIComponent(token)}&sender=${encodeURIComponent(sender)}&alias=${encodeURIComponent(alias)}`;

  console.log(`[Convite] Novo convite único gerado: ${inviteId} por ${alias} (${sender})`);

  res.json({
    success: true,
    inviteId,
    token,
    inviteUrl,
    senderIdentity: sender,
    senderAlias: alias,
    expiresAt
  });
});

/**
 * Resgate de Convite de Uso Único (Efetiva Conexão Mútua Instantânea à Distância)
 */
app.post('/api/invites/redeem', async (req, res) => {
  const { inviteId, token, receiverIdentity, receiverAlias } = req.body;
  const receiver = cleanId(receiverIdentity);
  const recAlias = (receiverAlias || 'Usuário Convidado').trim();

  if (!inviteId || !token || !receiver) {
    return res.status(400).json({ error: 'Parâmetros de convite ou destinatário inválidos.' });
  }

  const invite = activeInvites.get(inviteId);
  if (!invite) {
    return res.status(404).json({ error: 'Convite não encontrado ou inexistente.' });
  }

  if (invite.token !== token) {
    return res.status(403).json({ error: 'Token de convite inválido ou adulterado.' });
  }

  if (invite.isUsed) {
    return res.status(410).json({ error: 'Este convite já foi utilizado e não pode ser reutilizado.' });
  }

  if (Date.now() > invite.expiresAt) {
    return res.status(410).json({ error: 'Este convite expirou.' });
  }

  const sender = cleanId(invite.senderIdentity);
  if (keyId(sender) === keyId(receiver)) {
    return res.status(400).json({ error: 'Você não pode resgatar seu próprio convite.' });
  }

  // Marca convite como utilizado (USO ÚNICO GARANTIDO)
  invite.isUsed = true;
  invite.usedBy = receiver;
  invite.usedAt = Date.now();

  const kSender = keyId(sender);
  const kReceiver = keyId(receiver);

  // Estabelece Conexão Mútua Bidirecional Permanente
  if (isPostgres()) {
    try {
      await query(
        `INSERT INTO connections (from_identity, to_identity, is_mutual)
         VALUES ($1, $2, TRUE), ($2, $1, TRUE)
         ON CONFLICT (from_identity, to_identity) DO UPDATE SET is_mutual = TRUE`,
        [sender, receiver]
      );
    } catch (e) {
      console.error('[DB] Erro ao gravar conexão mútua por convite:', e.message);
    }
  } else {
    localStore.connections.set(`${kSender}->${kReceiver}`, { fromIdentity: sender, toIdentity: receiver, isMutual: true });
    localStore.connections.set(`${kReceiver}->${kSender}`, { fromIdentity: receiver, toIdentity: sender, isMutual: true });
  }

  console.log(`[Convite] Convite ${inviteId} resgatado com sucesso por ${recAlias} (${receiver}) com ${invite.senderAlias} (${sender})!`);

  // Notifica ambos via WebSocket
  const senderWs = activeSockets.get(kSender);
  const senderMsg = {
    type: 'MUTUAL_CONNECTION_ESTABLISHED',
    partnerIdentity: receiver,
    partnerAlias: recAlias,
    timestamp: Date.now(),
    viaInvite: true
  };
  if (senderWs && senderWs.readyState === WebSocket.OPEN) {
    senderWs.send(JSON.stringify(senderMsg));
  } else {
    if (!pendingMessages.has(kSender)) pendingMessages.set(kSender, []);
    pendingMessages.get(kSender).push({
      type: 'E2EE_MESSAGE_RECEIVED',
      senderHash: receiver,
      senderAlias: recAlias,
      ciphertextPayload: `[MARK_MUTUAL:${recAlias}]`,
      ivNonce: '',
      messageId: `mut_inv_${Date.now()}`
    });
  }

  const receiverWs = activeSockets.get(kReceiver);
  if (receiverWs && receiverWs.readyState === WebSocket.OPEN) {
    receiverWs.send(JSON.stringify({
      type: 'MUTUAL_CONNECTION_ESTABLISHED',
      partnerIdentity: sender,
      partnerAlias: invite.senderAlias,
      timestamp: Date.now(),
      viaInvite: true
    }));
  }

  res.json({
    success: true,
    senderIdentity: sender,
    senderAlias: invite.senderAlias,
    receiverIdentity: receiver,
    receiverAlias: recAlias,
    message: `✨ Conexão Mútua Estabelecida com ${invite.senderAlias}! Vínculo à distância liberado com sucesso.`
  });
});

/**
 * Landing Page Web do Convite (com suporte a Deep Link automático para o app PessoasAqui)
 */
app.get('/invite', (req, res) => {
  const { id, token, sender, alias } = req.query;
  const safeAlias = (alias || 'Alguém').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  const queryParams = `id=${encodeURIComponent(id || '')}&token=${encodeURIComponent(token || '')}&sender=${encodeURIComponent(sender || '')}&alias=${encodeURIComponent(alias || '')}`;
  const appDeepLink = `pessoasaqui://invite?${queryParams}`;
  const chromeIntentLink = `intent://invite?${queryParams}#Intent;scheme=pessoasaqui;package=br.com.pessoasaqui;end`;

  res.send(`<!DOCTYPE html>
<html lang="pt-BR">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Convite de Conexão Segura - PessoasAqui</title>
  <style>
    body {
      background-color: #0A0E14;
      color: #F0F6FC;
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      min-height: 100vh;
      margin: 0;
      padding: 20px;
      box-sizing: border-box;
      text-align: center;
    }
    .card {
      background-color: #131920;
      border: 1px solid #1C2430;
      border-radius: 20px;
      padding: 32px 24px;
      max-width: 420px;
      width: 100%;
      box-shadow: 0 8px 32px rgba(0,0,0,0.5);
    }
    .badge {
      display: inline-block;
      background: rgba(0, 229, 255, 0.15);
      color: #00E5FF;
      border-radius: 20px;
      padding: 6px 14px;
      font-size: 13px;
      font-weight: bold;
      margin-bottom: 16px;
    }
    h1 {
      font-size: 22px;
      margin: 0 0 12px;
      color: #F0F6FC;
    }
    p {
      color: #8B949E;
      font-size: 15px;
      line-height: 1.5;
      margin: 0 0 24px;
    }
    .sender-name {
      color: #00E676;
      font-weight: bold;
    }
    .btn {
      display: block;
      background-color: #00E5FF;
      color: #0A0E14;
      text-decoration: none;
      font-weight: bold;
      font-size: 16px;
      padding: 14px 20px;
      border-radius: 12px;
      margin-bottom: 12px;
      transition: opacity 0.2s;
    }
    .btn:hover {
      opacity: 0.9;
    }
    .btn-secondary {
      background-color: #1C2430;
      color: #8B949E;
      font-size: 14px;
    }
    .security-note {
      font-size: 12px;
      color: #8B949E;
      margin-top: 16px;
    }
  </style>
  <script>
    // Tenta abrir o aplicativo automaticamente via Chrome Intent no Android ou Deep Link
    window.onload = function() {
      if (/Android/i.test(navigator.userAgent)) {
        window.location.href = "${chromeIntentLink}";
      } else {
        window.location.href = "${appDeepLink}";
      }
    };
  </script>
</head>
<body>
  <div class="card">
    <div class="badge">🔒 Convite Criptografado de Uso Único</div>
    <h1>Conectar no PessoasAqui</h1>
    <p><span class="sender-name">${safeAlias}</span> convidou você para uma conexão mútua segura à distância com mensagens, chamadas e fotos criptografadas de ponta a ponta.</p>
    <a href="${chromeIntentLink}" class="btn">★ Abrir no Aplicativo PessoasAqui</a>
    <a href="${appDeepLink}" class="btn btn-secondary">Abrir via link direto</a>
    <div class="security-note">Se o aplicativo não abrir automaticamente, toque no botão acima.</div>
  </div>
</body>
</html>`);
});

/**
 * Android Digital Asset Links (para suporte a App Links nativos sem aviso no navegador)
 */
app.get('/.well-known/assetlinks.json', (req, res) => {
  res.json([{
    relation: ["delegate_permission/common.handle_all_urls"],
    target: {
      namespace: "android_app",
      package_name: "br.com.pessoasaqui",
      sha256_cert_fingerprints: [
        "14:A4:44:03:77:F1:C9:83:81:4A:27:0B:4D:B5:1A:87:69:B6:59:75:A8:DF:6B:47:19:D4:57:3E:68:5B:3F:58"
      ]
    }
  }]);
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
  const oldWs = activeSockets.get(cleanId(targetId));
  if (oldWs && oldWs.readyState === WebSocket.OPEN) {
    try {
      oldWs.send(JSON.stringify({
        type: 'SESSION_REVOKED',
        message: '🔐 Sua identidade PessoasAqui foi transferida para um novo aparelho.'
      }));
    } catch (_) {}
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
