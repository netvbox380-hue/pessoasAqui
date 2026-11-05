require('dotenv').config();
process.on('uncaughtException', (err) => {
  console.error('[SERVER RESILIENCE] Uncaught Exception interceptada:', err);
});
process.on('unhandledRejection', (reason, promise) => {
  console.error('[SERVER RESILIENCE] Unhandled Rejection interceptada:', reason);
});
const path = require('path');
const express = require('express');
const http = require('http');
const WebSocket = require('ws');
const cors = require('cors');
const crypto = require('crypto');
const { initDatabase, localStore, isPostgres, query, withTransaction } = require('./db');
const { hashPin, verifyPinHash, generateNonce, calculateLockoutDurationSeconds } = require('./crypto');
const {
  INVITE_LIFETIME_MS, tokenDigest, verifyStoredToken, validateInviteId, validateInviteToken
} = require('./inviteSecurity');
const { verifyInviteIdentityProof, deriveInviteId } = require('./inviteAuth');
const { verifyWsIdentity } = require('./identityAuth');
const { registerFamilyRecoveryRoutes } = require('./familyRecoveryRoutes');
const { registerOfferRoutesV2 } = require('./offerRoutesV2');

function verifyClientEcdsaSignature(publicKeyBase64, dataString, signatureBase64) {
  if (!publicKeyBase64 || !signatureBase64) return false;
  try {
    const pubKeyDer = Buffer.from(publicKeyBase64, 'base64');
    const pubKeyObject = crypto.createPublicKey({
      key: pubKeyDer,
      format: 'der',
      type: 'spki'
    });
    const verifier = crypto.createVerify('SHA256');
    verifier.update(dataString);
    return verifier.verify(pubKeyObject, Buffer.from(signatureBase64, 'base64'));
  } catch (err) {
    return false;
  }
}

const registeredDevicePublicKeys = new Map(); // identityKey -> publicKeyBase64

// Sem chave secreta provisionada, não emitir assinatura HMAC. O token é
// autorizado pelo registro persistente do banco, nunca por um fallback stateless.
const INVITE_SECRET = process.env.INVITE_SECRET || process.env.SESSION_SECRET || null;

function signInvitePayload(inviteId, token, senderIdentity, expiresAt) {
  const cleanInvId = (inviteId || '').replace(/^peer-/, '').trim().toLowerCase();
  const cleanTok = (token || '').trim();
  const cleanSender = (senderIdentity || '').replace(/^peer-/, '').trim().toLowerCase();
  const exp = String(expiresAt || '');
  if (!INVITE_SECRET) return '';
  return crypto.createHmac('sha256', INVITE_SECRET)
    .update(`${cleanInvId}:${cleanTok}:${cleanSender}:${exp}`)
    .digest('hex');
}

function verifyInvitePayloadSignature(inviteId, token, senderIdentity, expiresAt, signature) {
  if (!signature) return false;
  try {
    const expected = signInvitePayload(inviteId, token, senderIdentity, expiresAt);
    const sigBuf = Buffer.from(signature, 'hex');
    const expBuf = Buffer.from(expected, 'hex');
    if (sigBuf.length !== expBuf.length) return false;
    return crypto.timingSafeEqual(sigBuf, expBuf);
  } catch (_) {
    return false;
  }
}

const app = express();
const server = http.createServer(app);
const wss = new WebSocket.Server({ server });

const PORT = process.env.PORT || 3000;

app.use(cors());
app.use(express.json({ limit: '25mb' }));
app.use(express.urlencoded({ extended: true, limit: '25mb' }));

// Inicia conexão com banco (Supabase PostgreSQL ou local)
initDatabase();

// Servir o Painel Administrativo Web Integrado
app.get(['/admin', '/admin/'], (req, res) => res.sendFile(path.join(__dirname, '../public/admin/index.html')));
app.use('/admin', express.static(path.join(__dirname, '../public/admin')));

// Servir o Portal Jurídico, Termos de Uso e Política de Privacidade (Conformidade Google Play & LGPD)
app.get(['/legal', '/legal/'], (req, res) => res.sendFile(path.join(__dirname, '../public/legal/index.html')));
app.get(['/child-safety', '/child-safety.html', '/seguranca-infantil', '/csae', '/child-safety-standards'], (req, res) => res.sendFile(path.join(__dirname, '../public/legal/child-safety.html')));
app.get(['/privacy', '/politica-de-privacidade'], (req, res) => res.redirect('/legal#privacidade'));
app.get(['/terms', '/termos-de-uso'], (req, res) => res.redirect('/legal#termos'));
app.get(['/law-enforcement', '/autoridades'], (req, res) => res.redirect('/legal#autoridades'));
app.get(['/delete-data', '/exclusao-dados', '/data-deletion', '/delete'], (req, res) => res.redirect('/legal#exclusao'));
app.use('/legal', express.static(path.join(__dirname, '../public/legal')));

// Garante tabelas persistentes se estiver conectado ao PostgreSQL
setTimeout(async () => {
  if (isPostgres()) {
    try {
      // Esquema aditivo v2: não altera registros antigos, exige persistência.
      await query(`CREATE TABLE IF NOT EXISTS identity_key_bindings (
        identity_id VARCHAR(64) PRIMARY KEY REFERENCES identities(id) ON DELETE CASCADE,
        public_key_spki TEXT NOT NULL,
        generation BIGINT NOT NULL DEFAULT 1,
        updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
      )`);
      await query(`CREATE TABLE IF NOT EXISTS recovery_delegations (
        target_identity VARCHAR(64) NOT NULL REFERENCES identities(id) ON DELETE CASCADE,
        family_identity VARCHAR(64) NOT NULL REFERENCES identities(id) ON DELETE CASCADE,
        family_public_key TEXT NOT NULL,
        is_active BOOLEAN NOT NULL DEFAULT TRUE,
        updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
        PRIMARY KEY(target_identity,family_identity)
      )`);
      await query(`CREATE TABLE IF NOT EXISTS recovery_proof_nonces (
        identity_id VARCHAR(64) NOT NULL,
        operation VARCHAR(32) NOT NULL,
        nonce VARCHAR(64) NOT NULL,
        created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
        PRIMARY KEY(identity_id,operation,nonce)
      )`);
      await query(`CREATE TABLE IF NOT EXISTS recovery_grants (
        id VARCHAR(64) PRIMARY KEY,
        token_hash CHAR(64) NOT NULL UNIQUE,
        target_identity VARCHAR(64) NOT NULL REFERENCES identities(id) ON DELETE CASCADE,
        family_identity VARCHAR(64) NOT NULL REFERENCES identities(id) ON DELETE CASCADE,
        family_public_key TEXT NOT NULL,
        expected_generation BIGINT NOT NULL,
        expires_at TIMESTAMPTZ NOT NULL,
        used_at TIMESTAMPTZ,
        created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
      )`);
      await query(`CREATE INDEX IF NOT EXISTS idx_recovery_grants_target
        ON recovery_grants(target_identity,expires_at)`);
      await query(`CREATE TABLE IF NOT EXISTS offers_secure (
        id VARCHAR(64) PRIMARY KEY,
        author_id VARCHAR(64) NOT NULL REFERENCES identities(id) ON DELETE CASCADE,
        author_alias VARCHAR(64) NOT NULL,
        profession VARCHAR(64) NOT NULL,
        description VARCHAR(500) NOT NULL,
        image_base64 TEXT,
        external_link TEXT,
        created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
        updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
      )`);
      await query(`CREATE INDEX IF NOT EXISTS idx_offers_secure_owner ON offers_secure(author_id,created_at)`);
      await query(`CREATE TABLE IF NOT EXISTS offer_action_nonces (
        author_id VARCHAR(64) NOT NULL,
        nonce VARCHAR(64) NOT NULL,
        created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
        PRIMARY KEY(author_id,nonce)
      )`);
      await query(`CREATE TABLE IF NOT EXISTS invites (
        id VARCHAR(64) PRIMARY KEY,
        token VARCHAR(64) NOT NULL,
        sender_identity VARCHAR(64),
        sender_alias VARCHAR(64),
        signature TEXT,
        expires_at TIMESTAMPTZ NOT NULL,
        is_used BOOLEAN DEFAULT FALSE,
        used_by VARCHAR(64),
        used_at TIMESTAMPTZ,
        created_at TIMESTAMPTZ DEFAULT NOW()
      )`);

      await query(`CREATE TABLE IF NOT EXISTS moderation_reports (
        id VARCHAR(64) PRIMARY KEY,
        reporter_identity VARCHAR(64),
        accused_identity VARCHAR(64),
        violation_type VARCHAR(64) DEFAULT 'ABUSE',
        reason TEXT NOT NULL,
        evidence_payload TEXT,
        status VARCHAR(32) DEFAULT 'PENDING',
        moderator_notes TEXT,
        created_at TIMESTAMPTZ DEFAULT NOW(),
        resolved_at TIMESTAMPTZ
      )`);

      await query(`CREATE TABLE IF NOT EXISTS support_tickets (
        id VARCHAR(64) PRIMARY KEY,
        author_identity VARCHAR(64) NOT NULL,
        author_alias VARCHAR(64),
        subject VARCHAR(128) NOT NULL,
        category VARCHAR(64) DEFAULT 'GERAL',
        message TEXT NOT NULL,
        status VARCHAR(32) DEFAULT 'OPEN',
        moderator_reply TEXT,
        replied_at TIMESTAMPTZ,
        created_at TIMESTAMPTZ DEFAULT NOW()
      )`);

      const bannedRes = await query(`SELECT id FROM identities WHERE is_banned = TRUE`);
      for (const row of bannedRes.rows) {
        bannedIdentities.add(String(row.id).toLowerCase());
      }
      console.log(`[DB] Tabelas de moderação, tickets e convites verificadas (${bannedIdentities.size} banimentos ativos).`);
    } catch (err) {
      console.warn('[DB] Nota ao verificar tabelas de moderação:', err.message);
    }
  }
}, 500);

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
// Controle anti-flood / taxa de envio de mensagens por cliente (max 25 msg/s para mensagens discretas)
const clientMessageRate = new Map();

function checkRateLimit(identity, isStream) {
  if (isStream || !identity) return true;
  const now = Date.now();
  const rate = clientMessageRate.get(identity) || { count: 0, windowStart: now };
  if (now - rate.windowStart > 1000) {
    rate.count = 1;
    rate.windowStart = now;
    clientMessageRate.set(identity, rate);
    return true;
  }
  rate.count++;
  if (rate.count > 25) {
    if (rate.count === 26) {
      console.warn(`[ANTI-FLOOD] Taxa de mensagens excedida para ${identity} (${rate.count}/seg). Descartando mensagens excedentes.`);
    }
    return false;
  }
  return true;
}
// Conjunto de identidades banidas da rede (rejeição em tempo real)
const bannedIdentities = new Set();

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

  // Sempre verifica o cache local em memória primeiro (garante resiliência instantânea)
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
  }

  if (isMarkedByMe && isMarkingMe) isMutual = true;
  return { isMarkedByMe, isMarkingMe, isMutual };
}

function unwrapE2eeIfCompatible(payload, ivNonce, senderHash, recipientHash) {
  if (!payload || typeof payload !== 'string' || !payload.startsWith('ENC:') || !ivNonce) {
    return payload;
  }
  try {
    const rawIvParts = String(ivNonce).split('|');
    const ivB64 = rawIvParts[0];
    const hintPair = rawIvParts[1];
    const idA = cleanId(senderHash).toUpperCase();
    const idB = cleanId(recipientHash).toUpperCase();
    const sortedPair = hintPair || [idA, idB].sort().join(':');
    const keyMaterial = `${sortedPair}:PESSOASAQUI_E2EE_V1_SECURE_SALT_2026`;
    const key = crypto.createHash('sha256').update(keyMaterial, 'utf8').digest();
    const iv = Buffer.from(ivB64, 'base64');
    const combined = Buffer.from(payload.slice(4), 'base64');
    if (combined.length <= 16) return payload;
    const authTag = combined.slice(combined.length - 16);
    const encryptedData = combined.slice(0, combined.length - 16);
    const decipher = crypto.createDecipheriv('aes-256-gcm', key, iv);
    decipher.setAuthTag(authTag);
    const decrypted = Buffer.concat([decipher.update(encryptedData), decipher.final()]).toString('utf8');
    return decrypted || payload;
  } catch (err) {
    return payload;
  }
}

function deliverMessageToTarget(cleanSender, senderAlias, rawRecipient, ciphertextPayload, ivNonce, msgId) {
  const cleanRecipient = cleanId(rawRecipient);
  const kRecip = keyId(cleanRecipient);
  const kSender = keyId(cleanSender);
  const resolvedSenderAlias =
    (senderAlias && senderAlias !== 'Usuário' ? senderAlias : null) ||
    activePresence.get(kSender)?.alias ||
    'Usuário';

  // Garante compatibilidade total entre aparelhos com APK novo (AES-256-GCM) e APK anterior
  const compatiblePayload = unwrapE2eeIfCompatible(ciphertextPayload, ivNonce, cleanSender, cleanRecipient);

  // Resolve destinatário tanto pelo identityHash quanto pelo apelido ativo (caso o remetente tenha ID antigo do contato)
  const targetKeys = new Set([kRecip]);
  for (const [presKey, peer] of activePresence.entries()) {
    if (keyId(peer.identityHash) === kRecip || (peer.alias && peer.alias.trim().toLowerCase() === kRecip)) {
      targetKeys.add(presKey);
      targetKeys.add(keyId(peer.identityHash));
    }
  }

  let deliveredWs = false;
  for (const tk of targetKeys) {
    if (!tk || tk === kSender) continue;

    const msgObj = {
      type: 'E2EE_MESSAGE_RECEIVED',
      messageId: msgId,
      senderHash: cleanSender,
      senderAlias: resolvedSenderAlias,
      ciphertextPayload: compatiblePayload,
      ivNonce: ivNonce || '',
      timestamp: Date.now()
    };

    const targetWs = activeSockets.get(tk);
    if (targetWs && targetWs.readyState === WebSocket.OPEN) {
      try {
        targetWs.send(JSON.stringify(msgObj));
        deliveredWs = true;
      } catch (_) {}
    }

    // Também armazena na fila pendente HTTP (com limite de 50 mensagens) para garantir entrega
    // mesmo se o WebSocket do destinatário estiver em transição de rede (o cliente deduplica por messageId).
    // Mensagens efêmeras / de sinalização como [READ_ACK] ou frames de áudio/vídeo não devem ser acumuladas em fila.
    const payloadStr = String(compatiblePayload || '');
    const isEphemeral = payloadStr.startsWith('[CALL_') ||
                        payloadStr.startsWith('[FAMILY_') ||
                        payloadStr === '[READ_ACK]' ||
                        payloadStr.startsWith('[MSG_DELETE:') ||
                        payloadStr.startsWith('[MSG_EDIT:');

    if (!isEphemeral) {
      if (!pendingMessages.has(tk)) {
        pendingMessages.set(tk, []);
      }
      const queue = pendingMessages.get(tk);
      if (!queue.some(m => m.messageId === msgId)) {
        queue.push(msgObj);
        if (queue.length > 50) queue.shift();
      }
    }
  }

  // Log resumido (ignora frames contínuos de chamada ou acks em lote para não poluir o console)
  const isContinuous = String(compatiblePayload).startsWith('[CALL_AUDIO_FRAME:') || String(compatiblePayload).startsWith('[CALL_VIDEO_FRAME:') || compatiblePayload === '[READ_ACK]';
  if (!isContinuous) {
    console.log(`[MSG] De ${resolvedSenderAlias} (${cleanSender}) -> ${cleanRecipient} (WS=${deliveredWs})`);
  }
  return deliveredWs;
}

wss.on('connection', (ws, req) => {
  let authenticatedIdentity = null;
  const serverChallenge=crypto.randomBytes(24).toString('hex');
  ws.send(JSON.stringify({type:'AUTH_CHALLENGE',nonce:serverChallenge}));
  const authTimeout=setTimeout(()=>{
    if(!authenticatedIdentity){try{ws.close(4001,'Authentication timeout');}catch(_){}}
  },20000);
  if(typeof authTimeout.unref==='function')authTimeout.unref();

  ws.on('message', async (data) => {
    try {
      const msg = JSON.parse(data.toString());

      if (msg.type === 'AUTH' && !authenticatedIdentity) {
        const requestedIdentity=cleanId(msg.identityHash);
        const incomingAlias=String(msg.alias||'Usuário PessoasAqui').trim().slice(0,64);
        const kAuth=keyId(requestedIdentity);
        if(!/^[A-F0-9]{16}$/.test(requestedIdentity)){
          ws.send(JSON.stringify({type:'AUTH_FAILED',message:'Identidade inválida.'}));
          try{ws.close(4001,'Invalid identity');}catch(_){} return;
        }
        if(bannedIdentities.has(kAuth)){
          ws.send(JSON.stringify({type:'ACCOUNT_BANNED',message:'Identidade suspensa.'}));
          try{ws.close(4003,'Account banned');}catch(_){} return;
        }
        // Ignorar primeiro AUTH legado, sem aceitar a sessão antes do desafio.
        if(msg.challengeNonce!==serverChallenge) return;
        let activeKey=null;
        if(process.env.DATABASE_URL){
          try{
            if(!isPostgres())throw Error('Banco indisponível');
            const binding=await query('SELECT public_key_spki FROM identity_key_bindings WHERE identity_id=$1',[requestedIdentity]);
            if(!isPostgres())throw Error('Banco indisponível');
            activeKey=binding.rows[0]?.public_key_spki||null;
          }catch(_){
            ws.send(JSON.stringify({type:'AUTH_FAILED',message:'Autenticação indisponível.'}));
            try{ws.close(4001,'Database unavailable');}catch(_){} return;
          }
        }
        if(!verifyWsIdentity(requestedIdentity,msg.publicKey,msg.signature,msg.challengeNonce,serverChallenge,activeKey)){
          ws.send(JSON.stringify({type:'AUTH_FAILED',message:'Prova criptográfica inválida.'}));
          try{ws.close(4001,'Invalid proof');}catch(_){} return;
        }
        authenticatedIdentity=requestedIdentity;
        clearTimeout(authTimeout);
        registeredDevicePublicKeys.set(kAuth,msg.publicKey);

        // Regra 18: 1 Identidade = 1 Dispositivo Ativo. Fecha socket anterior se houver
        if (activeSockets.has(kAuth)) {
          const oldWs = activeSockets.get(kAuth);
          if (oldWs && oldWs !== ws) {
            try { oldWs.close(1000, "Replaced by active device connection"); } catch (_) {}
          }
        }
        activeSockets.set(kAuth, ws);

        // Remove qualquer presença antiga/duplicada que compartilhe o mesmo apelido, MAS apenas se for de OUTRO id!
        for (const [idKey, peer] of activePresence.entries()) {
          if (keyId(idKey) !== kAuth && keyId(peer.identityHash) !== kAuth &&
              peer.alias && incomingAlias && peer.alias.toLowerCase() === incomingAlias.toLowerCase()) {
            console.log(`[WS] Removendo presença fantasma para o apelido "${incomingAlias}" (id antigo: ${peer.identityHash})`);
            activePresence.delete(idKey);
            wss.clients.forEach((client) => {
              if (client !== ws && client.readyState === WebSocket.OPEN) {
                client.send(JSON.stringify({ type: 'PEER_OFFLINE', identityHash: peer.identityHash }));
              }
            });
          } else if (idKey !== kAuth && keyId(idKey) === kAuth) {
            // Limpa chave maiúscula legada sem emitir PEER_OFFLINE
            activePresence.delete(idKey);
          }
        }

        activePresence.set(kAuth, {
          identityHash: authenticatedIdentity,
          alias: incomingAlias,
          intent: msg.intent || 'QUERO_CONVERSAR',
          publicKey: msg.publicKey || registeredDevicePublicKeys.get(kAuth) || null,
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

        // Transmite no PRESENCE_SYNC apenas quem já possui relação mútua ou de marcação
        const mutualOrRelatedPeers = enrichedPeers.filter(p => p.isMutual || p.isMarkedByMe || p.isMarkingMe);
        ws.send(JSON.stringify({ type: 'PRESENCE_SYNC', peers: mutualOrRelatedPeers }));

        // Transmite anúncio de presença apenas para aparelhos que já possuem conexão mútua com este usuário
        const peerData = activePresence.get(kAuth);
        for (const [targetKey, client] of activeSockets.entries()) {
          if (client !== ws && client.readyState === WebSocket.OPEN) {
            const status = await getConnectionStatus(authenticatedIdentity, targetKey);
            if (status.isMutual) {
              client.send(JSON.stringify({ type: 'PEER_ONLINE', peer: { ...peerData, isMutual: true } }));
            }
          }
        }
      }

      // Estado de vinculação é autoritativo no servidor: o cliente não pode
      // ressuscitar vínculos revogados usando um cache local desatualizado.
      if (msg.type === 'CLIENT_MUTUAL_SYNC') {
        ws.send(JSON.stringify({
          type: 'CLIENT_MUTUAL_SYNC_REJECTED',
          message: 'Sincronização unilateral de vínculos foi desativada.'
        }));
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

      // Relay de Mensagem Ponta a Ponta (E2EE)
      if (msg.type === 'E2EE_MESSAGE' && authenticatedIdentity) {
        const { recipientHash, ciphertextPayload, ivNonce, messageId } = msg;
        const isStream = String(ciphertextPayload).startsWith('[CALL_AUDIO_FRAME:') || String(ciphertextPayload).startsWith('[CALL_VIDEO_FRAME:');
        if (!checkRateLimit(authenticatedIdentity, isStream)) {
          return;
        }

        const kAuth = keyId(authenticatedIdentity);
        const msgId = messageId || `${authenticatedIdentity}_${Date.now()}_${Math.random()}`;

        if (isDuplicateMessage(msgId)) {
          return;
        }

        const senderAlias = activePresence.get(kAuth)?.alias || 'Usuário';
        deliverMessageToTarget(authenticatedIdentity, senderAlias, recipientHash, ciphertextPayload, ivNonce, msgId);
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
        // Não apaga activePresence imediatamente no close do WS pois o aparelho pode estar
        // ativo via HTTP Heartbeat / reconectando em 4G/Wi-Fi.
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

let appVersionConfig = {
  latestVersionCode: parseInt(process.env.APP_LATEST_VERSION_CODE || '25', 10),
  latestVersionName: process.env.APP_LATEST_VERSION_NAME || '1.2.9',
  minSupportedVersionCode: parseInt(process.env.APP_MIN_SUPPORTED_VERSION_CODE || '8', 10),
  forceUpdate: process.env.APP_FORCE_UPDATE === 'true',
  playStoreUrl: process.env.APP_PLAY_STORE_URL || 'https://play.google.com/store/apps/details?id=br.com.pessoasaqui',
  releaseNotes: process.env.APP_RELEASE_NOTES || '• Atualização oficial do PessoasAqui disponível na Google Play Store\n• Criptografia ponta a ponta reforçada e melhorias de conectividade em chamadas\n• Backup silencioso e restauração de dados 100% integrados\n• Correções gerais de estabilidade e desempenho'
};

/**
 * Verificação de Versão do Aplicativo (Google Play Update Check)
 * Dinâmico e configurável via variáveis de ambiente, banco de dados ou painel admin
 */
app.get('/api/app/version-check', async (req, res) => {
  if (isPostgres()) {
    try {
      const vRes = await query(`SELECT value FROM app_config WHERE key = 'version_config'`);
      if (vRes.rows.length > 0) {
        const parsed = JSON.parse(vRes.rows[0].value);
        appVersionConfig = { ...appVersionConfig, ...parsed };
      }
    } catch (_) {}
  }

  res.json({
    success: true,
    ...appVersionConfig
  });
});

/**
 * Atualização da Versão Oficial do App (Permite ao administrador ou CI/CD sincronizar a Play Store)
 */
app.post(['/api/app/version-update', '/admin/api/app-version'], async (req, res) => {
  const { latestVersionCode, latestVersionName, minSupportedVersionCode, forceUpdate, playStoreUrl, releaseNotes } = req.body;
  if (latestVersionCode !== undefined) appVersionConfig.latestVersionCode = parseInt(latestVersionCode, 10);
  if (latestVersionName !== undefined) appVersionConfig.latestVersionName = String(latestVersionName);
  if (minSupportedVersionCode !== undefined) appVersionConfig.minSupportedVersionCode = parseInt(minSupportedVersionCode, 10);
  if (forceUpdate !== undefined) appVersionConfig.forceUpdate = Boolean(forceUpdate);
  if (playStoreUrl !== undefined) appVersionConfig.playStoreUrl = String(playStoreUrl);
  if (releaseNotes !== undefined) appVersionConfig.releaseNotes = String(releaseNotes);

  if (isPostgres()) {
    try {
      await query(`CREATE TABLE IF NOT EXISTS app_config (key VARCHAR(64) PRIMARY KEY, value TEXT)`);
      await query(`INSERT INTO app_config (key, value) VALUES ('version_config', $1) ON CONFLICT (key) DO UPDATE SET value = $1`, [JSON.stringify(appVersionConfig)]);
    } catch (e) {
      console.warn('[Version] Aviso ao persistir config de versão no PostgreSQL:', e.message);
    }
  }

  console.log(`[Version] Nova versão configurada com sucesso: v${appVersionConfig.latestVersionName} (code: ${appVersionConfig.latestVersionCode})`);
  res.json({ success: true, appVersion: appVersionConfig });
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
  const kId = keyId(cleanIdentity);
  const cleanAlias = (alias || 'Usuário PessoasAqui').trim();

  // Remove qualquer presença fantasma com o mesmo apelido apenas se pertencer a outro ID
  for (const [idKey, peer] of activePresence.entries()) {
    if (keyId(idKey) !== kId && keyId(peer.identityHash) !== kId &&
        peer.alias && cleanAlias && peer.alias.toLowerCase() === cleanAlias.toLowerCase()) {
      activePresence.delete(idKey);
    } else if (idKey !== kId && keyId(idKey) === kId) {
      activePresence.delete(idKey);
    }
  }

  activePresence.set(kId, {
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
  const kMyHash = keyId(myHash);
  const myAlias = (req.query.myAlias || '').trim().toLowerCase();
  const now = Date.now();
  const peers = Array.from(activePresence.values())
    .filter(p => keyId(p.identityHash) !== kMyHash &&
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
    // Retorna apenas quem possui relação mútua ou de marcação para não vazar estranhos de outras cidades
    const relevant = enriched.filter(p => p.isMutual || p.isMarkedByMe || p.isMarkingMe);
    return res.json({ success: true, count: relevant.length, people: relevant });
  }

  res.json({ success: true, count: 0, people: [] });
});

/**
 * Ofertas autenticadas v2: o servidor não presume 10m de proximidade.
 * BLE/GATT envia anúncios diretamente a pares fisicamente encontrados.
 */
registerOfferRoutesV2(app,{query,withTransaction,isPostgres,activeOffers});

/**
 * Envio de Mensagem Cifrada via HTTP (Fallback redundante para WebSockets)
 */
app.post('/api/messages/send', (req, res) => {
  const { senderHash, recipientHash, ciphertextPayload, ivNonce, senderAlias, messageId } = req.body;
  if (!recipientHash || !ciphertextPayload) {
    return res.status(400).json({ error: 'recipientHash e ciphertextPayload são obrigatórios.' });
  }

  const cleanSender = cleanId(senderHash);
  const isStream = String(ciphertextPayload).startsWith('[CALL_AUDIO_FRAME:') || String(ciphertextPayload).startsWith('[CALL_VIDEO_FRAME:');
  if (!checkRateLimit(cleanSender, isStream)) {
    return res.status(429).json({ error: 'Muitas requisições. Limite de envio por segundo excedido.' });
  }

  const msgId = messageId || `${cleanSender}_${Date.now()}_${Math.random()}`;

  if (isDuplicateMessage(msgId)) {
    return res.json({ success: true, delivered: true, duplicate: true });
  }

  const delivered = deliverMessageToTarget(cleanSender, senderAlias, recipientHash, ciphertextPayload, ivNonce, msgId);
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
  const cleanKey = keyId(rawId);
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

  // Sempre armazena no cache em memória local para velocidade e resiliência
  localStore.identities.set(cleanIdentity, {
    id: cleanIdentity,
    publicKeyEd25519,
    publicKeyX25519,
    pinHash,
    alias: alias || 'Eu'
  });

  if (isPostgres()) {
    try {
      await query(
        `INSERT INTO identities (id, public_key_ed25519, public_key_x25519, pin_salted_hash, alias)
         VALUES ($1, $2, $3, $4, $5)
         ON CONFLICT (id) DO UPDATE SET alias = EXCLUDED.alias, updated_at = NOW()`,
        [cleanIdentity, publicKeyEd25519, publicKeyX25519 || '', pinHash, alias || 'Eu']
      );
    } catch (e) {
      console.warn('[DB] Aviso ao gravar identidade no PostgreSQL (mantido em memória):', e.message);
    }
  }

  res.json({ success: true, identityHash: cleanIdentity, message: 'Identidade técnica registrada com sucesso.' });
});

/**
 * Consulta de Status de Conexões (Mútuas e Marcações)
 * Isolamento Estrito: Retorna estritamente conexões onde o solicitante (myIdentity) seja participante.
 */
app.get('/api/connections/status', async (req, res) => {
  const myId = cleanId(req.query.myIdentity);
  if (!myId) {
    return res.status(400).json({ error: 'myIdentity é obrigatório.' });
  }

  if (process.env.DATABASE_URL && !isPostgres()) {
    return res.status(503).json({success:false,error:'Banco de conexões indisponível.'});
  }

  const kMyId = keyId(myId);
  let markedByMe = [];
  let markingMe = [];
  let mutual = [];

  // Lê as conexões em memória local com filtro rigoroso de isolamento
  for (const [key, val] of localStore.connections.entries()) {
    const f = cleanId(val.fromIdentity);
    const t = cleanId(val.toIdentity);
    const kF = keyId(f);
    const kT = keyId(t);
    if (kF === kMyId && !markedByMe.includes(t)) markedByMe.push(t);
    if (kT === kMyId && !markingMe.includes(f)) markingMe.push(f);
    if (val.isMutual && (kF === kMyId || kT === kMyId)) {
      const partner = (kF === kMyId) ? t : f;
      if (!mutual.includes(partner)) mutual.push(partner);
    }
  }

  if (isPostgres()) {
    try {
      const result = await query(
        `SELECT from_identity, to_identity, is_mutual FROM connections
         WHERE LOWER(from_identity) = LOWER($1) OR LOWER(to_identity) = LOWER($1)`,
        [myId]
      );
      for (const row of result.rows) {
        const f = cleanId(row.from_identity);
        const t = cleanId(row.to_identity);
        const kF = keyId(f);
        const kT = keyId(t);
        if (kF === kMyId && !markedByMe.includes(t)) markedByMe.push(t);
        if (kT === kMyId && !markingMe.includes(f)) markingMe.push(f);
        if (row.is_mutual && (kF === kMyId || kT === kMyId)) {
          const partner = (kF === kMyId) ? t : f;
          if (!mutual.includes(partner)) mutual.push(partner);
        }
      }
    } catch (e) {
      console.error('[DB] Erro ao buscar connections:', e.message);
      return res.status(503).json({ success: false, error: 'Estado de conexões indisponível.' });
    }
  }

  res.json({ success: true, markedByMe, markingMe, mutual });
});

/**
 * Sincronização em Massa de Conexões Mútuas pelo Cliente (Blindagem de Conexões)
 */
app.post('/api/connections/sync', async (_req, res) => {
  // Endpoint legado inseguro: aceitava declarar conexões mútuas unilateralmente
  // e ressuscitava vínculos revogados. Só /mark com reciprocidade pode criar vínculo.
  return res.status(410).json({ success: false, error: 'Sincronização unilateral desativada. Use /connections/mark.' });
});

// Somente a identidade dona da chave privada pode autorizar marcação e revogação.
// No modo de desenvolvimento sem DATABASE_URL o caminho antigo fica disponível para testes.
const connectionProofNonces=new Set();
async function authorizeConnectionWrite(req,res,operation,from,to){
  const proof=req.body?.identityProof;
  if(!process.env.DATABASE_URL && !proof)return true;
  if(!proof){res.status(401).json({error:'Assinatura criptográfica obrigatória.'});return false;}
  let verified=false;
  try{verified=await verifyActiveInviteProof(operation,from,to,'','',proof);}
  catch(_){res.status(503).json({error:'Autenticação indisponível.'});return false;}
  if(!verified){res.status(401).json({error:'Assinatura ou chave ativa inválida.'});return false;}
  if(process.env.DATABASE_URL){
    try{
      const r=await query(
       'INSERT INTO recovery_proof_nonces(identity_id, operation, nonce) VALUES ($1,$2,$3) ON CONFLICT DO NOTHING RETURNING nonce',
       [from,operation,proof.nonce]);
      if(r.rowCount!==1){res.status(409).json({error:'Assinatura já utilizada.'});return false;}
    }catch(_){res.status(503).json({error:'Não foi possível registrar a autorização.'});return false;}
  } else {
    const key=from+':'+operation+':'+proof.nonce;
    if(connectionProofNonces.has(key)){res.status(409).json({error:'Assinatura já utilizada.'});return false;}
    if(connectionProofNonces.size>10000)connectionProofNonces.clear();
    connectionProofNonces.add(key);
  }
  return true;
}

/**
 * Marcação Unilateral e Conexão Mútua (Seções 10 e 11)
 * 10 metros = descoberta | Marcação mútua = vínculo à distância
 */
app.post('/api/connections/mark', async (req, res) => {
  const { fromIdentity, toIdentity, senderAlias } = req.body;
  const from = cleanId(fromIdentity);
  const to = cleanId(toIdentity);

  if(!/^[A-F0-9]{16}$/.test(from)|| !/^[A-F0-9]{16}$/.test(to)||from===to){
    return res.status(400).json({error:'Identidades válidas e distintas obrigatórias.'});
  }
  if(process.env.DATABASE_URL && !isPostgres()){
    return res.status(503).json({error:'Banco de vínculos indisponível.'});
  }
  if(!await authorizeConnectionWrite(req,res,'CONNECTION_MARK',from,to))return;

  if (process.env.DATABASE_URL && !isPostgres()) {
    return res.status(503).json({success:false,error:'Banco de conexões indisponível.'});
  }

  // Verifica se o contato de destino já havia marcado a origem
  let isReciprocal = false;

  const kFrom = keyId(from);
  const kTo = keyId(to);

  const reverseKey = `${kTo}->${kFrom}`;
  if (localStore.connections.has(reverseKey)) {
    isReciprocal = true;
  }

  if (isPostgres()) {
    try {
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
    } catch (e) {
      console.error('[DB] Erro ao gravar marcação no PostgreSQL:', e.message);
      return res.status(503).json({ success: false, error: 'Marcação não confirmada no banco.' });
    }
  }

  // Sempre atualiza localStore em memória para velocidade imediata e resiliência
  localStore.connections.set(`${kFrom}->${kTo}`, { fromIdentity: from, toIdentity: to, isMutual: isReciprocal });
  if (isReciprocal && localStore.connections.has(reverseKey)) {
    localStore.connections.get(reverseKey).isMutual = true;
  } else if (isReciprocal) {
    localStore.connections.set(reverseKey, { fromIdentity: to, toIdentity: from, isMutual: true });
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
 * Desfaz Marcação e Conexão Mútua
 */
app.post('/api/connections/unmark', async (req, res) => {
  const { fromIdentity, toIdentity, senderAlias } = req.body;
  const from = cleanId(fromIdentity);
  const to = cleanId(toIdentity);

  if(!/^[A-F0-9]{16}$/.test(from)|| !/^[A-F0-9]{16}$/.test(to)||from===to){
    return res.status(400).json({error:'Identidades válidas e distintas obrigatórias.'});
  }
  if(process.env.DATABASE_URL && !isPostgres()){
    return res.status(503).json({error:'Banco de vínculos indisponível.'});
  }
  if(!await authorizeConnectionWrite(req,res,'CONNECTION_UNMARK',from,to))return;

  if (process.env.DATABASE_URL && !isPostgres()) {
    return res.status(503).json({success:false,error:'Banco de conexões indisponível.'});
  }

  const kFrom = keyId(from);
  const kTo = keyId(to);

  // Revogação de QUALQUER participante desfaz as duas marcações.
  // Sem isto, uma nova marcação poderia reativar o vínculo unilateralmente.
  if (isPostgres()) {
    try {
      await query(
        `DELETE FROM connections
         WHERE (LOWER(from_identity) = LOWER($1) AND LOWER(to_identity) = LOWER($2))
            OR (LOWER(from_identity) = LOWER($2) AND LOWER(to_identity) = LOWER($1))`,
        [from, to]
      );
    } catch (e) {
      console.error('[DB] Erro ao revogar conexão no PostgreSQL:', e.message);
      return res.status(503).json({ success: false, error: 'Revogação não confirmada no banco. Tente novamente.' });
    }
  }
  localStore.connections.delete(`${kFrom}->${kTo}`);
  localStore.connections.delete(`${kTo}->${kFrom}`);

  const myAlias = senderAlias || activePresence.get(kFrom)?.alias || 'Alguém';
  const targetWs = activeSockets.get(kTo);
  const msgForTo = {
    type: 'MUTUAL_CONNECTION_BROKEN',
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
      ciphertextPayload: `[MARK_OFF:${myAlias}]`,
      ivNonce: '',
      messageId: `unmark_${Date.now()}`
    });
  }

  res.json({
    success: true,
    message: 'Marcação removida com sucesso.'
  });
});

/**
 * Geração de Convite Criptografado de Uso Único para Conexão Mútua à Distância
 */
// Um convite é uma credencial bearer. Ele é ASSINADO quando existe segredo
// de implantação, mas NÃO é texto criptografado: evite logs, previews e
// armazenamento em terceiros. Somente o banco decide quem o resgatou.
const inviteDbRequired = Boolean(process.env.DATABASE_URL);
const inviteMutex = new Set();
// Caso uma identidade tenha sido recuperada, APENAS a chave registrada
// atomicamente como ativa no PostgreSQL pode assinar convites.
async function verifyActiveInviteProof(operation, identity, alias, inviteId, tokenHash, proof) {
  let binding = null;
  if (inviteDbRequired) {
    if (!isPostgres()) throw new Error('Banco de autenticação indisponível');
    const found = await query(
      'SELECT public_key_spki FROM identity_key_bindings WHERE identity_id=$1',
      [identity]);
    if (!isPostgres()) throw new Error('Banco de autenticação indisponível');
    binding = found.rows[0]?.public_key_spki || null;
  }
  return verifyInviteIdentityProof(operation, identity, alias, inviteId, tokenHash, proof,
    Date.now(), binding);
}

app.post('/api/invites/create', async (req, res) => {
  const sender = cleanId(req.body?.senderIdentity);
  const alias = String(req.body?.senderAlias || 'Usuário').trim().slice(0, 64);
  if (!/^[A-Za-z0-9_-]{8,64}$/.test(sender)) {
    return res.status(400).json({ error: 'Identidade inválida.' });
  }
  if (inviteDbRequired && !isPostgres()) {
    return res.status(503).json({ error: 'Serviço de convites temporariamente indisponível.' });
  }

  // Autenticação vinculada à chave que gerou a identidade técnica (SHA-256/SPKI).
  // NÃO aceitar somente senderIdentity, nem uma chave pública não vinculada ao ID.
  let creatorVerified = false;
  try {
    creatorVerified = await verifyActiveInviteProof('CREATE', sender, alias, '', '', req.body?.identityProof);
  } catch (_) { return res.status(503).json({error:'Autenticação indisponível.'}); }
  if (!creatorVerified) {
    return res.status(401).json({ error: 'Identidade não autenticada. Atualize o aplicativo ou valide a recuperação da identidade.' });
  }
  // ID determinístico do nonce assinado: replay não gera segundo convite,
  // inclusive em instâncias distintas que compartilham a chave primária do PostgreSQL.
  const inviteId = deriveInviteId(sender, req.body.identityProof.nonce);
  const token = generateNonce(); // 256 bits; só seu SHA-256 vai para o banco
  const expiresAt = Date.now() + INVITE_LIFETIME_MS;
  const signature = signInvitePayload(inviteId, token, sender, expiresAt);
  const inviteData = {
    inviteId, tokenHash: tokenDigest(token),
    senderIdentity: sender, senderAlias: alias, signature,
    isUsed: false, usedBy: null, expiresAt
  };

  if (inviteDbRequired) {
    try {
      const result = await query(
        `INSERT INTO invites (id, token, sender_identity, sender_alias, signature, expires_at)
         VALUES ($1, $2, $3, $4, $5, TO_TIMESTAMP($6 / 1000.0))
         ON CONFLICT DO NOTHING RETURNING id`,
        [inviteId, inviteData.tokenHash, sender, alias, signature, expiresAt]
      );
      if (result.rowCount !== 1) {
        return res.status(409).json({ error: 'Solicitação de convite já utilizada. Gere outra.' });
      }
    } catch (err) {
      console.error('[Convite] Falha ao persistir convite:', err.message);
      return res.status(503).json({ error: 'Não foi possível registrar o convite.' });
    }
  } else {
    // Modo dev SEM DATABASE_URL: memória somente; não há fallback stateless.
    if (activeInvites.has(inviteId)) {
      return res.status(409).json({ error: 'Solicitação de convite já utilizada. Gere outra.' });
    }
    activeInvites.set(inviteId, inviteData);
    if (localStore.invites) localStore.invites.set(inviteId, inviteData);
  }

  const baseUrl = 'https://pessoasaqui3.onrender.com';
  const queryParams = new URLSearchParams({
    id: inviteId, token, sender, alias,
    ...(signature ? { sig: signature } : {}), exp: String(expiresAt)
  });
  const inviteUrl = `${baseUrl}/invite?${queryParams}`;
  console.log(`[Convite] Registrado com expiração (ID): ${inviteId}`);
  return res.json({
    success: true, inviteId, token, signature, inviteUrl,
    senderIdentity: sender, senderAlias: alias, expiresAt
  });
});

/**
 * Resgate atômico: um token pode ser reivindicado uma única vez, inclusive
 * quando duas instâncias Render tentarem processá-lo simultaneamente.
 * O vínculo só é confirmado se a transação persistir no PostgreSQL.
 */
app.post('/api/invites/redeem', async (req, res) => {
  const { inviteId, token, receiverIdentity, receiverAlias } = req.body || {};
  const receiver = cleanId(receiverIdentity);
  const recAlias = String(receiverAlias || 'Usuário Convidado').trim().slice(0, 64);
  if (!validateInviteId(inviteId) || !validateInviteToken(token) ||
      !/^[A-Za-z0-9_-]{8,64}$/.test(receiver)) {
    return res.status(400).json({ error: 'Convite ou destinatário inválido.' });
  }
  if (inviteDbRequired && !isPostgres()) {
    return res.status(503).json({ error: 'Banco de convites indisponível. Tente novamente.' });
  }
  // O token bearer sozinho não autoriza assumir receiverIdentity.
  // A assinatura vincula a aceitação ao titular da identidade destinatária.
  let receiverVerified = false;
  try {
    receiverVerified = await verifyActiveInviteProof('REDEEM', receiver, recAlias,
      inviteId, tokenDigest(token), req.body?.identityProof);
  } catch (_) { return res.status(503).json({error:'Autenticação indisponível.'}); }
  if (!receiverVerified) {
    return res.status(401).json({ error: 'Identidade de quem aceita não autenticada. Atualize o aplicativo.' });
  }

  let sender, originalAlias;
  if (inviteDbRequired) {
    try {
      // A CTE é UMA instrução transacional. Se a atualização do vínculo falhar,
      // o consumo do token também é revertido. OLD token plaintext e NEW hash
      // continuam válidos; nenhum token novo é armazenado em texto puro.
      const claim = await query(
        `WITH claimed AS (
           UPDATE invites
              SET is_used = TRUE, used_by = $3, used_at = NOW()
            WHERE id = $1
              AND (token = $2 OR token = $4)
              AND is_used = FALSE AND expires_at > NOW()
              AND LOWER(sender_identity) <> LOWER($3)
            RETURNING sender_identity, sender_alias
         ), linked AS (
           INSERT INTO connections (from_identity, to_identity, is_mutual)
           SELECT pairs.from_identity, pairs.to_identity, TRUE
             FROM claimed
             CROSS JOIN LATERAL (
               VALUES (claimed.sender_identity, $3::varchar),
                      ($3::varchar, claimed.sender_identity)
             ) AS pairs(from_identity, to_identity)
           ON CONFLICT (from_identity, to_identity)
             DO UPDATE SET is_mutual = TRUE
           RETURNING 1
         )
         SELECT sender_identity, sender_alias,
                (SELECT COUNT(*) FROM linked) AS linked_count FROM claimed`,
        [inviteId, token, receiver, tokenDigest(token)]
      );
      if (claim.rowCount !== 1 || Number(claim.rows[0]?.linked_count) !== 2) {
        return res.status(410).json({
          error: 'Convite inválido, já usado, expirado ou indisponível.'
        });
      }
      sender = cleanId(claim.rows[0].sender_identity);
      originalAlias = claim.rows[0].sender_alias || 'Contato';
    } catch (err) {
      console.error('[Convite] Falha no resgate transacional:', err.message);
      return res.status(503).json({ error: 'Não foi possível confirmar o vínculo agora.' });
    }
  } else {
    // Sem awaits entre a verificação e a marcação; bloqueio adicional contra
    // invocação reentrante durante testes ou futuras mudanças de rota.
    if (inviteMutex.has(inviteId)) return res.status(409).json({ error: 'Convite em uso.' });
    inviteMutex.add(inviteId);
    try {
      const invite = activeInvites.get(inviteId);
      if (!invite || invite.isUsed || Date.now() > invite.expiresAt ||
          !verifyStoredToken(invite.tokenHash || invite.token, token)) {
        return res.status(410).json({ error: 'Convite inválido, expirado ou já utilizado.' });
      }
      sender = cleanId(invite.senderIdentity);
      originalAlias = invite.senderAlias;
      if (keyId(sender) === keyId(receiver)) {
        return res.status(400).json({ error: 'Você não pode usar seu próprio convite.' });
      }
      invite.isUsed = true;
      invite.usedBy = receiver;
      invite.usedAt = Date.now();
      const kSender = keyId(sender), kReceiver = keyId(receiver);
      localStore.connections.set(`${kSender}->${kReceiver}`,
        { fromIdentity: sender, toIdentity: receiver, isMutual: true });
      localStore.connections.set(`${kReceiver}->${kSender}`,
        { fromIdentity: receiver, toIdentity: sender, isMutual: true });
    } finally { inviteMutex.delete(inviteId); }
  }

  const kSender = keyId(sender), kReceiver = keyId(receiver);
  // Cache local é atualizado SOMENTE após o commit PostgreSQL.
  localStore.connections.set(`${kSender}->${kReceiver}`,
    { fromIdentity: sender, toIdentity: receiver, isMutual: true });
  localStore.connections.set(`${kReceiver}->${kSender}`,
    { fromIdentity: receiver, toIdentity: sender, isMutual: true });

  const senderWs = activeSockets.get(kSender);
  const senderMsg = {
    type: 'MUTUAL_CONNECTION_ESTABLISHED',
    partnerIdentity: receiver,
    partnerAlias: recAlias,
    timestamp: Date.now(), viaInvite: true
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
      partnerAlias: originalAlias,
      timestamp: Date.now(), viaInvite: true
    }));
  }

  console.log(`[Convite] Resgate concluído atomicamente: ${inviteId}`);
  return res.json({
    success: true,
    senderIdentity: sender, senderAlias: originalAlias,
    receiverIdentity: receiver, receiverAlias: recAlias,
    message: `✨ Conexão Mútua Estabelecida com ${originalAlias}!`
  });
});

/**
 * Landing Page Web do Convite (com suporte a Deep Link automático para o app PessoasAqui)
 */
app.get('/invite', (req, res) => {
  // Link contém segredo bearer: jamais permitir cache, indexação ou envio
  // de URL como Referer ao navegar para sites externos.
  res.set('Cache-Control', 'no-store, max-age=0');
  res.set('Referrer-Policy', 'no-referrer');
  res.set('X-Robots-Tag', 'noindex, nofollow, noarchive');
  const { id, token, sender, alias, fallback, sig, exp } = req.query;
  const safeAlias = String(alias || 'Alguém').slice(0, 64)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  const queryParams = `id=${encodeURIComponent(id || '')}&token=${encodeURIComponent(token || '')}&sender=${encodeURIComponent(sender || '')}&alias=${encodeURIComponent(alias || '')}&sig=${encodeURIComponent(sig || '')}&exp=${encodeURIComponent(exp || '')}`;
  const appDeepLink = `pessoasaqui://invite?${queryParams}`;
  const fallbackUrl = `https://pessoasaqui3.onrender.com/invite?${queryParams}&fallback=true`;
  const chromeIntentLink = `intent://invite?${queryParams}#Intent;scheme=pessoasaqui;package=br.com.pessoasaqui;S.browser_fallback_url=${encodeURIComponent(fallbackUrl)};end`;

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
      max-width: 440px;
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
      cursor: pointer;
      border: none;
      width: 100%;
      box-sizing: border-box;
      transition: opacity 0.2s;
    }
    .btn:hover {
      opacity: 0.9;
    }
    .btn-secondary {
      background-color: #1C2430;
      color: #00E5FF;
      border: 1px solid rgba(0, 229, 255, 0.3);
      font-size: 14px;
    }
    .security-note {
      font-size: 12px;
      color: #8B949E;
      margin-top: 16px;
      line-height: 1.4;
    }
    .instructions {
      margin-top: 20px;
      padding: 12px;
      border-radius: 10px;
      background: rgba(255, 255, 255, 0.03);
      border: 1px dashed #21262D;
      font-size: 12px;
      color: #8B949E;
      text-align: left;
    }
  </style>
  <script>
    // Se não for fallback, tenta abrir no app
    window.onload = function() {
      const isFallback = ${fallback ? 'true' : 'false'};
      if (!isFallback) {
        if (/Android/i.test(navigator.userAgent)) {
          window.location.href = "${chromeIntentLink}";
        } else {
          window.location.href = "${appDeepLink}";
        }
      }
    };

    function copyLink() {
      const textToCopy = window.location.href.replace('&fallback=true', '');
      if (navigator.clipboard) {
        navigator.clipboard.writeText(textToCopy).then(function() {
          const btn = document.getElementById('copyBtn');
          btn.innerText = '✅ Link Copiado!';
          setTimeout(function() { btn.innerText = '📋 Copiar Link do Convite'; }, 2500);
        });
      } else {
        prompt('Copie o link abaixo:', textToCopy);
      }
    }
  </script>
</head>
<body>
  <div class="card">
    <div class="badge">🔒 Convite pessoal de uso único</div>
    <h1>Conectar no PessoasAqui</h1>
    <p><span class="sender-name">${safeAlias}</span> convidou você para uma conexão mútua segura à distância com mensagens, chamadas e fotos criptografadas de ponta a ponta.</p>
    
    <a href="${chromeIntentLink}" class="btn">★ Abrir no Aplicativo PessoasAqui</a>
    <a href="${appDeepLink}" class="btn btn-secondary">Abrir via link direto</a>
    <button id="copyBtn" onclick="copyLink()" class="btn btn-secondary">📋 Copiar Link do Convite</button>

    <div class="instructions">
      <strong style="color:#F0F6FC;">Dica de Conexão:</strong><br>
      Se o app não abrir automaticamente, você pode tocar em <strong>"Copiar Link do Convite"</strong>, abrir o aplicativo <strong>PessoasAqui</strong>, ir na aba <strong>Conversas</strong> e tocar em <strong>"Colar Convite"</strong>.
    </div>
    <div class="security-note">Este link é exclusivo e expira após o primeiro uso.</div>
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
        "52:0C:ED:CA:47:CA:66:A8:A0:C9:D3:37:C7:7F:5E:E6:73:53:34:48:CC:00:E8:79:F7:08:E5:AF:D7:D3:1F:41",
        "14:A4:44:03:77:F1:C9:83:81:4A:27:0B:4D:B5:1A:87:69:B6:59:75:A8:DF:6B:47:19:D4:57:3E:68:5B:3F:58"
      ]
    }
  }]);
});

/**
 * Fluxo de recuperação familiar v2. O antigo protocolo sem autorização
 * criptográfica foi removido para impedir tomada de conta.
 */
registerFamilyRecoveryRoutes(app, {
  query, withTransaction, isPostgres, activeSockets, WebSocket
});

// =========================================================================
// SISTEMA INTEGRADO DE MODERAÇÃO, SUPORTE E PAINEL ADMINISTRATIVO (WEB)
// =========================================================================

const ADMIN_USERNAME = process.env.ADMIN_USER || 'admin';
const ADMIN_PASSWORD = process.env.ADMIN_PASSWORD || 'pessoasaqui2026';
const ADMIN_JWT_SECRET = process.env.ADMIN_JWT_SECRET || 'pa_admin_secret_key_987654321_2026';

function generateAdminToken(username) {
  const payload = `${username}:${Date.now()}`;
  const sig = crypto.createHmac('sha256', ADMIN_JWT_SECRET).update(payload).digest('hex');
  return Buffer.from(`${payload}:${sig}`).toString('base64');
}

function verifyAdminToken(token) {
  if (!token) return null;
  try {
    const decoded = Buffer.from(token, 'base64').toString('utf8');
    const parts = decoded.split(':');
    if (parts.length !== 3) return null;
    const [username, timestamp, sig] = parts;
    const expected = crypto.createHmac('sha256', ADMIN_JWT_SECRET).update(`${username}:${timestamp}`).digest('hex');
    if (sig !== expected) return null;
    // Validade de 7 dias
    if (Date.now() - parseInt(timestamp, 10) > 7 * 24 * 3600 * 1000) return null;
    return { username, role: 'SUPER_ADMIN' };
  } catch (_) {
    return null;
  }
}

function requireAdminAuth(req, res, next) {
  const authHeader = req.headers.authorization || '';
  const token = authHeader.replace(/^Bearer\s+/i, '').trim() || req.query.admin_token;
  const verified = verifyAdminToken(token);
  if (!verified) {
    return res.status(401).json({ error: 'Acesso não autorizado ao painel de moderação.' });
  }
  req.adminUser = verified;
  next();
}

// -------------------------------------------------------------
// 1. ENDPOINTS PÚBLICOS DE SEGURANÇA E SUPORTE (App Android)
// -------------------------------------------------------------

/**
 * Registro de Denúncia de Abuso / Violação (Enviado pelo App)
 */
app.post('/api/reports', async (req, res) => {
  const { reporterIdentity, accusedIdentity, violationType, reason, evidenceMessages } = req.body;
  if (!accusedIdentity || !reason) {
    return res.status(400).json({ error: 'accusedIdentity e reason são obrigatórios.' });
  }

  const reportId = 'rep_' + Date.now().toString(36) + '_' + crypto.randomBytes(3).toString('hex');
  const cleanReporter = cleanId(reporterIdentity);
  const cleanAccused = cleanId(accusedIdentity);
  const vType = (violationType || 'ABUSE').toUpperCase();
  const evidencePayloadStr = typeof evidenceMessages === 'object' ? JSON.stringify(evidenceMessages) : (evidenceMessages || '');

  const reportObj = {
    id: reportId,
    reporter_identity: cleanReporter,
    accused_identity: cleanAccused,
    violation_type: vType,
    reason: reason.trim(),
    evidence_payload: evidencePayloadStr,
    status: 'PENDING',
    moderator_notes: null,
    created_at: new Date().toISOString(),
    resolved_at: null
  };

  localStore.moderationReports.unshift(reportObj);
  if (localStore.moderationReports.length > 500) localStore.moderationReports.pop();

  if (isPostgres()) {
    try {
      await query(
        `INSERT INTO moderation_reports (id, reporter_identity, accused_identity, violation_type, reason, evidence_payload, status)
         VALUES ($1, $2, $3, $4, $5, $6, 'PENDING')`,
        [reportId, cleanReporter, cleanAccused, vType, reason.trim(), evidencePayloadStr]
      );
    } catch (e) {
      console.warn('[DB] Nota ao registrar denúncia no PostgreSQL:', e.message);
    }
  }

  console.log(`[MODERAÇÃO] Nova denúncia ${reportId}: ${cleanReporter} denunciou ${cleanAccused} (${vType})`);
  res.json({
    success: true,
    reportId,
    message: 'Denúncia registrada com sucesso. Nossa equipe de moderação analisará as evidências com prioridade.'
  });
});

/**
 * Abertura de Petição / Chamado de Suporte (Enviado pelo App)
 */
app.post('/api/tickets', async (req, res) => {
  const { authorIdentity, authorAlias, category, subject, message } = req.body;
  if (!authorIdentity || !message) {
    return res.status(400).json({ error: 'authorIdentity e message são obrigatórios.' });
  }

  const ticketId = 'tkt_' + Date.now().toString(36) + '_' + crypto.randomBytes(3).toString('hex');
  const cleanAuthor = cleanId(authorIdentity);
  const cat = (category || 'GERAL').toUpperCase();
  const subj = (subject || 'Solicitação de Suporte').trim();

  const ticketObj = {
    id: ticketId,
    author_identity: cleanAuthor,
    author_alias: authorAlias || 'Usuário',
    category: cat,
    subject: subj,
    message: message.trim(),
    status: 'OPEN',
    moderator_reply: null,
    replied_at: null,
    created_at: new Date().toISOString()
  };

  localStore.supportTickets.unshift(ticketObj);
  if (localStore.supportTickets.length > 500) localStore.supportTickets.pop();

  if (isPostgres()) {
    try {
      await query(
        `INSERT INTO support_tickets (id, author_identity, author_alias, category, subject, message, status)
         VALUES ($1, $2, $3, $4, $5, $6, 'OPEN')`,
        [ticketId, cleanAuthor, authorAlias || 'Usuário', cat, subj, message.trim()]
      );
    } catch (e) {
      console.warn('[DB] Nota ao gravar ticket no PostgreSQL:', e.message);
    }
  }

  console.log(`[SUPORTE] Novo chamado ${ticketId} de ${cleanAuthor} (${subj})`);
  res.json({
    success: true,
    ticketId,
    message: 'Chamado de suporte recebido com sucesso. Responderemos em breve.'
  });
});

/**
 * Consulta de Chamados do Usuário (Para o App exibir respostas)
 */
app.get('/api/tickets/my', async (req, res) => {
  const identity = cleanId(req.query.identity);
  if (!identity) {
    return res.status(400).json({ error: 'identity é obrigatório.' });
  }

  let tickets = localStore.supportTickets.filter(t => keyId(t.author_identity) === keyId(identity));

  if (isPostgres()) {
    try {
      const dbRes = await query(
        `SELECT * FROM support_tickets WHERE LOWER(author_identity) = LOWER($1) ORDER BY created_at DESC LIMIT 50`,
        [identity]
      );
      if (dbRes.rows.length > 0) {
        tickets = dbRes.rows;
      }
    } catch (_) {}
  }

  res.json({ success: true, tickets });
});

// -------------------------------------------------------------
// 2. ENDPOINTS DA API DO PAINEL ADMINISTRATIVO (/admin/api)
// -------------------------------------------------------------

const adminLoginAttempts = new Map(); // ip -> { count, lockedUntil }

/**
 * Login no Painel de Moderação / Admin com Proteção contra Força Bruta
 */
app.post('/admin/api/login', (req, res) => {
  const clientIp = req.ip || req.headers['x-forwarded-for'] || req.connection?.remoteAddress || 'unknown';
  const now = Date.now();
  const attempt = adminLoginAttempts.get(clientIp);

  if (attempt && attempt.lockedUntil > now) {
    const waitSec = Math.ceil((attempt.lockedUntil - now) / 1000);
    return res.status(429).json({ error: `Muitas tentativas incorretas. Aguarde ${waitSec} segundos.` });
  }

  const { username, password } = req.body;
  if (!username || !password) {
    return res.status(400).json({ error: 'Usuário e senha são obrigatórios.' });
  }

  if (username === ADMIN_USERNAME && password === ADMIN_PASSWORD) {
    adminLoginAttempts.delete(clientIp);
    const token = generateAdminToken(username);
    console.log(`[ADMIN] Login realizado com sucesso por ${username}`);
    return res.json({
      success: true,
      token,
      user: { username, role: 'SUPER_ADMIN' }
    });
  }

  const curCount = (attempt ? attempt.count : 0) + 1;
  const lockedUntil = curCount >= 5 ? now + (15 * 60 * 1000) : 0;
  adminLoginAttempts.set(clientIp, { count: curCount, lockedUntil });

  console.warn(`[ADMIN] Tentativa de login inválida de ${clientIp} para usuário "${username}" (falha ${curCount}/5)`);
  return res.status(401).json({ error: 'Credenciais de administrador incorretas.' });
});

/**
 * Estatísticas e Métricas em Tempo Real
 */
app.get('/admin/api/stats', requireAdminAuth, async (req, res) => {
  let totalIdentities = localStore.identities.size;
  if (isPostgres()) {
    try {
      const countRes = await query('SELECT COUNT(*) as count FROM identities');
      totalIdentities = parseInt(countRes.rows[0]?.count || 0, 10);
    } catch (_) {}
  }

  const pendingReports = localStore.moderationReports.filter(r => r.status === 'PENDING').length;
  const openTickets = localStore.supportTickets.filter(t => t.status === 'OPEN').length;

  res.json({
    onlineSockets: activeSockets.size,
    activePresences: activePresence.size,
    activeOffersCount: activeOffers.size,
    totalIdentities,
    pendingReports,
    openTickets,
    bannedIdentitiesCount: bannedIdentities.size,
    serverUptimeSeconds: Math.floor(process.uptime()),
    databaseMode: isPostgres() ? 'PostgreSQL (Supabase/Render)' : 'Memória Local (Dev Resiliente)'
  });
});

/**
 * Fila de Denúncias
 */
app.get('/admin/api/reports', requireAdminAuth, async (req, res) => {
  let reports = [...localStore.moderationReports];

  if (isPostgres()) {
    try {
      const dbRes = await query(`SELECT * FROM moderation_reports ORDER BY created_at DESC LIMIT 100`);
      if (dbRes.rows.length > 0) {
        reports = dbRes.rows;
      }
    } catch (_) {}
  }

  res.json({ success: true, reports });
});

/**
 * Ações de Moderação sobre Denúncias (BAN, STRIKE, DISMISS)
 */
app.post('/admin/api/reports/:id/action', requireAdminAuth, async (req, res) => {
  const { id } = req.params;
  const { action, notes } = req.body;

  if (!['BAN', 'STRIKE', 'DISMISS'].includes(action)) {
    return res.status(400).json({ error: 'Ação inválida. Use BAN, STRIKE ou DISMISS.' });
  }

  let report = localStore.moderationReports.find(r => r.id === id);
  if (isPostgres()) {
    try {
      const rRes = await query(`SELECT * FROM moderation_reports WHERE id = $1`, [id]);
      if (rRes.rows.length > 0) report = rRes.rows[0];
    } catch (_) {}
  }

  if (!report) {
    return res.status(404).json({ error: 'Denúncia não encontrada.' });
  }

  const accused = cleanId(report.accused_identity);
  const kAccused = keyId(accused);
  let newStatus = 'REVIEWED';

  if (action === 'BAN') {
    newStatus = 'BANNED';
    bannedIdentities.add(kAccused);

    if (isPostgres()) {
      try {
        await query(`UPDATE identities SET is_banned = TRUE WHERE LOWER(id) = LOWER($1)`, [accused]);
        await query(`UPDATE devices SET is_active = FALSE, revoked_at = NOW() WHERE LOWER(identity_id) = LOWER($1)`, [accused]);
      } catch (e) {
        console.warn('[DB] Erro ao aplicar banimento no DB:', e.message);
      }
    }

    // Derruba conexão ativa WebSocket se houver
    const targetWs = activeSockets.get(kAccused);
    if (targetWs && targetWs.readyState === WebSocket.OPEN) {
      try {
        targetWs.send(JSON.stringify({
          type: 'ACCOUNT_BANNED',
          message: 'Sua conta foi permanentemente banida por descumprimento das regras de segurança e convivência.'
        }));
        setTimeout(() => { try { targetWs.close(4003, 'Banned by Moderator'); } catch (_) {} }, 200);
      } catch (_) {}
    }
    activeSockets.delete(kAccused);

    // Remove do radar de presença
    if (activePresence.has(kAccused)) {
      activePresence.delete(kAccused);
      wss.clients.forEach(client => {
        if (client.readyState === WebSocket.OPEN) {
          client.send(JSON.stringify({ type: 'PEER_OFFLINE', identityHash: accused }));
        }
      });
    }

    // Remove ofertas criadas por este usuário
    for (const [offId, offData] of activeOffers.entries()) {
      if (keyId(offData.authorId || offData.authorIdentity) === kAccused) {
        activeOffers.delete(offId);
      }
    }

    console.log(`[MODERAÇÃO] Identidade banida com sucesso: ${accused}`);

  } else if (action === 'STRIKE') {
    newStatus = 'STRIKE_ISSUED';
    const strikeMsg = '⚠️ Alerta da Moderação: Você recebeu um aviso por comportamento incompatível com as Diretrizes da Comunidade.';
    const targetWs = activeSockets.get(kAccused);
    if (targetWs && targetWs.readyState === WebSocket.OPEN) {
      try {
        targetWs.send(JSON.stringify({
          type: 'MODERATION_WARNING',
          message: strikeMsg
        }));
      } catch (_) {}
    }
    deliverMessageToTarget(
      'moderacao-oficial',
      'Moderação PessoasAqui',
      accused,
      `⚠️ [AVISO FORMAL DA MODERAÇÃO]\nSua conduta violou as Diretrizes da Comunidade PessoasAqui. A reincidência acarretará em suspensão temporária ou banimento permanente.`,
      '',
      `strike_${id}_${Date.now()}`
    );
  } else if (action === 'DISMISS') {
    newStatus = 'DISMISSED';
  }

  const resolvedAt = new Date().toISOString();
  if (report) {
    report.status = newStatus;
    report.moderator_notes = notes || null;
    report.resolved_at = resolvedAt;
  }

  if (isPostgres()) {
    try {
      await query(
        `UPDATE moderation_reports SET status = $1, moderator_notes = $2, resolved_at = NOW() WHERE id = $3`,
        [newStatus, notes || null, id]
      );
    } catch (_) {}
  }

  res.json({ success: true, status: newStatus, message: `Ação ${action} aplicada à denúncia ${id}.` });
});

/**
 * Listagem de Ofertas Ativas no Mural para Auditoria
 */
app.get('/admin/api/offers', requireAdminAuth, async (req, res) => {
  if(!process.env.DATABASE_URL||!isPostgres())
    return res.status(503).json({error:'Ofertas seguras indisponíveis sem banco.'});
  try {
    const results=await query(
      'SELECT id,author_id,author_alias,profession,description,image_base64,external_link,created_at FROM offers_secure ORDER BY created_at DESC LIMIT 200');
    return res.json({success:true,offers:results.rows.map(row=>({
      id:row.id,authorId:row.author_id,authorAlias:row.author_alias,
      profession:row.profession,description:row.description,
      imageBase64:row.image_base64,externalLink:row.external_link,
      createdAt:Number(new Date(row.created_at))
    }))});
  } catch(_) {return res.status(503).json({error:'Falha ao consultar ofertas.'});}
});

/**
 * Exclusão / Derrubada de Oferta Imprópria pelo Moderador
 */
app.delete('/admin/api/offers/:id', requireAdminAuth, async (req, res) => {
  if (!process.env.DATABASE_URL || !isPostgres())
    return res.status(503).json({error:'Moderação de ofertas exige banco persistente.'});
  const {id}=req.params;
  try{
    const result=await withTransaction(run=>
      run('DELETE FROM offers_secure WHERE id=$1 RETURNING id',[id]));
    if(result.rowCount!==1) return res.status(404).json({error:'Oferta não encontrada.'});
    activeOffers.delete(id);
    return res.json({success:true,deleted:id});
  }catch(_){return res.status(503).json({error:'Banco indisponível.'});}
});

/**
 * Fila de Petições / Suporte de Clientes
 */
app.get('/admin/api/tickets', requireAdminAuth, async (req, res) => {
  let tickets = [...localStore.supportTickets];

  if (isPostgres()) {
    try {
      const dbRes = await query(`SELECT * FROM support_tickets ORDER BY created_at DESC LIMIT 100`);
      if (dbRes.rows.length > 0) {
        tickets = dbRes.rows;
      }
    } catch (_) {}
  }

  res.json({ success: true, tickets });
});

/**
 * Resposta do Moderador a uma Petição de Cliente
 */
app.post('/admin/api/tickets/:id/reply', requireAdminAuth, async (req, res) => {
  const { id } = req.params;
  const { reply } = req.body;

  if (!reply || !reply.trim()) {
    return res.status(400).json({ error: 'O texto da resposta é obrigatório.' });
  }

  let ticket = localStore.supportTickets.find(t => t.id === id);
  if (isPostgres()) {
    try {
      const tRes = await query(`SELECT * FROM support_tickets WHERE id = $1`, [id]);
      if (tRes.rows.length > 0) ticket = tRes.rows[0];
    } catch (_) {}
  }

  if (!ticket) {
    return res.status(404).json({ error: 'Petição/chamado não encontrado.' });
  }

  const nowIso = new Date().toISOString();
  if (ticket) {
    ticket.status = 'ANSWERED';
    ticket.moderator_reply = reply.trim();
    ticket.replied_at = nowIso;
  }

  if (isPostgres()) {
    try {
      await query(
        `UPDATE support_tickets SET status = 'ANSWERED', moderator_reply = $1, replied_at = NOW() WHERE id = $2`,
        [reply.trim(), id]
      );
    } catch (_) {}
  }

  const authorKey = keyId(ticket.author_identity);
  const targetWs = activeSockets.get(authorKey);
  const replyPayload = {
    type: 'SUPPORT_TICKET_REPLIED',
    ticketId: id,
    subject: ticket.subject,
    reply: reply.trim(),
    timestamp: Date.now()
  };

  // 1. Envia sinal em tempo real para o diálogo de suporte do app
  if (targetWs && targetWs.readyState === WebSocket.OPEN) {
    try {
      targetWs.send(JSON.stringify(replyPayload));
    } catch (_) {}
  }

  // 2. Entrega também no canal de chat oficial em Conversas Privadas (online ou fila offline)
  deliverMessageToTarget(
    'suporte-oficial',
    'Suporte PessoasAqui',
    ticket.author_identity,
    `📩 [RESPOSTA DE SUPORTE]\nChamado: ${ticket.subject}\n\n${reply.trim()}`,
    '',
    `tkt_reply_${id}_${Date.now()}`
  );

  console.log(`[SUPORTE] Resposta enviada ao chamado ${id} para ${ticket.author_identity}`);
  res.json({ success: true, message: 'Resposta enviada com sucesso ao cliente.' });
});

/**
 * Consulta e Gestão de Usuários
 */
app.get('/admin/api/users', requireAdminAuth, async (req, res) => {
  const usersList = [];
  for (const [id, idData] of localStore.identities.entries()) {
    const k = keyId(id);
    usersList.push({
      identity: id,
      alias: idData.alias || 'Eu',
      isOnline: activeSockets.has(k),
      isBanned: bannedIdentities.has(k)
    });
  }

  if (isPostgres() && usersList.length === 0) {
    try {
      const dbRes = await query(`SELECT id, alias, is_banned, created_at FROM identities LIMIT 100`);
      for (const row of dbRes.rows) {
        usersList.push({
          identity: row.id,
          alias: row.alias,
          isOnline: activeSockets.has(keyId(row.id)),
          isBanned: row.is_banned || bannedIdentities.has(keyId(row.id))
        });
      }
    } catch (_) {}
  }

  res.json({ success: true, users: usersList });
});

/**
 * Desbanir Usuário (Reverter Banimento)
 */
app.post('/admin/api/users/:id/unban', requireAdminAuth, async (req, res) => {
  const id = cleanId(req.params.id);
  const kId = keyId(id);

  bannedIdentities.delete(kId);

  if (isPostgres()) {
    try {
      await query(`UPDATE identities SET is_banned = FALSE WHERE LOWER(id) = LOWER($1)`, [id]);
    } catch (_) {}
  }

  console.log(`[ADMIN] Banimento revertido para a identidade: ${id}`);
  res.json({ success: true, message: `A identidade ${id} foi desbanida com sucesso.` });
});

if (require.main === module) {
  server.listen(PORT, () => {
    console.log(`[PessoasAqui Backend] Servidor rodando na porta ${PORT}`);
    console.log(`[PessoasAqui Backend] Painel Administrativo disponível em http://localhost:${PORT}/admin`);
    console.log(`[PessoasAqui Backend] Pronto para desenvolvimento local ou deploy no Render/Supabase.`);
  });
}

module.exports = { app, server, activeSockets, wss };
