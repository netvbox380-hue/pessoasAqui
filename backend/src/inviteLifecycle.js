'use strict';
/**
 * Convites de capacidade: token aleatório + assinatura HMAC e consumo único.
 * Um token na URL é um segredo de posse, NÃO conteúdo criptografado.
 *
 * Em ambiente com DATABASE_URL, o PostgreSQL é obrigatório e autoritativo:
 * nunca aceitar um resgate em cache ou recriar um registro só pela assinatura.
 * O UPDATE e as duas conexões são uma única instrução SQL (transação atômica).
 */
const crypto = require('node:crypto');

const DURATION_MS = 7 * 24 * 60 * 60 * 1000;
const INVITE_ID_RE = /^inv_[a-f0-9]{12,48}$/i;
const TOKEN_RE = /^[a-f0-9]{32,128}$/i;

function tokenHash(token) {
  return crypto.createHash('sha256').update(token).digest('hex');
}
function constantTimeEqualHex(a, b) {
  if (!/^[0-9a-f]+$/i.test(String(a)) || !/^[0-9a-f]+$/i.test(String(b))) return false;
  const left = Buffer.from(a, 'hex');
  const right = Buffer.from(b, 'hex');
  return left.length === right.length && crypto.timingSafeEqual(left, right);
}

/**
 * Legacy-compatible DB predicate allows older token-as-plaintext rows.
 * New rows store only sha256(token) inside existing VARCHAR(64) column.
 */
const CONSUME_ATOMIC_SQL = `
WITH consumed AS (
    UPDATE invites
    SET is_used = TRUE, used_by = $2, used_at = NOW()
    WHERE id = $1
      AND (token = $3 OR token = $4)
      AND is_used = FALSE
      AND expires_at > NOW()
      AND sender_identity <> $2
    RETURNING sender_identity, sender_alias
), outbound_edge AS (
    INSERT INTO connections (from_identity, to_identity, is_mutual)
    SELECT sender_identity, $2, TRUE FROM consumed
    ON CONFLICT (from_identity, to_identity) DO UPDATE SET is_mutual = TRUE
    RETURNING from_identity
), inbound_edge AS (
    INSERT INTO connections (from_identity, to_identity, is_mutual)
    SELECT $2, sender_identity, TRUE FROM consumed
    ON CONFLICT (from_identity, to_identity) DO UPDATE SET is_mutual = TRUE
    RETURNING from_identity
)
SELECT consumed.sender_identity, consumed.sender_alias
FROM consumed
JOIN (SELECT COUNT(*) AS n FROM outbound_edge) outbound ON outbound.n = 1
JOIN (SELECT COUNT(*) AS n FROM inbound_edge) inbound ON inbound.n = 1`;

function makeInviteLifecycle({ query, isPostgres, databaseConfigured, memoryStore, secret, now = Date.now }) {
  function sign(inviteId, token, sender, expiresAt) {
    if (!secret) return '';
    return crypto.createHmac('sha256', secret)
      .update(`${inviteId.toLowerCase()}:${token}:${sender.toLowerCase()}:${expiresAt}`)
      .digest('hex');
  }

  async function create({ sender, alias }) {
    if (!sender || sender.length > 64) return { status: 400, error: 'Identidade inválida.' };
    if (!secret && databaseConfigured()) {
      return { status: 503, error: 'Servidor sem configuração de assinatura de convites.' };
    }
    if (databaseConfigured() && !isPostgres()) {
      return { status: 503, error: 'Convites indisponíveis até a recuperação do banco de dados.' };
    }

    const inviteId = 'inv_' + crypto.randomBytes(16).toString('hex');
    const token = crypto.randomBytes(24).toString('hex');
    const createdAt = now();
    const expiresAt = createdAt + DURATION_MS;
    const signature = sign(inviteId, token, sender, expiresAt);
    const stored = {
      inviteId, tokenDigest: tokenHash(token), senderIdentity: sender,
      senderAlias: String(alias || 'Usuário').slice(0, 64),
      signature, createdAt, expiresAt, isUsed: false
    };
    if (databaseConfigured()) {
      try {
        const r = await query(
          `INSERT INTO invites (id, token, sender_identity, sender_alias, signature, expires_at)
           VALUES ($1, $2, $3, $4, $5, TO_TIMESTAMP($6 / 1000.0))
           RETURNING id`,
          [inviteId, stored.tokenDigest, sender, stored.senderAlias, signature, expiresAt]
        );
        if (!r.rows || r.rows.length !== 1) throw new Error('persistência não confirmada');
      } catch (_) {
        return { status: 503, error: 'Convite não pôde ser salvo de modo seguro. Tente novamente.' };
      }
    } else {
      memoryStore.set(inviteId, stored);
    }
    return { status: 200, invite: { inviteId, token, signature,
      senderIdentity: sender, senderAlias: stored.senderAlias, expiresAt } };
  }

  async function redeem({ inviteId, token, receiver, cleanId }) {
    if (!INVITE_ID_RE.test(String(inviteId)) || !TOKEN_RE.test(String(token)) ||
        !receiver || receiver.length > 64) {
      return { status: 400, error: 'Convite ou identidade inválidos.' };
    }
    if (databaseConfigured()) {
      if (!isPostgres()) return { status: 503, error: 'Banco indisponível. Convite não consumido.' };
      try {
        const result = await query(CONSUME_ATOMIC_SQL, [inviteId, receiver, tokenHash(token), token]);
        if (result.rows && result.rows.length === 1) {
          const sender = cleanId(result.rows[0].sender_identity);
          return { status: 200, invite: {
            senderIdentity: sender,
            senderAlias: result.rows[0].sender_alias || 'Pessoa'
          } };
        }
        // Consulta somente para mensagem de erro, nunca para tentar resgatar novamente.
        const state = await query('SELECT token, is_used, expires_at, sender_identity FROM invites WHERE id = $1', [inviteId]);
        if (!state.rows || state.rows.length !== 1) return { status: 404, error: 'Convite não encontrado.' };
        const row = state.rows[0];
        if (row.is_used) return { status: 410, error: 'Convite já utilizado.' };
        if (new Date(row.expires_at).getTime() <= now()) return { status: 410, error: 'Convite expirado.' };
        if (cleanId(row.sender_identity) === receiver) return { status: 400, error: 'Você não pode usar seu próprio convite.' };
        if (!constantTimeEqualHex(tokenHash(token), row.token) &&
            !constantTimeEqualHex(token, row.token)) {
          return { status: 403, error: 'Token de convite inválido.' };
        }
        return { status: 503, error: 'Convite não consumido. Tente novamente.' };
      } catch (_) {
        return { status: 503, error: 'Erro no banco. Convite não consumido.' };
      }
    }
    const invite = memoryStore.get(inviteId);
    if (!invite) return { status: 404, error: 'Convite não encontrado.' };
    if (invite.isUsed) return { status: 410, error: 'Convite já utilizado.' };
    if (invite.expiresAt <= now()) return { status: 410, error: 'Convite expirado.' };
    const sender = cleanId(invite.senderIdentity);
    if (sender === receiver) return { status: 400, error: 'Você não pode usar seu próprio convite.' };
    const match = constantTimeEqualHex(tokenHash(token), invite.tokenDigest || '') ||
      constantTimeEqualHex(token, invite.token || '');
    if (!match) return { status: 403, error: 'Token de convite inválido.' };
    // Sem 'await' entre checar e marcar: consumo sequencial no loop Node.js.
    invite.isUsed = true;
    invite.usedBy = receiver;
    invite.usedAt = now();
    return { status: 200, invite: { senderIdentity: sender, senderAlias: invite.senderAlias } };
  }
  return { create, redeem };
}
module.exports = { makeInviteLifecycle, tokenHash, constantTimeEqualHex, CONSUME_ATOMIC_SQL };
