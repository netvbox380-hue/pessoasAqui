const crypto = require('crypto');

/**
 * Motor Criptográfico do Servidor PessoasAqui
 */

function hashPin(pin) {
  const salt = 'PA_SALT_2026_';
  return crypto.createHash('sha256').update(salt + pin.trim()).digest('hex');
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
  generateNonce,
  calculateLockoutDurationSeconds
};
