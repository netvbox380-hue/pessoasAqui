const crypto = require('crypto');

/**
 * Motor Criptográfico do Servidor PessoasAqui
 */

const PIN_GLOBAL_SALT = 'PA_SALT_2026_';

/**
 * Gera hash resistente contra força bruta utilizando PBKDF2 (10.000 iterações).
 */
function hashPin(pin) {
  const cleanPin = pin.trim();
  const derived = crypto.pbkdf2Sync(cleanPin, PIN_GLOBAL_SALT, 10000, 32, 'sha256').toString('hex');
  return `pbkdf2$${derived}`;
}

/**
 * Validação segura de PIN com verificação em tempo constante (proteção contra timing attacks).
 * Oferece compatibilidade com hashes legados SHA-256 e novos hashes PBKDF2.
 */
function verifyPinHash(pin, expectedHash) {
  if (!expectedHash || typeof expectedHash !== 'string') return false;
  const cleanPin = pin.trim();

  try {
    if (expectedHash.startsWith('pbkdf2$')) {
      const candidate = hashPin(cleanPin);
      const bufA = Buffer.from(candidate);
      const bufB = Buffer.from(expectedHash);
      if (bufA.length !== bufB.length) return false;
      return crypto.timingSafeEqual(bufA, bufB);
    }

    // Validação retrocompatível com o formato SHA-256 legado
    const legacyCandidate = crypto.createHash('sha256').update(PIN_GLOBAL_SALT + cleanPin).digest('hex');
    const bufA = Buffer.from(legacyCandidate);
    const bufB = Buffer.from(expectedHash);
    if (bufA.length !== bufB.length) return false;
    return crypto.timingSafeEqual(bufA, bufB);
  } catch (_) {
    return false;
  }
}

function generateNonce() {
  return crypto.randomBytes(32).toString('hex');
}

/**
 * Escada progressiva de bloqueio por tentativas de PIN
 */
function calculateLockoutDurationSeconds(attempts) {
  switch (attempts) {
    case 1:
    case 2:
      return 0;
    case 3:
      return 5 * 3600; // 5 horas
    case 4:
      return 10 * 3600; // 10 horas
    case 5:
      return 24 * 3600; // 24 horas
    case 6:
      return 48 * 3600; // 48 horas
    case 7:
      return 7 * 24 * 3600; // 7 dias
    case 8:
      return 15 * 24 * 3600; // 15 dias
    case 9:
      return 30 * 24 * 3600; // 30 dias
    default:
      return -1; // 10ª ou mais: cancelamento definitivo
  }
}

module.exports = {
  hashPin,
  verifyPinHash,
  generateNonce,
  calculateLockoutDurationSeconds
};
