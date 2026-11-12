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
const { initDatabase, localStore, resetCircuitBreaker, closeDatabase, isPostgres, query, withTransaction } = require('./db');
const { revisionsEnabled, stateFromDirections, advancePairRevision } = require('./connectionRevisions');
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

// O segredo do LINK fica no fragmento (#key), que não chega ao backend.
app.get(['/recover','/recover/'], (req,res)=>{
  res.set('Cache-Control','no-store, private');
  res.set('Referrer-Policy','no-referrer');
  res.set('Content-Security-Policy',
    "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; base-uri 'none'; form-action 'none'");
  res.sendFile(path.join(__dirname,'../public/recover/index.html'));
});

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

// Módulo de verificação e proteção de esquema (Supabase em modo somente-leitura na subida)
const { checkSchemaCompatibility, SCHEMA_STATUS } = require('./migrations');

async function verifySchemaCompatibility() {
  if (!isPostgres()) return { compatible: false, reason: 'Database not connected' };
  try {
    const check = await checkSchemaCompatibility(query);
    if (check.status === SCHEMA_STATUS.BLOCKING_INCOMPATIBLE) {
      console.error('[DB] ERRO CRÍTICO: Esquema incompatível no banco de dados. Estruturas ausentes:', check.missingStructures);
    } else if (check.status === SCHEMA_STATUS.MIGRATION_PENDING) {
      console.log('[DB] Esquema verificado em modo somente-leitura (Supabase protegido contra DDL na subida).');
      console.log(`[DB] Migrações aditivas pendentes de aprovação expressa: ${JSON.stringify(check.pendingMigrations)}`);
      console.warn('[DB] Operações com is_cancelled em fail-closed até aprovação e aplicação manual da migração.');
    } else {
      console.log('[DB] Esquema do banco verificado em modo somente-leitura: 100% compatível (Supabase protegido contra DDL na subida).');
    }

    // Carrega banimentos ativos sem DDL
    try {
      const bannedRes = await query(`SELECT id FROM identities WHERE is_banned = TRUE`);
      for (const row of bannedRes.rows) {
        bannedIdentities.add(String(row.id).toLowerCase());
      }
      if (bannedIdentities.size > 0) {
        console.log(`[DB] Banimentos ativos carregados: ${bannedIdentities.size}`);
      }
    } catch (_) {}

    // O PostgreSQL permanece a fonte autoritativa das pendências.
    // Não baixar ciphertexts de todos os usuários ao iniciar o serviço.
    return check;
  } catch (err) {
    console.warn('[DB] Nota ao verificar compatibilidade:', err.message);
    return { compatible: false, error: err.message };
  }
}

setTimeout(async () => {
  if (isPostgres()) {
    await verifySchemaCompatibility();
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

async function acknowledgePendingMessage(authenticatedRecipientId, messageId) {
  const isDbConfigured = () => (typeof process !== 'undefined' && Boolean(process.env && process.env.DATABASE_URL)) || (typeof isPostgres === 'function' && isPostgres());
  if (!authenticatedRecipientId || !messageId) {
    return { success: false, acknowledged: false, error: 'Identidade e messageId obrigatórios', statusCode: 400 };
  }
  const cleanRecip = cleanId(authenticatedRecipientId);
  if (!/^[A-Za-z0-9_-]{8,64}$/.test(cleanRecip)) {
    return { success: false, acknowledged: false, error: 'Identidade inválida', statusCode: 400 };
  }
  const kRecip = keyId(cleanRecip);

  let inDbFound = false;
  if (isDbConfigured()) {
    if (!isPostgres()) {
      return { success: false, acknowledged: false, error: 'Database unavailable', statusCode: 503 };
    }
    try {
      const checkRes = await query(
        `SELECT id, recipient_identity, is_delivered, is_cancelled FROM e2ee_messages WHERE id = $1`,
        [messageId]
      );
      if (checkRes && checkRes.rowCount > 0) {
        inDbFound = true;
        const row = checkRes.rows[0];
        // Validação estrita de autorização: apenas o destinatário legítimo pode confirmar
        if (cleanId(row.recipient_identity).toLowerCase() !== cleanRecip.toLowerCase()) {
          console.warn(`[ACK] Destinatário não autorizado para ACK do ID ${messageId}: esperado ${row.recipient_identity}, recebido ${cleanRecip}`);
          return { success: false, acknowledged: false, error: 'Não autorizado: destinatário incorreto', statusCode: 403 };
        }
        if (row.is_cancelled) {
          return { success: false, acknowledged: false, error: 'Mensagem cancelada por revogação de vínculo', statusCode: 410 };
        }
        if (row.is_delivered) {
          // Idempotência: mensagem já constava como entregue no PostgreSQL
          const queue = pendingMessages.get(kRecip);
          if (queue) {
            const idx = queue.findIndex(m => m.messageId === messageId);
            if (idx !== -1) {
              queue.splice(idx, 1);
              if (queue.length === 0) pendingMessages.delete(kRecip);
            }
          }
          return { success: true, acknowledged: true, duplicate: true, messageId };
        }
        const updateRes = await query(
          `UPDATE e2ee_messages SET is_delivered = TRUE, delivered_at = NOW() WHERE id = $1 AND LOWER(recipient_identity) = LOWER($2) AND (is_cancelled IS NULL OR is_cancelled = FALSE)`,
          [messageId, cleanRecip]
        );
        if (!updateRes || updateRes.rowCount === 0) {
          // Concorrência: outra requisição confirmou no mesmo milissegundo.
          // Re-checa is_delivered para garantir integridade e idempotência.
          const recheck = await query(
            `SELECT is_delivered, is_cancelled FROM e2ee_messages WHERE id = $1 AND LOWER(recipient_identity) = LOWER($2)`,
            [messageId, cleanRecip]
          );
          if (!recheck || recheck.rowCount === 0) {
            return { success: false, acknowledged: false, error: 'Falha ao atualizar status de entrega no banco', statusCode: 500 };
          }
          if (recheck.rows[0].is_cancelled) {
            return { success: false, acknowledged: false, error: 'Mensagem cancelada por revogação de vínculo', statusCode: 410 };
          }
        }
      }
    } catch (dbErr) {
      console.error('[DB] Erro ao registrar ACK no PostgreSQL:', dbErr.message);
      // Falha fechada: NÃO remove da memória RAM, retorna 503
      return { success: false, acknowledged: false, error: 'Database ACK failed', statusCode: 503 };
    }
  }

  let inMemoryFound = false;
  const queue = pendingMessages.get(kRecip);
  if (queue) {
    const idx = queue.findIndex(m => m.messageId === messageId);
    if (idx !== -1) {
      const targetMsg = queue[idx];
      const targetRecip = targetMsg.recipientHash ? cleanId(targetMsg.recipientHash) : cleanRecip;
      if (targetRecip.toLowerCase() === cleanRecip.toLowerCase()) {
        queue.splice(idx, 1);
        if (queue.length === 0) pendingMessages.delete(kRecip);
        inMemoryFound = true;
      } else {
        return { success: false, acknowledged: false, error: 'Não autorizado: destinatário incorreto', statusCode: 403 };
      }
    }
  }

  if (!inDbFound && !inMemoryFound) {
    return { success: false, acknowledged: false, error: 'Mensagem inexistente ou já expurgada', statusCode: 404 };
  }

  return { success: true, acknowledged: true, messageId };
}

// A recuperação do pool não pré-carrega mensagens duráveis em RAM.
// Os endpoints assinados HTTP/WS consultam pendências por destinatário no PostgreSQL.
// Assim, uma reconexão não faz um SELECT global de ciphertext_payload.


function purgePendingMessagesRam(idA, idB) {
  const cleanA = cleanId(idA);
  const cleanB = cleanId(idB);
  const kA = keyId(cleanA);
  const kB = keyId(cleanB);

  const qA = pendingMessages.get(kA);
  if (qA) {
    const filtered = qA.filter(m => keyId(m.senderHash) !== kB);
    if (filtered.length > 0) pendingMessages.set(kA, filtered);
    else pendingMessages.delete(kA);
  }
  const qB = pendingMessages.get(kB);
  if (qB) {
    const filtered = qB.filter(m => keyId(m.senderHash) !== kA);
    if (filtered.length > 0) pendingMessages.set(kB, filtered);
    else pendingMessages.delete(kB);
  }

  for (const [id, msg] of localDurableMessages.entries()) {
    const s = cleanId(msg.cleanSender).toLowerCase();
    const r = cleanId(msg.cleanRecipient).toLowerCase();
    const a = cleanA.toLowerCase();
    const b = cleanB.toLowerCase();
    if (((s === a && r === b) || (s === b && r === a)) && !msg.delivered) {
      msg.isCancelled = true;
    }
  }
}

async function purgePendingMessagesBetween(idA, idB, txQuery) {
  const isDbConfigured = () => (typeof process !== 'undefined' && Boolean(process.env && process.env.DATABASE_URL)) || (typeof isPostgres === 'function' && isPostgres());
  const cleanA = cleanId(idA);
  const cleanB = cleanId(idB);

  if (txQuery) {
    const res = await txQuery(
      `UPDATE e2ee_messages
       SET is_cancelled = TRUE, cancelled_at = NOW()
       WHERE is_delivered = FALSE
         AND (is_cancelled IS NULL OR is_cancelled = FALSE)
         AND ((LOWER(sender_identity) = LOWER($1) AND LOWER(recipient_identity) = LOWER($2))
           OR (LOWER(sender_identity) = LOWER($2) AND LOWER(recipient_identity) = LOWER($1)))
       RETURNING id`,
      [cleanA, cleanB]
    );
    if (typeof purgePendingMessagesRam === 'function') {
      purgePendingMessagesRam(idA, idB);
    }
    return { success: true, cancelledCount: res?.rowCount || 0 };
  }

  if (typeof purgePendingMessagesRam === 'function') {
    purgePendingMessagesRam(idA, idB);
  }

  if (isDbConfigured()) {
    if (!isPostgres()) {
      throw new Error('Database unavailable during purge');
    }
    const txRunner = (typeof withTransaction === 'function') ? withTransaction : async (cb) => cb(query);
    const result = await txRunner(async (tx) => {
      const res = await tx(
        `UPDATE e2ee_messages
         SET is_cancelled = TRUE, cancelled_at = NOW()
         WHERE is_delivered = FALSE
           AND (is_cancelled IS NULL OR is_cancelled = FALSE)
           AND ((LOWER(sender_identity) = LOWER($1) AND LOWER(recipient_identity) = LOWER($2))
             OR (LOWER(sender_identity) = LOWER($2) AND LOWER(recipient_identity) = LOWER($1)))
         RETURNING id`,
        [cleanA, cleanB]
      );
      return { success: true, cancelledCount: res?.rowCount || 0 };
    });
    return result;
  }

  return { success: true, cancelledCount: 0 };
}
// Cache de deduplicação de mensagens (evita mensagens repetidas/duplicadas)
const recentMessageIds = new Map();
// Controle anti-flood / taxa de envio de mensagens por cliente (max 25 msg/s para mensagens discretas)
const clientMessageRate = new Map();

function checkRateLimit(identity, isStream) {
  if (!identity) return true;
  const now = Date.now();
  const maxAllowed = isStream ? 120 : 25;
  const rateKey = isStream ? `${identity}_stream` : identity;
  let rate = clientMessageRate.get(rateKey);
  if (!rate || (now - rate.windowStart > 1000)) {
    rate = { count: 1, windowStart: now };
    clientMessageRate.set(rateKey, rate);
    return true;
  }
  rate.count++;
  if (rate.count > maxAllowed) {
    if (rate.count === maxAllowed + 1) {
      console.warn(`[ANTI-FLOOD] Taxa ${isStream ? 'de streaming' : 'de mensagens'} excedida para ${identity} (${rate.count}/seg). Descartando.`);
    }
    return false;
  }
  return true;
}
// Conjunto de identidades banidas da rede (rejeição em tempo real)
const bannedIdentities = new Set();
// Controle de entregas em processamento (in-flight) para concorrência atômica
const inFlightDeliveries = new Map();
// Armazenamento durável em memória para ambiente isolado/testes sem PostgreSQL
const localDurableMessages = new Map();
// Conjunto de conexões revogadas para contenção imediata em memória
const revokedConnections = new Set();

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

function pairKey(a, b) {
  return [keyId(a), keyId(b)].sort().join('<->');
}

async function getConnectionStatus(myId, peerId) {
  const from = cleanId(myId);
  const to = cleanId(peerId);
  if (!from || !to) return { isMarkedByMe: false, isMarkingMe: false, isMutual: false };

  // Revogação ativa em memória invalida qualquer mutualidade imediatamente
  if (typeof revokedConnections !== 'undefined' && revokedConnections.has(pairKey(from, to))) {
    return { isMarkedByMe: false, isMarkingMe: false, isMutual: false, isRevoked: true };
  }

  const kFrom = keyId(from);
  const kTo = keyId(to);

  const isDbConfigured = (typeof process !== 'undefined' && Boolean(process.env && process.env.DATABASE_URL)) ||
                         (typeof isPostgres === 'function' && isPostgres());

  // 1. Quando PostgreSQL está configurado, o banco relacional é a ÚNICA fonte autoritativa de verdade.
  // Cache em memória desatualizado NUNCA pode reviver conexões revogadas ou excluídas.
  if (isDbConfigured) {
    if (typeof isPostgres === 'function' && isPostgres()) {
      try {
        const res = await query(
          `SELECT from_identity, to_identity, is_mutual FROM connections
           WHERE (LOWER(from_identity) = LOWER($1) AND LOWER(to_identity) = LOWER($2))
              OR (LOWER(from_identity) = LOWER($2) AND LOWER(to_identity) = LOWER($1))`,
          [from, to]
        );
        let isMarkedByMe = false;
        let isMarkingMe = false;
        for (const row of res.rows) {
          const rowFrom = cleanId(row.from_identity).toLowerCase();
          const rowTo = cleanId(row.to_identity).toLowerCase();
          if (rowFrom === from.toLowerCase() && rowTo === to.toLowerCase()) {
            isMarkedByMe = true;
          }
          if (rowFrom === to.toLowerCase() && rowTo === from.toLowerCase()) {
            isMarkingMe = true;
          }
        }
        const allMutual = res.rows.length >= 2 && res.rows.every(r => r.is_mutual === true);
        const isMutual = (isMarkedByMe && isMarkingMe && allMutual);
        return { isMarkedByMe, isMarkingMe, isMutual };
      } catch (e) {
        console.error('[DB] Erro ao consultar status de conexão no PostgreSQL:', e.message);
        // Fail-closed absoluto: falha no banco NÃO pode ser interpretada como autorização!
        return { isMarkedByMe: false, isMarkingMe: false, isMutual: false, dbError: true };
      }
    } else {
      // Banco configurado mas temporariamente fora do ar: fail-closed estrito
      return { isMarkedByMe: false, isMarkingMe: false, isMutual: false, dbUnavailable: true };
    }
  }

  // 2. Desenvolvimento local (estritamente sem DATABASE_URL configurado):
  let isMarkedByMe = false;
  let isMarkingMe = false;
  let isMutual = false;
  if (typeof localStore !== 'undefined' && localStore && localStore.connections) {
    const k1 = `${kFrom}->${kTo}`;
    const k2 = `${kTo}->${kFrom}`;
    const conn1 = localStore.connections.get(k1);
    const conn2 = localStore.connections.get(k2);
    if (conn1) isMarkedByMe = true;
    if (conn2) isMarkingMe = true;
    if (conn1 && conn2 && conn1.isMutual && conn2.isMutual) {
      isMutual = true;
    }
  }
  return { isMarkedByMe, isMarkingMe, isMutual };
}

// Snapshot em lote para o AUTH do WebSocket: uma consulta por conexao, e nao
// uma consulta por peer online. Em falha SQL, nenhuma relacao e autorizada.
async function getConnectionStatusesForPeers(myId, peerIds) {
  const from = cleanId(myId);
  const peers = Array.from(new Set(peerIds.map(id => keyId(cleanId(id))).filter(id => id && id !== keyId(from))));
  const empty = () => ({ isMarkedByMe: false, isMarkingMe: false, isMutual: false });
  const statuses = new Map(peers.map(id => [id, empty()]));
  if (!from || peers.length === 0) return statuses;

  // Sem banco configurado, manter exatamente as regras locais de desenvolvimento.
  if (!process.env.DATABASE_URL) {
    await Promise.all(peers.map(async id => statuses.set(id, await getConnectionStatus(from, id))));
    return statuses;
  }
  if (!isPostgres()) return statuses;

  try {
    const rows = await query(
      `SELECT from_identity, to_identity, is_mutual FROM connections
       WHERE (LOWER(from_identity) = $1 AND LOWER(to_identity) = ANY($2::text[]))
          OR (LOWER(to_identity) = $1 AND LOWER(from_identity) = ANY($2::text[]))`,
      [keyId(from), peers]
    );
    const directions = new Map();
    for (const row of rows.rows) {
      const rowFrom = keyId(row.from_identity);
      const rowTo = keyId(row.to_identity);
      const peer = rowFrom === keyId(from) ? rowTo : rowTo === keyId(from) ? rowFrom : null;
      if (!peer || !statuses.has(peer)) continue;
      const d = directions.get(peer) || { outbound: false, inbound: false, outboundMutual: false, inboundMutual: false };
      if (rowFrom === keyId(from)) {
        d.outbound = true;
        d.outboundMutual = d.outboundMutual || row.is_mutual === true;
      } else {
        d.inbound = true;
        d.inboundMutual = d.inboundMutual || row.is_mutual === true;
      }
      directions.set(peer, d);
    }
    for (const peer of peers) {
      if (revokedConnections.has(pairKey(from, peer))) continue;
      const d = directions.get(peer);
      if (!d) continue;
      statuses.set(peer, {
        isMarkedByMe: d.outbound,
        isMarkingMe: d.inbound,
        isMutual: d.outbound && d.inbound && d.outboundMutual && d.inboundMutual
      });
    }
  } catch (error) {
    console.error('[DB] Falha na consulta de presenca em lote (fail-closed):', error.message);
    // Manter mapa com autorizacoes negadas; nunca recorrer a cache legado.
    for (const peer of peers) statuses.set(peer, empty());
  }
  return statuses;
}

// Backend não decifra nenhum envelope, inclusive os legados ENC:.
// Mensagens históricas são encaminhadas intactas e decifradas somente pelo cliente.
function unwrapE2eeIfCompatible(payload) {
  return payload;
}

function deliverMessageToTarget(cleanSender, senderAlias, rawRecipient, ciphertextPayload, ivNonce, msgId, isEphemeralParam, operationParam) {
  const isDbConfigured = () => (typeof process !== 'undefined' && Boolean(process.env && process.env.DATABASE_URL)) || (typeof isPostgres === 'function' && isPostgres());
  const cleanRecipient = cleanId(rawRecipient);
  if (!cleanRecipient || !/^[A-Za-z0-9_-]{8,64}$/.test(cleanRecipient)) {
    return Promise.resolve({ success: false, delivered: false, persisted: false, error: 'Destinatário inválido', statusCode: 400 });
  }

  const effectiveMsgId = msgId || `${cleanSender}_${Date.now()}_${Math.random()}`;
  const compatiblePayload = unwrapE2eeIfCompatible(ciphertextPayload, ivNonce, cleanSender, cleanRecipient);

  // Classificação estrita e segura por contrato autenticado:
  // - Mensagens de texto, áudio gravado, imagem, documentos, fotos reais, edições, exclusões, vínculos familiares
  //   e compartilhamento de cofre Shamir NÃO são efêmeras e DEVEM ser persistidas duravelmente!
  // - Mensagens com operation === 'MESSAGE_SEND' são INCONDICIONALMENTE permanentes.
  // - Sem contrato explícito (isEphemeralParam), mensagens NUNCA são efêmeras, mesmo se prefixos forem manipulados.
  // - Para payloads cifrados E2EE, exige contrato explícito e identificador de operação efêmera (call_frame_, ephem_, call_).
  // - Para compatibilidade legada com payloads em texto claro, reconhece comandos legados [CALL_...].
  const payloadStr = typeof compatiblePayload === 'string' ? compatiblePayload : '';
  const isPermanentPayload = payloadStr.startsWith('[DELIVERY_ACK:') ||
                             payloadStr === '[READ_ACK]' ||
                             payloadStr.startsWith('[VAULT_') ||
                             payloadStr.startsWith('[FAMILY_') ||
                             payloadStr.startsWith('[PA_REAL_PHOTO_V1:') ||
                             payloadStr.startsWith('[IMAGE:') ||
                             payloadStr.startsWith('[AUDIO:') ||
                             payloadStr.startsWith('[DOC:') ||
                             payloadStr.startsWith('[MSG_EDIT:') ||
                             payloadStr.startsWith('[MSG_DELETE:') ||
                             payloadStr.startsWith('[MARK_');

  const isLegacyPlaintextCall = payloadStr.startsWith('[CALL_AUDIO_FRAME:') ||
                                payloadStr.startsWith('[CALL_VIDEO_FRAME:') ||
                                payloadStr.startsWith('[CALL_INIT:') ||
                                payloadStr.startsWith('[CALL_ACCEPT]') ||
                                payloadStr.startsWith('[CALL_ACK]') ||
                                payloadStr.startsWith('[CALL_END]') ||
                                payloadStr.startsWith('[ICE_CANDIDATE:');

  const effectiveOp = String(operationParam || (Boolean(isEphemeralParam) ? (String(effectiveMsgId).startsWith('call_frame_') ? 'CALL_STREAM' : 'CALL_SIGNAL') : 'MESSAGE_SEND'));

  const isCallStreamContract = Boolean(isEphemeralParam) && effectiveOp === 'CALL_STREAM' && String(effectiveMsgId).startsWith('call_frame_');
  const isCallSignalContract = Boolean(isEphemeralParam) && (effectiveOp === 'CALL_SIGNAL' || effectiveOp === 'CALL_STREAM') && (
    String(effectiveMsgId).startsWith('ephem_') ||
    String(effectiveMsgId).startsWith('call_')
  );

  // Segurança: MESSAGE_SEND é sempre durável, inclusive quando o payload tem
  // prefixo legado de chamada. Tipos de operação não podem elevar permissões.
  // Compatibilidade de chamadas antigas deve ser explicitamente classificada
  // como CALL_SIGNAL/CALL_STREAM pelo despachante, nunca por MESSAGE_SEND.
  const isEphemeral = effectiveOp !== 'MESSAGE_SEND' && !isPermanentPayload && (
    isCallStreamContract ||
    isCallSignalContract ||
    isLegacyPlaintextCall
  );

  // Concorrência e integridade sob requisições simultâneas em voo:
  if (inFlightDeliveries.has(effectiveMsgId)) {
    const inFlight = inFlightDeliveries.get(effectiveMsgId);
    const sameSender = inFlight.senderIdentity.toLowerCase() === cleanSender.toLowerCase();
    const sameRecip = inFlight.recipientIdentity.toLowerCase() === cleanRecipient.toLowerCase();
    const samePayload = inFlight.ciphertextPayload === compatiblePayload;
    const sameIv = (inFlight.ivNonce || '') === (ivNonce || '');

    if (!sameSender || !sameRecip || !samePayload || !sameIv) {
      console.warn(`[MSG] Rejeição de colisão concorrente no ID ${effectiveMsgId}: requisições em voo com dados divergentes`);
      return Promise.resolve({
        success: false,
        delivered: false,
        persisted: false,
        error: 'Identificador em conflito com mensagem concorrente divergente',
        statusCode: 409
      });
    }

    // Requisições rigorosamente idênticas compartilham o processamento em voo de forma idempotente
    return inFlight.promise;
  }

  const deliveryPromise = (async () => {
    const kRecip = keyId(cleanRecipient);
    const kSender = keyId(cleanSender);
    const resolvedSenderAlias =
      (senderAlias && senderAlias !== 'Usuário' ? senderAlias : null) ||
      activePresence.get(kSender)?.alias ||
      'Usuário';

    // PERSISTÊNCIA DURÁVEL E DEDUPLICAÇÃO NO POSTGRESQL ANTES DO DESPACHO
    let persistedInDb = false;
    if (isEphemeral) {
      // 1. Mensagens efêmeras (WebRTC áudio/vídeo frames, sinalização de chamada, etc.):
      // Toda chamada ou tráfego efêmero EXIGE vínculo bilateral permanente confirmado!
      const connStatus = await getConnectionStatus(cleanSender, cleanRecipient);
      if (connStatus.dbUnavailable || connStatus.dbError) {
        return {
          success: false,
          delivered: false,
          persisted: false,
          error: 'Banco de dados indisponível para verificar autorização de chamada.',
          statusCode: 503,
          code: 'DB_UNAVAILABLE'
        };
      }
      if (connStatus.isRevoked || (typeof revokedConnections !== 'undefined' && revokedConnections.has(pairKey(cleanSender, cleanRecipient)))) {
        return {
          success: false,
          delivered: false,
          persisted: false,
          error: 'Conexão revogada ou não autorizada para chamadas.',
          statusCode: 403,
          code: 'CONNECTION_REVOKED'
        };
      }
      if (!connStatus.isMutual) {
        const isUnilateral = connStatus.isMarkedByMe || connStatus.isMarkingMe;
        return {
          success: false,
          delivered: false,
          persisted: false,
          error: isUnilateral
            ? 'Conexão unilateral não autorizada para chamadas.'
            : 'Chamadas e sinalizações exigem vínculo bilateral permanente confirmado.',
          statusCode: 403,
          code: isUnilateral ? 'UNILATERAL_NOT_PERMITTED' : 'NO_CONNECTION'
        };
      }
    } else if (isDbConfigured()) {
      // 2. Ambiente com PostgreSQL configurado (Produção / Testes Reais):
      if (!isPostgres()) {
        console.error(`[DB] PostgreSQL configurado está inacessível para mensagem durável ${effectiveMsgId}`);
        return { success: false, delivered: false, persisted: false, error: 'Database unavailable', statusCode: 503 };
      }

      try {
        const txRunner = (typeof withTransaction === 'function') ? withTransaction : async (cb) => cb(query);
        const txResult = await txRunner(async (tx) => {
          // A. Sincronização transacional de autorização de vínculo:
          // Trava os registros da tabela connections entre os pares em FOR SHARE.
          // Se o unmark estiver executando DELETE na mesma linha, o SELECT FOR SHARE aguarda a conclusão do unmark.
          const connCheck = await tx(
            `SELECT from_identity, to_identity, is_mutual FROM connections
             WHERE (LOWER(from_identity) = LOWER($1) AND LOWER(to_identity) = LOWER($2))
                OR (LOWER(from_identity) = LOWER($2) AND LOWER(to_identity) = LOWER($1))
             FOR SHARE`,
            [cleanSender, cleanRecipient]
          );

          const isTombstoned = revokedConnections.has(pairKey(cleanSender, cleanRecipient));
          let hasCancelled = false;
          try {
            const cancelRes = await tx(
              `SELECT 1 FROM e2ee_messages
               WHERE is_cancelled = TRUE
                 AND ((LOWER(sender_identity) = LOWER($1) AND LOWER(recipient_identity) = LOWER($2))
                   OR (LOWER(sender_identity) = LOWER($2) AND LOWER(recipient_identity) = LOWER($1)))
               LIMIT 1`,
              [cleanSender, cleanRecipient]
            );
            hasCancelled = (cancelRes && cancelRes.rowCount > 0);
          } catch (_) {}

          // 1. Ausência total de vínculo (nunca foram amigos ou vínculo foi excluído)
          if (!connCheck || connCheck.rowCount === 0) {
            const isRevoked = isTombstoned || hasCancelled;
            throw Object.assign(
              new Error(isRevoked ? 'Conexão revogada ou não autorizada' : 'Ausência de conexão bilateral autorizada'),
              { statusCode: 403, code: isRevoked ? 'CONNECTION_REVOKED' : 'NO_CONNECTION' }
            );
          }

          // 2. Vínculo incompleto ou unilateral (exige ambas as direções com is_mutual = true)
          const hasDirect = connCheck.rows.some(r => cleanId(r.from_identity).toLowerCase() === cleanSender.toLowerCase() && cleanId(r.to_identity).toLowerCase() === cleanRecipient.toLowerCase());
          const hasReverse = connCheck.rows.some(r => cleanId(r.from_identity).toLowerCase() === cleanRecipient.toLowerCase() && cleanId(r.to_identity).toLowerCase() === cleanSender.toLowerCase());
          const allMutual = connCheck.rows.every(r => r.is_mutual === true);

          if (!hasDirect || !hasReverse || !allMutual || connCheck.rowCount < 2) {
            throw Object.assign(
              new Error('Conexão unilateral não autorizada'),
              { statusCode: 403, code: 'UNILATERAL_NOT_PERMITTED' }
            );
          }

          // B. Inserção durável no PostgreSQL com ON CONFLICT (id) DO NOTHING
          const insertRes = await tx(
            `INSERT INTO e2ee_messages (id, sender_identity, recipient_identity, sender_alias, ciphertext_payload, iv_nonce, is_delivered)
             VALUES ($1, $2, $3, $4, $5, $6, FALSE)
             ON CONFLICT (id) DO NOTHING`,
            [effectiveMsgId, cleanSender, cleanRecipient, resolvedSenderAlias, compatiblePayload, ivNonce || '']
          );

          if (insertRes && insertRes.rowCount === 0) {
            // Conflito em chave primária existente: consulta registro para validar idempotência vs colisão
            const existingRes = await tx(
              `SELECT sender_identity, recipient_identity, ciphertext_payload, iv_nonce, is_delivered, is_cancelled
               FROM e2ee_messages WHERE id = $1`,
              [effectiveMsgId]
            );
            if (existingRes && existingRes.rowCount > 0) {
              const ex = existingRes.rows[0];
              const sameSender = cleanId(ex.sender_identity).toLowerCase() === cleanSender.toLowerCase();
              const sameRecip = cleanId(ex.recipient_identity).toLowerCase() === cleanRecipient.toLowerCase();
              const samePayload = ex.ciphertext_payload === compatiblePayload;
              const sameIv = (ex.iv_nonce || '') === (ivNonce || '');

              if (!sameSender || !sameRecip || !samePayload || !sameIv) {
                console.warn(`[MSG] Rejeição de colisão no ID ${effectiveMsgId}: dados divergentes`);
                return {
                  success: false,
                  delivered: false,
                  persisted: false,
                  error: 'Identificador em conflito com mensagem existente diferente',
                  statusCode: 409
                };
              }

              if (ex.is_cancelled) {
                console.warn(`[MSG] Rejeição de envio no ID ${effectiveMsgId}: mensagem cancelada por revogação`);
                return {
                  success: false,
                  delivered: false,
                  persisted: true,
                  error: 'Mensagem cancelada por revogação de vínculo',
                  statusCode: 410
                };
              }

              // Retentativa idempotente legítima:
              if (ex.is_delivered) {
                return {
                  success: true,
                  delivered: true,
                  persisted: true,
                  duplicate: true,
                  alreadyDelivered: true
                };
              }
            }
          }

          return { success: true, persisted: true };
        });

        if (!txResult.success) {
          return txResult;
        }
        persistedInDb = txResult.persisted;
        if (txResult.duplicate) {
          return txResult;
        }
      } catch (dbErr) {
        if (dbErr.statusCode) {
          return {
            success: false,
            delivered: false,
            persisted: false,
            error: dbErr.message,
            statusCode: dbErr.statusCode,
            code: dbErr.code
          };
        }
        console.error('[DB] Erro de persistência durável no PostgreSQL:', dbErr.message);
        return { success: false, delivered: false, persisted: false, error: 'Database persistence failed', statusCode: 503 };
      }
    } else {
      // 3. Desenvolvimento local estritamente sem DATABASE_URL:
      if (typeof revokedConnections !== 'undefined' && revokedConnections.has(pairKey(cleanSender, cleanRecipient))) {
        return {
          success: false,
          delivered: false,
          persisted: false,
          error: 'Conexão revogada ou não autorizada',
          statusCode: 403,
          code: 'CONNECTION_REVOKED'
        };
      }
      const kFrom = keyId(cleanSender);
      const kTo = keyId(cleanRecipient);
      const conn1 = localStore?.connections?.get(`${kFrom}->${kTo}`);
      const conn2 = localStore?.connections?.get(`${kTo}->${kFrom}`);
      if (!conn1 || !conn2 || !conn1.isMutual || !conn2.isMutual) {
        return {
          success: false,
          delivered: false,
          persisted: false,
          error: (!conn1 && !conn2) ? 'Ausência de conexão bilateral autorizada' : 'Conexão unilateral não autorizada',
          statusCode: 403,
          code: (!conn1 && !conn2) ? 'NO_CONNECTION' : 'UNILATERAL_NOT_PERMITTED'
        };
      }
      if (localDurableMessages.has(effectiveMsgId)) {
        const ex = localDurableMessages.get(effectiveMsgId);
        if (ex.cleanSender.toLowerCase() !== cleanSender.toLowerCase() ||
            ex.cleanRecipient.toLowerCase() !== cleanRecipient.toLowerCase() ||
            ex.compatiblePayload !== compatiblePayload ||
            (ex.ivNonce || '') !== (ivNonce || '')) {
          return {
            success: false,
            delivered: false,
            persisted: false,
            error: 'Identificador em conflito com mensagem existente diferente',
            statusCode: 409
          };
        }
        return {
          success: true,
          delivered: ex.delivered,
          persisted: true,
          duplicate: true
        };
      }
    }

    // Resolve destinatário estritamente pela identidade técnica autorizada
    const targetKeys = new Set([kRecip]);
    for (const [presKey, peer] of activePresence.entries()) {
      if (keyId(peer.identityHash) === kRecip) {
        targetKeys.add(presKey);
      }
    }

    let deliveredWs = false;
    for (const tk of targetKeys) {
      if (!tk || tk === kSender) continue;

      const msgObj = {
        type: 'E2EE_MESSAGE_RECEIVED',
        messageId: effectiveMsgId,
        senderHash: cleanSender,
        senderAlias: resolvedSenderAlias,
        recipientHash: cleanRecipient,
        ciphertextPayload: compatiblePayload,
        ivNonce: ivNonce || '',
        isEphemeral: isEphemeral,
        timestamp: Date.now()
      };

      const targetWs = activeSockets.get(tk);
      if (targetWs && targetWs.readyState === WebSocket.OPEN) {
        try {
          targetWs.send(JSON.stringify(msgObj));
          deliveredWs = true;
        } catch (_) {}
      }

      // Armazena na fila pendente em memória para mensagens não-efêmeras
      if (!isEphemeral) {
        if (!pendingMessages.has(tk)) {
          pendingMessages.set(tk, []);
        }
        const queue = pendingMessages.get(tk);
        if (!queue.some(m => m.messageId === effectiveMsgId)) {
          queue.push(msgObj);
          if (queue.length > 50) queue.shift();
        }
      }
    }

    // Registra como processada com sucesso no cache de desenvolvimento local sem banco
    if (!isEphemeral) {
      if (!isDbConfigured()) {
        localDurableMessages.set(effectiveMsgId, {
          cleanSender,
          cleanRecipient,
          compatiblePayload,
          ivNonce: ivNonce || '',
          delivered: deliveredWs,
          timestamp: Date.now()
        });
      }
    }

    // Log resumido (ignora frames contínuos de chamada ou acks em lote para não poluir o console)
    const isContinuous = isEphemeral ||
                         payloadStr.startsWith('[CALL_AUDIO_FRAME:') ||
                         payloadStr.startsWith('[CALL_VIDEO_FRAME:') ||
                         compatiblePayload === '[READ_ACK]' ||
                         payloadStr.startsWith('[DELIVERY_ACK:');
    if (!isContinuous) {
      console.log(`[MSG] De ${resolvedSenderAlias} (${cleanSender}) -> ${cleanRecipient} (WS=${deliveredWs})`);
    }
    return { success: true, delivered: deliveredWs, persisted: persistedInDb };
  })();

  inFlightDeliveries.set(effectiveMsgId, {
    promise: deliveryPromise,
    senderIdentity: cleanSender,
    recipientIdentity: cleanRecipient,
    ciphertextPayload: compatiblePayload,
    ivNonce: ivNonce || '',
    isEphemeral: isEphemeral
  });
  deliveryPromise.finally(() => {
    inFlightDeliveries.delete(effectiveMsgId);
  });

  return deliveryPromise;
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
          // Aparência Online é independente do transporte de mensagens E2EE.
          // Em reconexões WS não ressuscitar visibilidade explicitamente desativada.
          visible: activePresence.get(kAuth)?.visible !== false,
          identityHash: authenticatedIdentity,
          alias: incomingAlias,
          intent: msg.intent || 'QUERO_CONVERSAR',
          publicKey: msg.publicKey || registeredDevicePublicKeys.get(kAuth) || null,
          lastSeen: Date.now()
        });

        ws.send(JSON.stringify({ type: 'AUTH_SUCCESS', identityHash: authenticatedIdentity }));
        console.log(`[WS] Dispositivo conectado para identidade: ${authenticatedIdentity} (${incomingAlias})`);

        // Entrega mensagens pendentes para este dispositivo sem apagá-las precipitadamente.
        // A remoção da fila ocorre sob confirmação explícita de processamento (ACK).
        let pending = [];
        const isDbConfiguredWs = () => (typeof process !== 'undefined' && Boolean(process.env && process.env.DATABASE_URL)) || (typeof isPostgres === 'function' && isPostgres());
        if (isDbConfiguredWs()) {
          if (isPostgres()) {
            try {
              let dbRes;
              try {
                dbRes = await query(
                  `SELECT id, sender_identity, recipient_identity, sender_alias, ciphertext_payload, iv_nonce, created_at
                   FROM e2ee_messages
                   WHERE LOWER(recipient_identity) = LOWER($1) AND is_delivered = FALSE AND (is_cancelled IS NULL OR is_cancelled = FALSE)
                   ORDER BY created_at ASC LIMIT 100`,
                  [kAuth]
                );
              } catch (errCol) {
                if (errCol.code === '42703' || String(errCol.message).includes('is_cancelled')) {
                  console.warn('[WS] Dreno de mensagens pendentes suspenso: coluna is_cancelled ausente no banco (fail-closed).');
                  return;
                }
                throw errCol;
              }
              pending = dbRes.rows.map(r => ({
                type: 'E2EE_MESSAGE_RECEIVED',
                messageId: r.id,
                senderHash: cleanId(r.sender_identity),
                senderAlias: r.sender_alias || 'Usuário',
                recipientHash: cleanId(r.recipient_identity),
                ciphertextPayload: r.ciphertext_payload,
                ivNonce: r.iv_nonce || '',
                isEphemeral: false,
                timestamp: r.created_at ? new Date(r.created_at).getTime() : Date.now()
              }));
            } catch (_) {
              pending = [];
            }
          } else {
            console.warn('[WS] Banco externo configurado indisponível; mensagens pendentes retidas até reconexão segura.');
            pending = [];
          }
        } else {
          pending = pendingMessages.get(kAuth) || [];
        }

        if (pending.length > 0) {
          pending.forEach((pm) => {
            ws.send(JSON.stringify(pm));
          });
        }

        // Envia lista atual de usuários presentes para o novo cliente (excluindo a si mesmo por id e por apelido)
        const now = Date.now();
        const existingPeers = Array.from(activePresence.values())
          .filter(p => keyId(p.identityHash) !== kAuth &&
                       (!p.alias || !incomingAlias || p.alias.toLowerCase() !== incomingAlias.toLowerCase()) &&
                       p.visible !== false && (now - p.lastSeen < 120000));

        const statusByPeer = await getConnectionStatusesForPeers(
          authenticatedIdentity,
          [...existingPeers.map(p => p.identityHash), ...activeSockets.keys()]
        );
        const enrichedPeers = existingPeers.map(p => {
          const status = statusByPeer.get(keyId(p.identityHash)) ||
            { isMutual: false, isMarkedByMe: false, isMarkingMe: false };
          return { ...p, isMutual: status.isMutual, isMarkedByMe: status.isMarkedByMe, isMarkingMe: status.isMarkingMe };
        });

        // Transmite no PRESENCE_SYNC apenas quem já possui relação mútua ou de marcação
        const mutualOrRelatedPeers = enrichedPeers.filter(p => p.isMutual || p.isMarkedByMe || p.isMarkingMe);
        ws.send(JSON.stringify({ type: 'PRESENCE_SYNC', peers: mutualOrRelatedPeers }));

        // Transmite anúncio de presença apenas para aparelhos que já possuem conexão mútua com este usuário
        const peerData = activePresence.get(kAuth);
        for (const [targetKey, client] of activeSockets.entries()) {
          if (client !== ws && client.readyState === WebSocket.OPEN) {
            const status = statusByPeer.get(keyId(targetKey));
            if (status?.isMutual && peerData?.visible !== false) {
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
        const { recipientHash, ciphertextPayload, ivNonce, messageId, isEphemeral, operation } = msg;
        if (!ciphertextPayload || (typeof ciphertextPayload === 'string' && ciphertextPayload.length > 25 * 1024 * 1024)) {
          return;
        }

        // Legado: chamadas em texto claro sem campo operation continuam como
        // sinalização, mas operação explícita MESSAGE_SEND nunca vira efêmera.
        const legacyCall = typeof ciphertextPayload === 'string' && (
          ciphertextPayload.startsWith('[CALL_AUDIO_FRAME:') ||
          ciphertextPayload.startsWith('[CALL_VIDEO_FRAME:') ||
          ciphertextPayload.startsWith('[CALL_INIT:') ||
          ciphertextPayload.startsWith('[CALL_ACCEPT]') ||
          ciphertextPayload.startsWith('[CALL_ACK]') ||
          ciphertextPayload.startsWith('[CALL_END]') ||
          ciphertextPayload.startsWith('[ICE_CANDIDATE:')
        );
        const declaredOp = String(operation || (
          Boolean(isEphemeral) || legacyCall
            ? (String(messageId || '').startsWith('call_frame_') ? 'CALL_STREAM' : 'CALL_SIGNAL')
            : 'MESSAGE_SEND'
        ));

        // Se a operação for MESSAGE_SEND, é incondicionalmente permanente!
        // Não é permitido classificar MESSAGE_SEND como efêmera.
        const isCallOp = declaredOp === 'CALL_STREAM' || declaredOp === 'CALL_SIGNAL';
        const isPlaintextCall = typeof ciphertextPayload === 'string' && (
          ciphertextPayload.startsWith('[CALL_AUDIO_FRAME:') ||
          ciphertextPayload.startsWith('[CALL_VIDEO_FRAME:') ||
          ciphertextPayload.startsWith('[CALL_INIT:') ||
          ciphertextPayload.startsWith('[CALL_ACCEPT]') ||
          ciphertextPayload.startsWith('[CALL_ACK]') ||
          ciphertextPayload.startsWith('[CALL_END]') ||
          ciphertextPayload.startsWith('[ICE_CANDIDATE:')
        );

        const isExplicitEphemeral = Boolean(isEphemeral) && isCallOp;
        const isEphemeralContract = isExplicitEphemeral || isPlaintextCall;

        const isStreamRate = isEphemeralContract && (
          declaredOp === 'CALL_STREAM' ||
          String(messageId || '').startsWith('call_frame_') ||
          (typeof ciphertextPayload === 'string' && (
            ciphertextPayload.startsWith('[CALL_AUDIO_FRAME:') ||
            ciphertextPayload.startsWith('[CALL_VIDEO_FRAME:')
          ))
        );
        if (!checkRateLimit(authenticatedIdentity, isStreamRate)) {
          return;
        }

        const kAuth = keyId(authenticatedIdentity);
        const msgId = messageId || `${authenticatedIdentity}_${Date.now()}_${Math.random()}`;

        // Reconhecimento de ACK de entrega: quando o destinatário responde com [DELIVERY_ACK:id],
        // o servidor confirma o recebimento e expurga a mensagem pendente confirmada.
        if (typeof ciphertextPayload === 'string' && ciphertextPayload.startsWith('[DELIVERY_ACK:')) {
          const ackTargetId = ciphertextPayload.replace(/^\[DELIVERY_ACK:/, '').replace(/\]$/, '').trim();
          await acknowledgePendingMessage(authenticatedIdentity, ackTargetId).catch(err => {
            console.error('[WS] Erro ao registrar ACK embutido:', err.message);
          });
        }

        const senderAlias = activePresence.get(kAuth)?.alias || 'Usuário';
        const deliveryResult = await deliverMessageToTarget(authenticatedIdentity, senderAlias, recipientHash, ciphertextPayload, ivNonce, msgId, isEphemeralContract, declaredOp);
        if (!deliveryResult.success) {
          try {
            ws.send(JSON.stringify({
              type: 'MESSAGE_ERROR',
              messageId: msgId,
              error: deliveryResult.error || 'Falha no processamento da mensagem',
              code: deliveryResult.code
            }));
          } catch (_) {}
        }
      }

      if (msg.type === 'MESSAGE_ACK' && authenticatedIdentity) {
        const { messageId } = msg;
        const ackResult = await acknowledgePendingMessage(authenticatedIdentity, messageId);
        try {
          ws.send(JSON.stringify({
            type: 'MESSAGE_ACK_CONFIRMED',
            messageId,
            success: ackResult.success,
            acknowledged: ackResult.acknowledged,
            duplicate: ackResult.duplicate || false,
            error: ackResult.error
          }));
        } catch (_) {}
        return;
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
app.post(['/api/app/version-update', '/admin/api/app-version'], requireAdminAuth, async (req, res) => {
  if (process.env.DATABASE_URL && !isPostgres())
    return res.status(503).json({error:'Banco indisponível; configuração não alterada.'});
  const {
    latestVersionCode,latestVersionName,minSupportedVersionCode,forceUpdate,
    playStoreUrl,releaseNotes
  }=req.body||{};
  const proposed={...appVersionConfig};
  if(latestVersionCode!==undefined)proposed.latestVersionCode=Number(latestVersionCode);
  if(latestVersionName!==undefined)proposed.latestVersionName=String(latestVersionName).slice(0,32);
  if(minSupportedVersionCode!==undefined)proposed.minSupportedVersionCode=Number(minSupportedVersionCode);
  if(forceUpdate!==undefined)proposed.forceUpdate=forceUpdate===true;
  if(playStoreUrl!==undefined)proposed.playStoreUrl=String(playStoreUrl).slice(0,400);
  if(releaseNotes!==undefined)proposed.releaseNotes=String(releaseNotes).slice(0,3000);
  if(!Number.isSafeInteger(proposed.latestVersionCode) ||
     !Number.isSafeInteger(proposed.minSupportedVersionCode) ||
     proposed.latestVersionCode<1 || proposed.minSupportedVersionCode<1 ||
     proposed.minSupportedVersionCode>proposed.latestVersionCode ||
     !/^https:\/\/play\.google\.com\//.test(proposed.playStoreUrl))
    return res.status(400).json({error:'Configuração de versão inválida.'});

  if(isPostgres()){
    try{
      await withTransaction(async run=>{
        await run('CREATE TABLE IF NOT EXISTS app_config (key VARCHAR(64) PRIMARY KEY, value TEXT)');
        await run(`INSERT INTO app_config (key,value) VALUES ('version_config',$1)
                   ON CONFLICT (key) DO UPDATE SET value=EXCLUDED.value`,
          [JSON.stringify(proposed)]);
      });
    }catch(_){return res.status(503).json({error:'Não foi possível persistir configuração.'});}
  }
  appVersionConfig=proposed;
  res.json({success:true,appVersion:proposed});
});

// Comprovação assinada da identidade nas operações HTTP (independente da sessão WS).
function digestHttpParts(parts) {
  return crypto.createHash('sha256')
    .update(parts.map(value=>String(value).length+':'+String(value)).join('|'),'utf8').digest('hex');
}
const httpNonceCache=new Map();
async function verifySignedHttp(req,res,operation,identity,alias='',tokenHash=''){
  if(!/^[A-F0-9]{16}$/.test(identity)){
    res.status(400).json({error:'Identidade inválida.'});return false;
  }
  if(process.env.DATABASE_URL&&!isPostgres()){
    res.status(503).json({error:'Autenticação indisponível.'});return false;
  }
  const hashes = Array.isArray(tokenHash) ? tokenHash : [tokenHash];
  let valid = false;
  let verifiedHash = null;
  for (const h of hashes) {
    try {
      if (await verifyActiveInviteProof(operation, identity, alias, '', h, req.body?.identityProof)) {
        valid = true;
        verifiedHash = h;
        break;
      }
    } catch (_) {
      res.status(503).json({error:'Banco de autenticação indisponível.'});
      return false;
    }
  }
  if(!valid){res.status(401).json({error:'Prova criptográfica inválida.'});return false;}
  if (req) req.verifiedTokenHash = verifiedHash;
  const key=identity+':'+operation+':'+req.body.identityProof.nonce,now=Date.now();
  if(httpNonceCache.size>8000){
    for(const [id,expires] of httpNonceCache){if(expires<=now)httpNonceCache.delete(id);}
  }
  if((httpNonceCache.get(key)||0)>now){
    res.status(409).json({error:'Solicitação já utilizada.'});return false;
  }
  if (process.env.DATABASE_URL) {
    try {
      const used=await query(
        'INSERT INTO recovery_proof_nonces(identity_id, operation, nonce) VALUES($1,$2,$3) ON CONFLICT DO NOTHING RETURNING nonce',
        [identity,operation,req.body.identityProof.nonce]);
      if (!isPostgres()) throw Error('Banco indisponível');
      if (used.rowCount!==1) {
        res.status(409).json({error:'Solicitação já utilizada.'});return false;
      }
    } catch (_) {
      res.status(503).json({error:'Proteção contra reutilização indisponível.'});return false;
    }
  }
  httpNonceCache.set(key,now+125000);
  return true;
}

/**
 * Heartbeat de Presença (Dispositivos ativos no radar)
 */
app.post('/api/presence/heartbeat', async (req, res) => {
  const { identityHash, alias, intent } = req.body;
  if (!identityHash) {
    return res.status(400).json({ error: 'identityHash obrigatório.' });
  }

  const cleanIdentity = cleanId(identityHash);
  const kId = keyId(cleanIdentity);
  const cleanAlias = String(alias || 'Usuário PessoasAqui').trim().slice(0,64);
  if(!await verifySignedHttp(req,res,'PRESENCE_HEARTBEAT',cleanIdentity,cleanAlias,
      digestHttpParts([String(intent||'')])))return;

  // Remove qualquer presença fantasma com o mesmo apelido apenas se pertencer a outro ID
  for (const [idKey, peer] of activePresence.entries()) {
    if (keyId(idKey) !== kId && keyId(peer.identityHash) !== kId &&
        peer.alias && cleanAlias && peer.alias.toLowerCase() === cleanAlias.toLowerCase()) {
      activePresence.delete(idKey);
    } else if (idKey !== kId && keyId(idKey) === kId) {
      activePresence.delete(idKey);
    }
  }

  // HTTP heartbeat mantém filas/mensagens; não torna a UI Online se o
  // Android explicitamente informou que sua atividade saiu de primeiro plano.
  const previousVisibility = activePresence.get(kId)?.visible !== false;
  activePresence.set(kId, {
    identityHash: cleanIdentity,
    alias: cleanAlias,
    intent: intent || 'QUERO_CONVERSAR',
    visible: previousVisibility,
    lastSeen: Date.now()
  });

  res.json({ success: true, activePresenceCount: activePresence.size });
});

/**
 * Estado visual de atividade do app, autenticado por prova de posse.
 * Não fecha WebSocket, não apaga presença de transporte e não mexe no E2EE.
 * Um cliente antigo que não conhece a rota continua seguindo o legado.
 */
app.post('/api/presence/visibility', async (req, res) => {
  const identity = cleanId(req.body?.identityHash);
  const visibility = req.body?.visible;
  if (!identity || typeof visibility !== 'boolean') {
    return res.status(400).json({ error: 'Identidade e visibilidade obrigatórias.' });
  }
  const alias = String(req.body?.alias || '').trim().slice(0, 64);
  if (!await verifySignedHttp(req, res, 'PRESENCE_VISIBILITY', identity, alias,
       digestHttpParts([String(visibility)]))) return;
  const key = keyId(identity);
  const entry = activePresence.get(key);
  // Não ressuscitar sessão inexistente apenas com um comando de visibilidade.
  if (!entry) return res.json({ success: true, visible: false });
  if (entry.visible === visibility) return res.json({ success: true, visible: visibility });
  entry.visible = visibility;
  if (visibility) entry.lastSeen = Date.now();
  for (const [peerId, client] of activeSockets.entries()) {
    if (peerId === key || client.readyState !== WebSocket.OPEN) continue;
    try {
      const relationship = await getConnectionStatus(identity, peerId);
      if (!relationship.isMutual) continue;
      client.send(JSON.stringify(visibility
        ? { type: 'PEER_ONLINE', peer: { ...entry, isMutual: true } }
        : { type: 'PEER_OFFLINE', identityHash: identity }));
    } catch (err) {
      console.warn('[Presence] Falha na publicação de visibilidade:', err.message);
    }
  }
  res.json({ success: true, visible: visibility });
});

/**
 * Listagem de Pessoas Online no Radar de Proximidade
 */
app.get('/api/presence/nearby',(_req,res)=>res.status(401).json({error:'Consulta exige autenticação.'}));
app.post('/api/presence/nearby', async (req, res) => {
  const myHash = cleanId(req.body?.myIdentity);
  const myAliasRaw = String(req.body?.myAlias || '').trim().slice(0,64);
  if(!await verifySignedHttp(req,res,'PRESENCE_NEARBY',myHash,myAliasRaw))return;
  const kMyHash = keyId(myHash);
  const myAlias = myAliasRaw.toLowerCase();
  const now = Date.now();
  const peers = Array.from(activePresence.values())
    .filter(p => keyId(p.identityHash) !== kMyHash &&
                 (!myAlias || !p.alias || p.alias.trim().toLowerCase() !== myAlias) &&
                 p.visible !== false && (now - p.lastSeen < 120000));

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
app.post('/api/messages/send', async (req, res) => {
  const { senderHash, recipientHash, ciphertextPayload, ivNonce, senderAlias, messageId, operation } = req.body;
  if (!recipientHash || !ciphertextPayload) {
    return res.status(400).json({ error: 'recipientHash e ciphertextPayload são obrigatórios.' });
  }

  const cleanSender = cleanId(senderHash);
  const cleanRecipient = cleanId(recipientHash);
  const declaredEphemeral = Boolean(req.body?.isEphemeral);
  const declaredOperation = String(operation || (declaredEphemeral ? (String(messageId || '').startsWith('call_frame_') ? 'CALL_STREAM' : 'CALL_SIGNAL') : 'MESSAGE_SEND'));
  const ephemContract = declaredEphemeral ? 'ephemeral' : 'durable';

  // Digest V2: Vincula remetente, destinatário, ciphertext, iv, messageId, alias, contrato de efemeridade e operação
  const digestV2 = digestHttpParts([
    String(senderHash || ''), String(recipientHash || ''), String(ciphertextPayload || ''),
    String(ivNonce || ''), String(messageId || ''), String(senderAlias || ''),
    ephemContract, declaredOperation
  ]);

  // Digest V1 (Legado): 6 partes sem efemeridade nem tipo de operação
  const digestV1 = digestHttpParts([
    String(senderHash || ''), String(recipientHash || ''), String(ciphertextPayload || ''),
    String(ivNonce || ''), String(messageId || ''), String(senderAlias || '')
  ]);

  if (!await verifySignedHttp(req, res, 'MESSAGE_SEND', cleanSender, cleanRecipient, [digestV2, digestV1])) {
    return;
  }

  const isV2Verified = (req.verifiedTokenHash === digestV2);

  // Se verificado apenas pelo contrato legado v1 (onde isEphemeral NÃO foi assinado):
  // O backend NUNCA permite que assinatura legada sem efemeridade seja tratada como efêmera!
  // É estritamente FORÇADO a persistência durável (Fail-Closed).
  const isEphemeralContract = isV2Verified ? declaredEphemeral : false;
  const effectiveOperation = isV2Verified ? declaredOperation : 'MESSAGE_SEND';

  const isPlaintextCall = typeof ciphertextPayload === 'string' && (
    ciphertextPayload.startsWith('[CALL_AUDIO_FRAME:') ||
    ciphertextPayload.startsWith('[CALL_VIDEO_FRAME:') ||
    ciphertextPayload.startsWith('[CALL_INIT:') ||
    ciphertextPayload.startsWith('[CALL_ACCEPT]') ||
    ciphertextPayload.startsWith('[CALL_ACK]') ||
    ciphertextPayload.startsWith('[CALL_END]') ||
    ciphertextPayload.startsWith('[ICE_CANDIDATE:')
  );

  // Mesmo um cliente legado autenticado por Digest V1 não pode converter
  // MESSAGE_SEND em efêmera por um prefixo de texto claro fornecido no payload.
  // Isso preserva a política fail-closed para durabilidade.
  const isEffectiveEphemeral = effectiveOperation !== 'MESSAGE_SEND' &&
    (isEphemeralContract || isPlaintextCall);
  const isStreamRate = isEffectiveEphemeral && (
    effectiveOperation === 'CALL_STREAM' ||
    String(messageId || '').startsWith('call_frame_') ||
    (typeof ciphertextPayload === 'string' && (
      ciphertextPayload.startsWith('[CALL_AUDIO_FRAME:') ||
      ciphertextPayload.startsWith('[CALL_VIDEO_FRAME:')
    ))
  );

  if (!checkRateLimit(cleanSender, isStreamRate)) {
    return res.status(429).json({ error: 'Muitas requisições. Limite de envio por segundo excedido.' });
  }

  const msgId = messageId || `${cleanSender}_${Date.now()}_${Math.random()}`;

  const deliveryResult = await deliverMessageToTarget(cleanSender, senderAlias, recipientHash, ciphertextPayload, ivNonce, msgId, isEffectiveEphemeral, effectiveOperation);
  if (!deliveryResult.success) {
    const statusCode = deliveryResult.statusCode || (deliveryResult.error === 'Database persistence failed' ? 503 : 400);
    return res.status(statusCode).json({
      error: deliveryResult.error === 'Database persistence failed'
        ? 'Erro de persistência durável no banco de dados.'
        : (deliveryResult.error || 'Falha no envio da mensagem.'),
      details: deliveryResult.error,
      code: deliveryResult.code
    });
  }
  res.json({
    success: true,
    delivered: deliveryResult.delivered,
    persisted: deliveryResult.persisted,
    duplicate: deliveryResult.duplicate || false
  });
});

/**
 * Confirmação de Entrega (ACK) de Mensagens Pendentes via HTTP
 */
app.post('/api/messages/ack', async (req, res) => {
  const rawId = req.body?.identityHash;
  const messageId = req.body?.messageId;
  if (!rawId || !messageId) {
    return res.status(400).json({ error: 'identityHash e messageId obrigatórios.' });
  }
  const cleanKey = keyId(rawId);
  if (!await verifySignedHttp(req, res, 'MESSAGE_ACK', cleanId(rawId))) return;
  const ackResult = await acknowledgePendingMessage(cleanKey, messageId);
  if (!ackResult.success) {
    const statusCode = ackResult.statusCode || 400;
    return res.status(statusCode).json({
      success: false,
      acknowledged: false,
      error: ackResult.error || 'Falha ao processar confirmação de mensagem'
    });
  }
  res.json({
    success: true,
    acknowledged: messageId,
    removed: ackResult.acknowledged,
    duplicate: ackResult.duplicate || false
  });
});

/**
 * Consulta de Mensagens Pendentes via HTTP
 */
app.get('/api/messages/pending',(_req,res)=>res.status(401).json({error:'A leitura requer assinatura. Atualize o aplicativo.'}));
app.post('/api/messages/pending', async (req, res) => {
  const rawId = req.body?.identityHash;
  if (!rawId) {
    return res.status(400).json({ error: 'identityHash obrigatório.' });
  }
  const cleanKey = keyId(rawId);
  if(!await verifySignedHttp(req,res,'MESSAGE_PENDING',cleanId(rawId)))return;
  if (Array.isArray(req.body?.ackMessageIds)) {
    for (const mid of req.body.ackMessageIds) {
      await acknowledgePendingMessage(cleanKey, mid);
    }
  }

  // ATENÇÃO: clearOnRead foi descontinuado para eliminar perda silenciosa de mensagens.
  // A leitura da fila não confirma entrega nem processamento.

  let pending = [];
  const isDbConfiguredHttp = () => (typeof process !== 'undefined' && Boolean(process.env && process.env.DATABASE_URL)) || (typeof isPostgres === 'function' && isPostgres());
  if (isDbConfiguredHttp()) {
    if (!isPostgres()) {
      return res.status(503).json({ success: false, error: 'Banco de mensagens indisponível. Fila protegida.' });
    }
    try {
      let dbRes;
      try {
        dbRes = await query(
          `SELECT id, sender_identity, recipient_identity, sender_alias, ciphertext_payload, iv_nonce, created_at
           FROM e2ee_messages
           WHERE LOWER(recipient_identity) = LOWER($1) AND is_delivered = FALSE AND (is_cancelled IS NULL OR is_cancelled = FALSE)
           ORDER BY created_at ASC LIMIT 100`,
          [cleanKey]
        );
      } catch (errCol) {
        if (errCol.code === '42703' || String(errCol.message).includes('is_cancelled')) {
          console.error('[DB] Consulta de pendências rejeitada: coluna is_cancelled ausente no banco (fail-closed).');
          return res.status(503).json({ success: false, error: 'Estrutura de banco incompatível: verificação de cancelamento indisponível (fail-closed).' });
        }
        throw errCol;
      }
      pending = dbRes.rows.map(r => ({
        type: 'E2EE_MESSAGE_RECEIVED',
        messageId: r.id,
        senderHash: cleanId(r.sender_identity),
        senderAlias: r.sender_alias || 'Usuário',
        recipientHash: cleanId(r.recipient_identity),
        ciphertextPayload: r.ciphertext_payload,
        ivNonce: r.iv_nonce || '',
        isEphemeral: false,
        timestamp: r.created_at ? new Date(r.created_at).getTime() : Date.now()
      }));
    } catch (e) {
      console.error('[DB] Erro ao consultar mensagens pendentes no PostgreSQL:', e.message);
      return res.status(503).json({ success: false, error: 'Banco de dados indisponível para consulta autoritativa de pendências.' });
    }
  } else {
    pending = pendingMessages.get(cleanKey) || [];
  }

  res.json({ success: true, messages: pending });
});

/**
 * Registro / Inicialização da Identidade Técnica Criptográfica (Sem dados tradicionais)
 */
app.post('/api/identities/register', async (req, res) => {
  const identity=cleanId(req.body?.identityHash);
  const alias=String(req.body?.alias||'Eu').trim().slice(0,64);
  if(!/^[A-F0-9]{16}$/.test(identity)||!alias){
    return res.status(400).json({error:'Identidade ou apelido inválido.'});
  }
  if(process.env.DATABASE_URL && !isPostgres()){
    return res.status(503).json({error:'Registro seguro exige banco disponível.'});
  }
  let verified=false;
  try{verified=await verifyActiveInviteProof('IDENTITY_REGISTER',identity,alias,'','',req.body?.identityProof);}
  catch(_){return res.status(503).json({error:'Autenticação indisponível.'});}
  if(!verified)return res.status(401).json({error:'Assinatura da chave privada obrigatória.'});
  const proof=req.body.identityProof;
  // Campo de PIN legado: utilizar verificador aleatório, nunca o padrão fixo 0000.
  // O usuário mantém seu próprio PIN no aparelho/cofre, separado deste cadastro.
  const pinHash=hashPin(crypto.randomBytes(32).toString('hex'));
  if(process.env.DATABASE_URL){
    try{
      await withTransaction(async run=>{
        const nonce=await run(
          'INSERT INTO recovery_proof_nonces(identity_id,operation,nonce) VALUES($1,$2,$3) ON CONFLICT DO NOTHING RETURNING nonce',
          [identity,'IDENTITY_REGISTER',proof.nonce]);
        if(nonce.rowCount!==1)throw Object.assign(Error('Prova já utilizada'),{status:409});
        await run(
          `INSERT INTO identities (id,public_key_ed25519,public_key_x25519,pin_salted_hash,alias)
           VALUES ($1,$2,$3,$4,$5)
           ON CONFLICT(id) DO UPDATE SET alias=EXCLUDED.alias, updated_at=NOW()`,
          [identity,proof.publicKey,'',pinHash,alias]);
        // A chave P-256 usada para ECDSA/ECDH é ligada ao ID em tabela
        // durável. Nunca substituir chave ativa por registro não autorizado:
        // rotação ocorre exclusivamente no CLAIM familiar transacional.
        const bound=await run(
          `INSERT INTO identity_key_bindings(identity_id,public_key_spki,generation)
           VALUES($1,$2,1) ON CONFLICT(identity_id) DO NOTHING RETURNING public_key_spki`,
          [identity,proof.publicKey]);
        if (bound.rowCount!==1) {
          const active=await run(
            'SELECT public_key_spki FROM identity_key_bindings WHERE identity_id=$1',
            [identity]);
          if (active.rows[0]?.public_key_spki!==proof.publicKey)
            throw Object.assign(Error('Chave ativa divergente'),{status:409});
        }
      });
    }catch(err){
      if(err.status===409)return res.status(409).json({error:err.message==='Chave ativa divergente'?'Chave não autorizada.':'Prova já utilizada.'});
      // Diagnóstico sem registrar chaves privadas, assinaturas nem dados pessoais.
      console.error('[DB] Registro de identidade não persistido:', {
        code: err.code || 'UNKNOWN', constraint: err.constraint || null,
        table: err.table || null
      });
      return res.status(503).json({error:'Registro não confirmado no banco.'});
    }
  }
  localStore.identities.set(identity,{id:identity,publicKeySpki:proof.publicKey,alias});
  return res.json({success:true,identityHash:identity});
});
/**
 * Consulta autenticada da chave pública P-256 ativa de um interlocutor.
 * Chave rotacionada por recuperação traz geração monotônica. TLS e prova
 * do solicitante obrigatórios; o Android só deve fixá-la após esta resposta.
 */
app.post('/api/identities/key', async (req,res)=>{
  const requester=cleanId(req.body?.requesterIdentity);
  const target=cleanId(req.body?.targetIdentity);
  if(!/^[A-F0-9]{16}$/.test(target))
    return res.status(400).json({error:'Identidade alvo inválida.'});
  if(!await verifySignedHttp(req,res,'IDENTITY_KEY_LOOKUP',requester,target))return;
  if(process.env.DATABASE_URL&&!isPostgres())
    return res.status(503).json({error:'Chaves indisponíveis.'});
  try {
    if(isPostgres()){
      const found=await query(
        `SELECT k.public_key_spki,k.generation
           FROM identity_key_bindings k JOIN identities i ON i.id=k.identity_id
           WHERE k.identity_id=$1 AND COALESCE(i.is_banned,FALSE)=FALSE`,[target]);
      if(!isPostgres())throw Error('Banco indisponível');
      if(found.rowCount!==1)return res.status(404).json({error:'Chave não registrada.'});
      return res.json({success:true,identityHash:target,
        publicKey:found.rows[0].public_key_spki,generation:Number(found.rows[0].generation)});
    }
    const stored=localStore.identities.get(target);
    if(!stored?.publicKeySpki)return res.status(404).json({error:'Chave não registrada.'});
    return res.json({success:true,identityHash:target,publicKey:stored.publicKeySpki,generation:1});
  }catch(_){return res.status(503).json({error:'Falha na consulta de chaves.'});}
});

/**
 * Consulta de Status de Conexões (Mútuas e Marcações)
 * Isolamento Estrito: Retorna estritamente conexões onde o solicitante (myIdentity) seja participante.
 */
app.get('/api/connections/status',(_req,res)=>res.status(401).json({error:'Consulta exige autenticação.'}));
app.post('/api/connections/status', async (req, res) => {
  const myId = cleanId(req.body?.myIdentity);
  if (!myId) {
    return res.status(400).json({ error: 'myIdentity é obrigatório.' });
  }
  if(!await verifySignedHttp(req,res,'CONNECTION_STATUS',myId))return;

  if (process.env.DATABASE_URL && !isPostgres()) {
    return res.status(503).json({success:false,error:'Banco de conexões indisponível.'});
  }

  const kMyId = keyId(myId);
  let markedByMe = [];
  let markingMe = [];
  let mutual = [];
  const pairRevisions = {};
  const useRevisions = revisionsEnabled();
  if (useRevisions && !isPostgres()) {
    return res.status(503).json({success:false,error:'Revisões de conexões indisponíveis.'});
  }

  // Em produção, somente o PostgreSQL é autoritativo: nunca misturar
  // conexões em RAM de outro ciclo do servidor com o snapshot durável.
  if (isPostgres()) {
    try {
      // UNION ALL retorna ambos os conjuntos no MESMO snapshot MVCC do banco.
      const result = await query(
        useRevisions
          ? `SELECT 'connection'::text AS row_kind, from_identity, to_identity,
                    is_mutual, NULL::text AS pair_key, NULL::text AS current_state,
                    NULL::bigint AS revision, NULL::varchar(64) AS identity_low,
                    NULL::varchar(64) AS identity_high
             FROM connections
             WHERE LOWER(from_identity) = LOWER($1) OR LOWER(to_identity) = LOWER($1)
             UNION ALL
             SELECT 'revision'::text AS row_kind, NULL::varchar(64), NULL::varchar(64),
                    NULL::boolean, pair_key, current_state, revision, identity_low, identity_high
             FROM connection_revisions
             WHERE LOWER(identity_low) = LOWER($1) OR LOWER(identity_high) = LOWER($1)`
          : `SELECT from_identity, to_identity, is_mutual FROM connections
             WHERE LOWER(from_identity) = LOWER($1) OR LOWER(to_identity) = LOWER($1)`,
        [myId]
      );
      const directions = new Map();
      for (const row of result.rows) {
        if (useRevisions && row.row_kind === 'revision') {
          const low = cleanId(row.identity_low);
          const high = cleanId(row.identity_high);
          const peer = keyId(low) === kMyId ? high : low;
          const revision = Number(row.revision);
          if (!Number.isSafeInteger(revision) || revision < 1) {
            throw new Error('Revisao do par invalida');
          }
          if (peer && keyId(peer) !== kMyId) {
            pairRevisions[peer] = { revision, state: row.current_state };
          }
          continue;
        }
        const from = cleanId(row.from_identity);
        const to = cleanId(row.to_identity);
        const kFrom = keyId(from);
        const kTo = keyId(to);
        if (kFrom === kMyId && kTo !== kMyId) {
          markedByMe.push(to);
          const state = directions.get(kTo) || { outgoing: false, incoming: false };
          state.outgoing = row.is_mutual === true;
          directions.set(kTo, state);
        } else if (kTo === kMyId && kFrom !== kMyId) {
          markingMe.push(from);
          const state = directions.get(kFrom) || { outgoing: false, incoming: false };
          state.incoming = row.is_mutual === true;
          directions.set(kFrom, state);
        }
      }
      const own = new Set(markedByMe.map(keyId));
      const incoming = new Set(markingMe.map(keyId));
      for (const [peer, state] of directions) {
        if (state.outgoing && state.incoming && own.has(peer) && incoming.has(peer)) {
          const partner = markedByMe.find(id => keyId(id) === peer);
          if (partner) mutual.push(partner);
        }
      }
    } catch (e) {
      console.error('[DB] Erro ao buscar connections:', e.message);
      return res.status(503).json({ success: false, error: 'Estado de conexões indisponível.' });
    }
  } else if (!process.env.DATABASE_URL) {
    // Apenas desenvolvimento explicitamente sem banco configurado.
    for (const val of localStore.connections.values()) {
      const f = cleanId(val.fromIdentity);
      const t = cleanId(val.toIdentity);
      const kF = keyId(f);
      const kT = keyId(t);
      if (kF === kMyId && kT !== kMyId) markedByMe.push(t);
      if (kT === kMyId && kF !== kMyId) markingMe.push(f);
    }
    for (const peer of markedByMe) {
      const kPeer = keyId(peer);
      if (!markingMe.some(id => keyId(id) === kPeer)) continue;
      if (localStore.connections.get(`${kMyId}->${kPeer}`)?.isMutual === true &&
          localStore.connections.get(`${kPeer}->${kMyId}`)?.isMutual === true) {
        mutual.push(peer);
      }
    }
  } else {
    return res.status(503).json({ success: false, error: 'Banco de conexões indisponível.' });
  }

  res.json({ success: true, markedByMe, markingMe, mutual,
    ...(useRevisions ? { pairRevisions } : {}) });
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
  if ((revisionsEnabled() || process.env.DATABASE_URL) && !isPostgres()) {
    return res.status(503).json({error:'Banco de vínculos indisponível.'});
  }
  if(!await authorizeConnectionWrite(req,res,'CONNECTION_MARK',from,to))return;

  // Uma sessão WS autenticada não implica uma linha em identities. A FK de
  // connections precisa de ambas as identidades registradas e vinculadas a
  // chaves ativas. Não criar registros fictícios nem contornar a FK.
  if (isPostgres()) {
    try {
      const registered = await query(
        'SELECT id FROM identities WHERE id = ANY($1::varchar[])',
        [[from, to]]
      );
      const found = new Set(registered.rows.map(row => cleanId(row.id)));
      if (!found.has(from) || !found.has(to)) {
        return res.status(409).json({
          success: false,
          error: !found.has(from)
            ? 'Sua identidade não está cadastrada. Registre novamente e tente marcar.'
            : 'A identidade do destinatário ainda não está cadastrada no servidor.',
          code: !found.has(from) ? 'SENDER_IDENTITY_NOT_REGISTERED' : 'TARGET_IDENTITY_NOT_REGISTERED'
        });
      }
    } catch (e) {
      console.error('[DB] Falha ao conferir identidades da marcação:', e.message);
      return res.status(503).json({success:false,error:'Cadastro de identidades temporariamente indisponível.'});
    }
  }

  if (process.env.DATABASE_URL && !isPostgres()) {
    return res.status(503).json({success:false,error:'Banco de conexões indisponível.'});
  }

  // Verifica se o contato de destino já havia marcado a origem
  let isReciprocal = false;
  let connectionRevision = null;

  const kFrom = keyId(from);
  const kTo = keyId(to);

  const reverseKey = `${kTo}->${kFrom}`;
  if (!isPostgres() && localStore.connections.has(reverseKey)) {
    isReciprocal = true;
  }

  if (isPostgres()) {
    try {
      // Ambos os sentidos da relação compartilham o mesmo advisory lock.
      // INSERT + UPDATE devem ser um único evento atômico, inclusive quando
      // os dois aparelhos marcam simultaneamente.
      const txRunner = withTransaction;
      const transactionResult = await txRunner(async (txQuery) => {
        await txQuery('SELECT pg_advisory_xact_lock(hashtext($1))', [pairKey(from, to)]);
        const existing = await txQuery(
          `SELECT 1 FROM connections
           WHERE LOWER(from_identity) = LOWER($1) AND LOWER(to_identity) = LOWER($2)`,
          [to, from]
        );
        const reciprocal = existing.rows.length > 0;
        await txQuery(
          `INSERT INTO connections (from_identity, to_identity, is_mutual)
           VALUES ($1, $2, $3)
           ON CONFLICT (from_identity, to_identity)
           DO UPDATE SET is_mutual = EXCLUDED.is_mutual`,
          [from, to, reciprocal]
        );
        if (reciprocal) {
          await txQuery(
            `UPDATE connections SET is_mutual = TRUE
             WHERE (LOWER(from_identity) = LOWER($1) AND LOWER(to_identity) = LOWER($2))
                OR (LOWER(from_identity) = LOWER($2) AND LOWER(to_identity) = LOWER($1))`,
            [from, to]
          );
        }
        let revision = null;
        if (revisionsEnabled()) {
          const directions = await txQuery(
            `SELECT from_identity, to_identity FROM connections
             WHERE (LOWER(from_identity) = LOWER($1) AND LOWER(to_identity) = LOWER($2))
                OR (LOWER(from_identity) = LOWER($2) AND LOWER(to_identity) = LOWER($1))`,
            [from, to]
          );
          const forward = directions.rows.some(row => keyId(row.from_identity) === kFrom);
          const backward = directions.rows.some(row => keyId(row.from_identity) === kTo);
          const state = stateFromDirections(from, to, forward, backward);
          revision = await advancePairRevision(txQuery, from, to, state,
            state === 'MUTUAL' ? 'MUTUAL' : 'MARK');
        }
        return { reciprocal, revision };
      });
      isReciprocal = transactionResult.reciprocal;
      connectionRevision = transactionResult.revision;
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
  if (isReciprocal && typeof revokedConnections !== 'undefined') {
    revokedConnections.delete(pairKey(from, to));
  }

  const myAlias = senderAlias || activePresence.get(kFrom)?.alias || 'Alguém';

  // Notificações realtime via WebSocket e fila de mensagens pendentes:
  if (isReciprocal) {
    const targetWs = activeSockets.get(kTo);
    const msgForTo = {
      type: 'MUTUAL_CONNECTION_ESTABLISHED',
      partnerIdentity: from,
      partnerAlias: myAlias,
      ...(connectionRevision !== null ? { revision: connectionRevision } : {}),
      timestamp: Date.now()
    };
    if (targetWs && targetWs.readyState === WebSocket.OPEN) {
      targetWs.send(JSON.stringify(msgForTo));
    } else {
      // Contato offline: estado do vínculo só é recuperado via /connections/status assinado.
    }

    const callerWs = activeSockets.get(kFrom);
    if (callerWs && callerWs.readyState === WebSocket.OPEN) {
      callerWs.send(JSON.stringify({
        type: 'MUTUAL_CONNECTION_ESTABLISHED',
        partnerIdentity: to,
        partnerAlias: activePresence.get(kTo)?.alias || 'Conexão',
        ...(connectionRevision !== null ? { revision: connectionRevision } : {}),
        timestamp: Date.now()
      }));
    }
  } else {
    const targetWs = activeSockets.get(kTo);
    const msgForTo = {
      type: 'PEER_MARKED_YOU',
      partnerIdentity: from,
      partnerAlias: myAlias,
      ...(connectionRevision !== null ? { revision: connectionRevision } : {}),
      timestamp: Date.now()
    };
    if (targetWs && targetWs.readyState === WebSocket.OPEN) {
      targetWs.send(JSON.stringify(msgForTo));
    } else {
      // Contato offline: estado do vínculo só é recuperado via /connections/status assinado.
    }
  }

  res.json({
    success: true,
    isMutualConnection: isReciprocal,
    ...(connectionRevision !== null ? { revision: connectionRevision } : {}),
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
  if ((revisionsEnabled() || process.env.DATABASE_URL) && !isPostgres()) {
    return res.status(503).json({error:'Banco de vínculos indisponível.'});
  }
  if(!await authorizeConnectionWrite(req,res,'CONNECTION_UNMARK',from,to))return;

  if (process.env.DATABASE_URL && !isPostgres()) {
    return res.status(503).json({success:false,error:'Banco de conexões indisponível.'});
  }

  const kFrom = keyId(from);
  const kTo = keyId(to);
  let connectionRevision = null;

  const isDbConfigured = () => (typeof process !== 'undefined' && Boolean(process.env && process.env.DATABASE_URL)) || (typeof isPostgres === 'function' && isPostgres());

  if (isDbConfigured() && !isPostgres()) {
    return res.status(503).json({ success: false, error: 'Banco de dados indisponível para revogação durável.' });
  }

  // REVOGAÇÃO BILATERAL ATÔMICA EM TRANSAÇÃO ÚNICA:
  // DELETE em connections e UPDATE de cancelamento em e2ee_messages sob o mesmo BEGIN ... COMMIT
  if (isPostgres()) {
    try {
      const txRunner = (typeof withTransaction === 'function') ? withTransaction : async (cb) => cb(query);
      connectionRevision = await txRunner(async (txQuery) => {
        // A revogação usa o mesmo lock transacional de /connections/mark,
        // para que mark e unmark do mesmo par não sejam intercalados.
        await txQuery('SELECT pg_advisory_xact_lock(hashtext($1))', [pairKey(from, to)]);
        // 1. Exclusão bilateral dos vínculos na tabela connections
        await txQuery(
          `DELETE FROM connections
           WHERE (LOWER(from_identity) = LOWER($1) AND LOWER(to_identity) = LOWER($2))
              OR (LOWER(from_identity) = LOWER($2) AND LOWER(to_identity) = LOWER($1))`,
          [from, to]
        );

        // 2. Cancelamento atômico das mensagens pendentes não entregues
        // Mensagens já entregues (is_delivered = TRUE) permanecem intocadas!
        await txQuery(
          `UPDATE e2ee_messages
           SET is_cancelled = TRUE, cancelled_at = NOW()
           WHERE is_delivered = FALSE
             AND (is_cancelled IS NULL OR is_cancelled = FALSE)
             AND ((LOWER(sender_identity) = LOWER($1) AND LOWER(recipient_identity) = LOWER($2))
               OR (LOWER(sender_identity) = LOWER($2) AND LOWER(recipient_identity) = LOWER($1)))`,
          [from, to]
        );
        if (revisionsEnabled()) {
          return advancePairRevision(txQuery, from, to, 'NONE', 'UNMARK');
        }
        return null;
      });
    } catch (e) {
      console.error('[DB] Erro na transação atômica de revogação no PostgreSQL:', e.message);
      return res.status(503).json({ success: false, error: 'Revogação não confirmada no banco. Transação abortada com rollback.' });
    }
  }

  // SOMENTE APÓS A CONFIRMAÇÃO DO COMMIT:
  // 1. Atualização dos estados em memória RAM
  localStore.connections.delete(`${kFrom}->${kTo}`);
  localStore.connections.delete(`${kTo}->${kFrom}`);
  if (typeof revokedConnections !== 'undefined') {
    revokedConnections.add(pairKey(from, to));
  }
  if (typeof purgePendingMessagesRam === 'function') {
    purgePendingMessagesRam(from, to);
  }

  const myAlias = senderAlias || activePresence.get(kFrom)?.alias || 'Alguém';
  const targetWs = activeSockets.get(kTo);
  const msgForTo = {
    type: 'MUTUAL_CONNECTION_BROKEN',
    partnerIdentity: from,
    partnerAlias: myAlias,
    ...(connectionRevision !== null ? { revision: connectionRevision } : {}),
    timestamp: Date.now()
  };
  if (targetWs && targetWs.readyState === WebSocket.OPEN) {
    targetWs.send(JSON.stringify(msgForTo));
  } else {
    // Contato offline: estado do vínculo só é recuperado via /connections/status assinado.
  }

  res.json({
    success: true,
    ...(connectionRevision !== null ? { revision: connectionRevision } : {}),
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
  let inviteConnectionRevision = null;
  if (inviteDbRequired) {
    try {
      // Versioned writes MUST share the pair advisory lock and transaction used
      // by /connections/mark and /connections/unmark. An unversioned invite
      // redemption would otherwise bypass monotonic revisions and revive peers.
      const useRevisions = typeof revisionsEnabled === 'function' && revisionsEnabled();
      const claimSql = `WITH claimed AS (
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
                (SELECT COUNT(*) FROM linked) AS linked_count FROM claimed`;
      const claimArgs = [inviteId, token, receiver, tokenDigest(token)];
      let claim;
      if (useRevisions) {
        if (!isPostgres() || typeof withTransaction !== 'function') {
          return res.status(503).json({ error: 'Banco de revisões indisponível.' });
        }
        const outcome = await withTransaction(async (txQuery) => {
          // Read the immutable invite creator before locking the pair. The
          // claim below remains the sole authority for expiry/one-time usage.
          const preliminary = await txQuery(
            'SELECT sender_identity FROM invites WHERE id = $1', [inviteId]
          );
          if (preliminary.rowCount !== 1) return { claim: { rowCount: 0, rows: [] }, revision: null };
          const inviteSender = cleanId(preliminary.rows[0].sender_identity);
          if (!inviteSender || keyId(inviteSender) === keyId(receiver)) {
            return { claim: { rowCount: 0, rows: [] }, revision: null };
          }
          await txQuery('SELECT pg_advisory_xact_lock(hashtext($1))', [pairKey(inviteSender, receiver)]);
          const result = await txQuery(claimSql, claimArgs);
          if (result.rowCount !== 1 || Number(result.rows[0]?.linked_count) !== 2) {
            return { claim: result, revision: null };
          }
          if (keyId(result.rows[0].sender_identity) !== keyId(inviteSender)) {
            throw new Error('Invite creator changed during redemption');
          }
          const revision = await advancePairRevision(
            txQuery, inviteSender, receiver, 'MUTUAL', 'MUTUAL'
          );
          return { claim: result, revision };
        });
        claim = outcome.claim;
        inviteConnectionRevision = outcome.revision;
      } else {
        // Legacy route retained byte-for-byte at the SQL contract level until
        // feature activation, so existing deployments and clients still work.
        claim = await query(claimSql, claimArgs);
      }
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
  if (typeof revokedConnections !== 'undefined') {
    revokedConnections.delete(pairKey(sender, receiver));
  }

  let senderPublicKey = null;
  let senderGeneration = 1;
  let receiverPublicKey = req.body?.identityProof?.publicKey || null;

  if (inviteDbRequired) {
    try {
      const sKey = await query('SELECT public_key_spki FROM identity_key_bindings WHERE identity_id = $1', [sender]);
      if (sKey && sKey.rows && sKey.rows.length > 0) {
        senderPublicKey = sKey.rows[0].public_key_spki;
        senderGeneration = Number(sKey.rows[0].generation || 1);
      }
    } catch (_) {}
    if (!receiverPublicKey) {
      try {
        const rKey = await query('SELECT public_key_spki FROM identity_key_bindings WHERE identity_id = $1', [receiver]);
        if (rKey && rKey.rows && rKey.rows.length > 0) {
          receiverPublicKey = rKey.rows[0].public_key_spki;
        }
      } catch (_) {}
    }
  } else {
    if (typeof registeredDevicePublicKeys !== 'undefined' && registeredDevicePublicKeys) {
      senderPublicKey = registeredDevicePublicKeys.get(sender) || null;
      if (!receiverPublicKey) {
        receiverPublicKey = registeredDevicePublicKeys.get(receiver) || null;
      }
    }
  }

  const senderWs = activeSockets.get(kSender);
  const senderMsg = {
    type: 'MUTUAL_CONNECTION_ESTABLISHED',
    partnerIdentity: receiver,
    partnerAlias: recAlias,
    partnerPublicKey: receiverPublicKey,
    ...(inviteConnectionRevision !== null ? { revision: inviteConnectionRevision } : {}),
    timestamp: Date.now(),
    viaInvite: true
  };
  if (senderWs && senderWs.readyState === WebSocket.OPEN) {
    senderWs.send(JSON.stringify(senderMsg));
  } else {
    if (!pendingMessages.has(kSender)) pendingMessages.set(kSender, []);
    pendingMessages.get(kSender).push(senderMsg);
  }

  const receiverWs = activeSockets.get(kReceiver);
  if (receiverWs && receiverWs.readyState === WebSocket.OPEN) {
    receiverWs.send(JSON.stringify({
      type: 'MUTUAL_CONNECTION_ESTABLISHED',
      partnerIdentity: sender,
      partnerAlias: originalAlias,
      partnerPublicKey: senderPublicKey,
      ...(inviteConnectionRevision !== null ? { revision: inviteConnectionRevision } : {}),
      timestamp: Date.now(),
      viaInvite: true
    }));
  }

  console.log(`[Convite] Resgate concluído atomicamente: ${inviteId}`);
  return res.json({
    success: true,
    senderIdentity: sender,
    senderAlias: originalAlias,
    senderPublicKey: senderPublicKey,
    senderGeneration: senderGeneration,
    receiverIdentity: receiver,
    ...(inviteConnectionRevision !== null ? { revision: inviteConnectionRevision } : {}),
    receiverAlias: recAlias,
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
      Se o app não abrir automaticamente, use <strong>"Abrir no Aplicativo PessoasAqui"</strong>.
      No Samsung, Motorola ou outro Android, confira em
      <strong>Configurações &gt; Aplicativos &gt; PessoasAqui &gt; Abrir por padrão</strong>
      se a opção <strong>"Abrir links compatíveis"</strong> está ativada para
      <strong>pessoasaqui3.onrender.com</strong>.
      Se preferir, toque em <strong>"Copiar Link do Convite"</strong>,
      abra <strong>PessoasAqui &gt; Conversas &gt; Colar Convite</strong>.
      O vínculo só será criado após sua confirmação no aplicativo.
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

// Sem defaults: instalações não provisionadas não possuem acesso de administração.
const ADMIN_USERNAME = process.env.ADMIN_USER || '';
const ADMIN_PASSWORD = process.env.ADMIN_PASSWORD || '';
const ADMIN_JWT_SECRET = process.env.ADMIN_JWT_SECRET || '';
const ADMIN_READY = ADMIN_USERNAME.length >= 3 && ADMIN_PASSWORD.length >= 12 &&
  Buffer.byteLength(ADMIN_JWT_SECRET,'utf8') >= 32;
const equalSafe = (a,b) => {
  const left=Buffer.from(String(a),'utf8'),right=Buffer.from(String(b),'utf8');
  return left.length===right.length && crypto.timingSafeEqual(left,right);
};

function generateAdminToken(username) {
  if(!ADMIN_READY)throw Error('Admin desabilitado');
  const payload = `${username}:${Date.now()}:${crypto.randomBytes(16).toString('hex')}`;
  const sig = crypto.createHmac('sha256', ADMIN_JWT_SECRET).update(payload).digest('hex');
  return Buffer.from(`${payload}:${sig}`).toString('base64');
}

function verifyAdminToken(token) {
  if (!token) return null;
  try {
    const decoded = Buffer.from(token, 'base64').toString('utf8');
    const parts = decoded.split(':');
    if (!ADMIN_READY || parts.length !== 4) return null;
    const [username, timestamp, nonce, sig] = parts;
    const epoch=Number(timestamp), age=Date.now()-epoch;
    if(username!==ADMIN_USERNAME || !Number.isSafeInteger(epoch) ||
       age < -60000 || age > 8*3600*1000 || !/^[a-f0-9]{32}$/.test(nonce))return null;
    const expected = crypto.createHmac('sha256', ADMIN_JWT_SECRET)
      .update(`${username}:${timestamp}:${nonce}`).digest('hex');
    if (!equalSafe(sig,expected)) return null;
    return { username, role: 'SUPER_ADMIN' };
  } catch (_) {
    return null;
  }
}

function requireAdminAuth(req, res, next) {
  const authHeader = req.headers.authorization || '';
  const token = authHeader.replace(/^Bearer\s+/i, '').trim();
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
  if (!reporterIdentity || !accusedIdentity || typeof reason!=='string' ||
      reason.trim().length===0 || reason.length>4000) {
    return res.status(400).json({ error: 'Identidades e motivo válido são obrigatórios.' });
  }
  const cleanReporter = cleanId(reporterIdentity);
  const cleanAccused = cleanId(accusedIdentity);
  const vType = String(violationType || 'ABUSE').toUpperCase();
  const evidencePayloadStr = typeof evidenceMessages === 'object' ?
    JSON.stringify(evidenceMessages) : String(evidenceMessages || '');
  if (!/^[A-F0-9]{16}$/.test(cleanAccused) || evidencePayloadStr.length>16000)
    return res.status(400).json({error:'Denúncia inválida ou evidência excessiva.'});
  if(!await verifySignedHttp(req,res,'REPORT_CREATE',cleanReporter,cleanAccused,
      digestHttpParts([String(reporterIdentity||''),String(accusedIdentity||''),
        String(violationType||''),String(reason||''),evidencePayloadStr])))return;
  if(process.env.DATABASE_URL&&!isPostgres())
    return res.status(503).json({error:'Denúncia não registrada; banco indisponível.'});

  const reportId = 'rep_' + Date.now().toString(36) + '_' + crypto.randomBytes(3).toString('hex');
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

  if (isPostgres()) {
    try {
      await query(
        `INSERT INTO moderation_reports (id, reporter_identity, accused_identity, violation_type, reason, evidence_payload, status)
         VALUES ($1, $2, $3, $4, $5, $6, 'PENDING')`,
        [reportId, cleanReporter, cleanAccused, vType, reason.trim(), evidencePayloadStr]
      );
    } catch (e) {
      console.warn('[DB] Falha ao registrar denúncia no PostgreSQL:', e.message);
      return res.status(503).json({error:'Denúncia não confirmada no banco.'});
    }
  }
  localStore.moderationReports.unshift(reportObj);
  if (localStore.moderationReports.length > 500) localStore.moderationReports.pop();

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
  if(!await verifySignedHttp(req,res,'TICKET_CREATE',cleanAuthor,'',digestHttpParts([
      String(authorIdentity||''),String(authorAlias||''),String(category||''),
      String(subject||''),String(message||'')])))return;
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

  if(process.env.DATABASE_URL&&!isPostgres())return res.status(503).json({error:'Chamado não registrado; banco indisponível.'});
  if (isPostgres()) {
    try {
      await query(
        `INSERT INTO support_tickets (id, author_identity, author_alias, category, subject, message, status)
         VALUES ($1, $2, $3, $4, $5, $6, 'OPEN')`,
        [ticketId, cleanAuthor, authorAlias || 'Usuário', cat, subj, message.trim()]
      );
    } catch (e) {
      console.warn('[DB] Falha ao gravar ticket no PostgreSQL:', e.message);
      return res.status(503).json({error:'Chamado não confirmado no banco.'});
    }
  }
  localStore.supportTickets.unshift(ticketObj);
  if (localStore.supportTickets.length > 500) localStore.supportTickets.pop();

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

/**
 * Denúncia de familiar: o alvo é recuperado do vínculo de recuperação
 * credenciado pelo titular, e não de relação declarada pela própria Maria.
 * O suporte recebe metadados mínimos e NUNCA PIN/segredo do cofre.
 */
app.post('/api/family/support/report',async(req,res)=>{
  const author=cleanId(req.body?.authorIdentity);
  const affected=cleanId(req.body?.affectedIdentity);
  const reason=String(req.body?.message||'').trim();
  const category=String(req.body?.category||'SECURITY_RECOVERY').trim().toUpperCase();
  if(!/^[A-F0-9]{16}$/.test(author)||!/^[A-F0-9]{16}$/.test(affected)||
     author===affected || reason.length<10 || reason.length>4000 ||
     !['SECURITY_RECOVERY','LOST_DEVICE','UNAUTHORIZED_DELETE','ACCOUNT_THEFT'].includes(category))
    return res.status(400).json({error:'Dados inválidos para denúncia familiar.'});
  if(!await verifySignedHttp(req,res,'FAMILY_INCIDENT',author,affected,
    digestHttpParts([String(req.body?.authorIdentity||''),
      String(req.body?.affectedIdentity||''),String(req.body?.authorAlias||''),category,reason])))return;
  if(!isPostgres())return res.status(503).json({error:'Consulta familiar indisponível.'});
  try{
    const verified=await query(
      `SELECT family_identity FROM recovery_delegations
        WHERE target_identity=$1 AND family_identity=$2 AND is_active=TRUE`,
      [affected,author]);
    if(verified.rowCount!==1)return res.status(403).json({
      error:'Somente familiar autorizado pelo titular pode usar este canal.'});
    const ticketId='tkt_'+Date.now().toString(36)+'_'+crypto.randomBytes(8).toString('hex');
    await withTransaction(async run=>{
      const saved=await run(
        `INSERT INTO support_tickets
          (id,author_identity,author_alias,subject,category,message,affected_identity,verified_family_report)
          VALUES($1,$2,$3,$4,$5,$6,$7,TRUE) RETURNING id`,
        [ticketId,author,String(req.body?.authorAlias||'Familiar').slice(0,64),
         'Incidente de segurança familiar',category,reason,affected]);
      if(saved.rowCount!==1)throw Error('Falha na gravação');
      await run(
        `INSERT INTO recovery_security_events
          (target_identity,actor_identity,event_type,request_id)
         VALUES($1,$2,'FAMILY_INCIDENT_REPORTED',$3)`,
        [affected,author,ticketId]);
    });
    return res.json({success:true,ticketId,affectedIdentity:affected,
      message:'Denúncia registrada; a equipe analisará o caso sem acesso a PIN ou mensagens privadas.'});
  }catch(_){return res.status(503).json({error:'Não foi possível registrar o incidente.'});}
});

app.get('/api/tickets/my', (_req,res)=>res.status(401).json({error:'Autenticação obrigatória.'}));
app.post('/api/tickets/my', async (req, res) => {
  const identity = cleanId(req.body?.identity);
  if (!identity) {
    return res.status(400).json({ error: 'identity é obrigatório.' });
  }
  if(!await verifySignedHttp(req,res,'TICKET_READ',identity))return;
  if(process.env.DATABASE_URL&&!isPostgres())return res.status(503).json({error:'Chamados indisponíveis.'});
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
    } catch (_) {
      return res.status(503).json({error:'Não foi possível consultar chamados.'});
    }
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

  if(!ADMIN_READY) return res.status(503).json({error:'Credenciais administrativas não provisionadas.'});
  const { username, password } = req.body || {};
  if (!username || !password) {
    return res.status(400).json({ error: 'Usuário e senha são obrigatórios.' });
  }

  if (equalSafe(username,ADMIN_USERNAME) && equalSafe(password,ADMIN_PASSWORD)) {
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
  if(process.env.DATABASE_URL&&!isPostgres())return res.status(503).json({error:'Banco administrativo indisponível.'});
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
  if(process.env.DATABASE_URL&&!isPostgres())return res.status(503).json({error:'Banco administrativo indisponível.'});
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
  if(process.env.DATABASE_URL&&!isPostgres())return res.status(503).json({error:'Banco administrativo indisponível.'});
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

  if (isPostgres()) {
    try {
      await withTransaction(async run=>{
        const result=await run('UPDATE moderation_reports SET status=$1,moderator_notes=$2,resolved_at=NOW() WHERE id=$3 RETURNING id',
          [action==='BAN'?'BANNED':action==='STRIKE'?'STRIKE_ISSUED':'DISMISSED',notes||null,id]);
        if(result.rowCount!==1)throw Object.assign(Error('Denúncia ausente'),{status:404});
        if(action==='BAN'){
          const banned=await run('UPDATE identities SET is_banned=TRUE WHERE LOWER(id)=LOWER($1) RETURNING id',[accused]);
          if(banned.rowCount!==1)throw Object.assign(Error('Identidade ausente'),{status:404});
          await run('UPDATE devices SET is_active=FALSE,revoked_at=NOW() WHERE LOWER(identity_id)=LOWER($1)',[accused]);
        }
      });
    }catch(e){return res.status(e.status===404?404:503).json({error:'Moderação não confirmada no banco.'});}
  }
  if (action === 'BAN') {
    newStatus = 'BANNED';
    bannedIdentities.add(kAccused);

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
    // Avisos administrativos são eventos autenticados; nunca texto disfarçado de E2EE.
  } else if (action === 'DISMISS') {
    newStatus = 'DISMISSED';
  }

  const resolvedAt = new Date().toISOString();
  if (report) {
    report.status = newStatus;
    report.moderator_notes = notes || null;
    report.resolved_at = resolvedAt;
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
  if(process.env.DATABASE_URL&&!isPostgres())return res.status(503).json({error:'Banco administrativo indisponível.'});
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
  if(process.env.DATABASE_URL&&!isPostgres())return res.status(503).json({error:'Banco administrativo indisponível.'});
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

  if(isPostgres()){
    try{
      const updated=await query(
        "UPDATE support_tickets SET status='ANSWERED',moderator_reply=$1,replied_at=NOW() WHERE id=$2 RETURNING id",
        [reply.trim(),id]);
      if(updated.rowCount!==1)return res.status(404).json({error:'Chamado não encontrado.'});
    }catch(_){return res.status(503).json({error:'Resposta não persistida no banco.'});}
  }
  const nowIso = new Date().toISOString();
  if (ticket) {
    ticket.status = 'ANSWERED';
    ticket.moderator_reply = reply.trim();
    ticket.replied_at = nowIso;
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

  // Nunca injetar avisos em texto puro no fluxo privado E2EE.

  console.log(`[SUPORTE] Resposta enviada ao chamado ${id} para ${ticket.author_identity}`);
  res.json({ success: true, message: 'Resposta enviada com sucesso ao cliente.' });
});

/**
 * Consulta e Gestão de Usuários
 */


/**
 * Controles preventivos: não transferem propriedade, não revelam segredos.
 * Requerem autenticação admin, motivo, persistência e trilha de auditoria.
 */
app.post('/admin/api/security/:id/hold',requireAdminAuth,async(req,res)=>{
  const id=cleanId(req.params.id);
  const reason=String(req.body?.reason||'').trim();
  const hours=Number(req.body?.hours||24);
  if(!/^[A-F0-9]{16}$/.test(id)||reason.length<12||reason.length>1000||
     !Number.isSafeInteger(hours)||hours<1||hours>48)
    return res.status(400).json({error:'Identidade, motivo detalhado e prazo de 1–48 horas obrigatórios.'});
  if(!isPostgres())return res.status(503).json({error:'Ação exige PostgreSQL.'});
  try{
    await withTransaction(async run=>{
      const owner=await run('SELECT id FROM identities WHERE id=$1 FOR UPDATE',[id]);
      if(owner.rowCount!==1)throw Object.assign(Error('Conta inexistente'),{status:404});
      await run(`INSERT INTO account_recovery_holds(target_identity,blocked_until,reason)
          VALUES($1,NOW()+($2::int*INTERVAL '1 hour'),$3)
          ON CONFLICT(target_identity) DO UPDATE SET
          blocked_until=EXCLUDED.blocked_until,reason=EXCLUDED.reason,created_at=NOW()`,
        [id,hours,reason]);
      await run(`INSERT INTO recovery_security_events
        (target_identity,actor_identity,event_type,details)
        VALUES($1,$2,'ADMIN_RECOVERY_HOLD',$3::jsonb)`,
        [id,null,JSON.stringify({admin:req.adminUser.username,hours,reason})]);
    });
    return res.json({success:true,blockedHours:hours});
  }catch(e){return res.status(e.status===404?404:503).json({error:'Restrição não confirmada.'});}
});
app.post('/admin/api/security/:id/release',requireAdminAuth,async(req,res)=>{
  const id=cleanId(req.params.id),reason=String(req.body?.reason||'').trim();
  if(!/^[A-F0-9]{16}$/.test(id)||reason.length<12||reason.length>1000)
    return res.status(400).json({error:'Identidade e justificativa obrigatórias.'});
  if(!isPostgres())return res.status(503).json({error:'Ação exige PostgreSQL.'});
  try{
    await withTransaction(async run=>{
      const old=await run('DELETE FROM account_recovery_holds WHERE target_identity=$1 RETURNING target_identity',[id]);
      if(old.rowCount!==1)throw Object.assign(Error('Conta sem restrição.'),{status:404});
      await run(`INSERT INTO recovery_security_events
        (target_identity,actor_identity,event_type,details)
        VALUES($1,$2,'ADMIN_RECOVERY_RELEASE',$3::jsonb)`,
        [id,null,JSON.stringify({admin:req.adminUser.username,reason})]);
    });
    return res.json({success:true});
  }catch(e){return res.status(e.status===404?404:503).json({error:'Liberação não confirmada.'});}
});
app.post('/admin/api/security/:id/cancel-recoveries',requireAdminAuth,async(req,res)=>{
  const id=cleanId(req.params.id),reason=String(req.body?.reason||'').trim();
  if(!/^[A-F0-9]{16}$/.test(id)||reason.length<12||reason.length>1000)
    return res.status(400).json({error:'Identidade e justificativa obrigatórias.'});
  if(!isPostgres())return res.status(503).json({error:'Ação exige PostgreSQL.'});
  try{
    const cancelled=await withTransaction(async run=>{
      const r=await run(`UPDATE recovery_grants SET used_at=NOW()
        WHERE target_identity=$1 AND used_at IS NULL RETURNING id`,[id]);
      await run(`INSERT INTO recovery_security_events
        (target_identity,actor_identity,event_type,details)
        VALUES($1,$2,'ADMIN_CANCEL_RECOVERY',$3::jsonb)`,
        [id,null,JSON.stringify({admin:req.adminUser.username,reason,grants:r.rowCount})]);
      return r.rowCount;
    });
    return res.json({success:true,cancelled});
  }catch(_){return res.status(503).json({error:'Cancelamento não confirmado.'});}
});

/** Consulta de evidências administrativas da identidade prejudicada. */
app.get('/admin/api/security/:id',requireAdminAuth,async(req,res)=>{
  const id=cleanId(req.params.id);
  if(!/^[A-F0-9]{16}$/.test(id))return res.status(400).json({error:'Identidade inválida.'});
  if(!isPostgres())return res.status(503).json({error:'Auditoria exige banco persistente.'});
  try{
    const [user,delegates,grants,events,tickets,devices,hold]=await Promise.all([
      query('SELECT id,alias,is_banned,created_at FROM identities WHERE UPPER(id)=UPPER($1)',[id]),
      query(`SELECT family_identity,is_active,updated_at FROM recovery_delegations
          WHERE target_identity=$1 ORDER BY updated_at DESC LIMIT 100`,[id]),
      query(`SELECT id,family_identity,method,eligible_at,expires_at,used_at,created_at
          FROM recovery_grants WHERE target_identity=$1
          ORDER BY created_at DESC LIMIT 100`,[id]),
      query(`SELECT actor_identity,event_type,request_id,details,created_at
          FROM recovery_security_events WHERE target_identity=$1
          ORDER BY created_at DESC LIMIT 200`,[id]),
      query(`SELECT id,author_identity,subject,category,status,created_at,verified_family_report
          FROM support_tickets WHERE affected_identity=$1
          ORDER BY created_at DESC LIMIT 100`,[id]),
      query(`SELECT is_active,revoked_at FROM devices WHERE identity_id=$1 LIMIT 100`,[id]),
      query('SELECT blocked_until,reason,created_at FROM account_recovery_holds WHERE target_identity=$1',[id])
    ]);
    if(user.rowCount!==1)return res.status(404).json({error:'Conta inexistente.'});
    return res.json({success:true,identity:user.rows[0],families:delegates.rows,
      recoverySessions:grants.rows,securityEvents:events.rows,
      familyTickets:tickets.rows,deviceStatus:devices.rows,
      recoveryHold:hold.rows[0]||null});
  }catch(_){return res.status(503).json({error:'Auditoria indisponível.'});}
});

app.get('/admin/api/users', requireAdminAuth, async (req, res) => {
  if(process.env.DATABASE_URL&&!isPostgres())return res.status(503).json({error:'Banco administrativo indisponível.'});
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
  if(process.env.DATABASE_URL&&!isPostgres())return res.status(503).json({error:'Banco administrativo indisponível.'});
  const id = cleanId(req.params.id);
  const kId = keyId(id);

  if (isPostgres()) {
    try {
      const updated=await query('UPDATE identities SET is_banned=FALSE WHERE LOWER(id)=LOWER($1) RETURNING id',[id]);
      if(updated.rowCount!==1)return res.status(404).json({error:'Identidade não encontrada.'});
    } catch (_) {return res.status(503).json({error:'Reversão não confirmada no banco.'});}
  }
  bannedIdentities.delete(kId);

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

module.exports = {
  app,
  server,
  activeSockets,
  wss,
  purgePendingMessagesBetween,
  purgePendingMessagesRam,
  verifySchemaCompatibility,
  deliverMessageToTarget,
  getConnectionStatus,
  localStore,
  revokedConnections,
  pairKey,
  verifySignedHttp,
  digestHttpParts,
  checkRateLimit
};
