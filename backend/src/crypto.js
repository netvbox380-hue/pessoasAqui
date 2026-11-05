const crypto = require('crypto');

/**
 * Motor Criptográfico do Servidor PessoasAqui
 */

const PIN_GLOBAL_SALT = 'PA_SALT_2026_'; // Apenas leitura de hashes históricos
const PIN_KDF_ROUNDS = 310000;
const HEX_64 = /^[a-f0-9]{64}$/i;

/** Novos registros: sal único criptográfico + PBKDF2-SHA256 versionado. */
function hashPin(pin) {
  if (typeof pin !== 'string' || !pin.trim()) throw new TypeError('PIN inválido');
  const salt=crypto.randomBytes(16);
  const digest=crypto.pbkdf2Sync(pin.trim(),salt,PIN_KDF_ROUNDS,32,'sha256');
  return `pbkdf2v2$${salt.toString('hex')}$${digest.toString('hex')}`;
}

/** Verifica V2 e formatos V1 sem permitir downgrade em gravações novas. */
function verifyPinHash(pin, expectedHash) {
  if(typeof pin!=='string' || typeof expectedHash!=='string')return false;
  const clean=pin.trim();
  if(!clean)return false;
  try{
    const parts=expectedHash.split('$');
    if(parts[0]==='pbkdf2v2' && parts.length===3 &&
       /^[a-f0-9]{32}$/i.test(parts[1]) && HEX_64.test(parts[2])){
      const actual=crypto.pbkdf2Sync(clean,Buffer.from(parts[1],'hex'),PIN_KDF_ROUNDS,32,'sha256');
      return crypto.timingSafeEqual(actual,Buffer.from(parts[2],'hex'));
    }
    if(parts[0]==='pbkdf2' && parts.length===2 && HEX_64.test(parts[1])){
      const actual=crypto.pbkdf2Sync(clean,PIN_GLOBAL_SALT,10000,32,'sha256');
      return crypto.timingSafeEqual(actual,Buffer.from(parts[1],'hex'));
    }
    if(HEX_64.test(expectedHash)){
      const actual=crypto.createHash('sha256').update(PIN_GLOBAL_SALT+clean).digest();
      return crypto.timingSafeEqual(actual,Buffer.from(expectedHash,'hex'));
    }
    return false;
  }catch(_){return false;}
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
