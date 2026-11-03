const crypto = require('crypto');

const INVITE_LIFETIME_MS = 7 * 24 * 60 * 60 * 1000;
const INVITE_ID_RE = /^inv_[a-f0-9]{24}$/i;
const TOKEN_RE = /^[a-f0-9]{32,64}$/i;

function tokenDigest(token) {
  return crypto.createHash('sha256').update(token, 'utf8').digest('hex');
}

/** Legacy 32/64-hex plaintext rows remain redeemable once during migration. */
function verifyStoredToken(stored, received) {
  if (typeof stored !== 'string' || typeof received !== 'string' ||
      !TOKEN_RE.test(received)) return false;
  const storedDigest = stored.length === 64 ? stored : tokenDigest(stored);
  const expected = tokenDigest(received);
  if (!/^[a-f0-9]{64}$/i.test(storedDigest)) return false;
  return crypto.timingSafeEqual(Buffer.from(storedDigest, 'hex'), Buffer.from(expected, 'hex')) ||
      (stored.length === 64 &&
       crypto.timingSafeEqual(Buffer.from(tokenDigest(stored), 'hex'), Buffer.from(expected, 'hex')));
}

function validateInviteId(value) {
  return typeof value === 'string' && INVITE_ID_RE.test(value);
}
function validateInviteToken(value) {
  return typeof value === 'string' && TOKEN_RE.test(value);
}

module.exports = {
  INVITE_LIFETIME_MS, tokenDigest, verifyStoredToken, validateInviteId,
  validateInviteToken
};
