'use strict';
/**
 * Autenticação de convites v1. Não usa sessão WS nem confia no identityHash
 * enviado pelo cliente: o hash técnico corresponde aos primeiros 8 bytes
 * de SHA-256 da chave pública EC P-256 (SPKI DER) gerada no Android.
 *
 * Uma identidade restaurada cuja chave não corresponda ao seu identificador
 * precisa de rebinding criptograficamente autorizado: falha fechada aqui.
 */
const crypto = require('node:crypto');
const PROOF_WINDOW_MS = 120000;
function canonicalInviteProof(operation, identity, alias, inviteId, tokenHash, timestamp, nonce) {
  const parts = ['PA-INVITE-AUTH-V1', operation, String(identity).toUpperCase(),
    alias, inviteId, tokenHash, String(timestamp), nonce];
  return parts.map(value => String(value).length + ':' + value).join('|');
}
function deriveInviteId(identity, nonce) {
  return 'inv_' + crypto.createHash('sha256')
    .update('PA-INVITE-ID-V1:' + String(identity).toUpperCase() + ':' + nonce.toLowerCase(), 'utf8')
    .digest('hex').slice(0, 24);
}
function verifyInviteIdentityProof(operation, identity, alias, inviteId, tokenHash, proof, now = Date.now(), activePublicKey = null) {
  try {
    if (!['CREATE', 'REDEEM', 'RECOVERY_DELEGATE', 'RECOVERY_ISSUE', 'RECOVERY_CLAIM', 'RECOVERY_REVOKE', 'OFFER_PUBLISH', 'OFFER_DELETE'].includes(operation) ||
        typeof identity !== 'string' || typeof alias !== 'string' ||
        typeof inviteId !== 'string' || typeof tokenHash !== 'string' ||
        !proof || proof.v !== 1) return false;
    const { publicKey, timestamp, nonce, signature } = proof;
    if (typeof publicKey !== 'string' || publicKey.length > 512 ||
        typeof signature !== 'string' || signature.length > 512 ||
        typeof nonce !== 'string' ||
        !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(nonce) ||
        !Number.isSafeInteger(timestamp) || Math.abs(now - timestamp) > PROOF_WINDOW_MS ||
        !/^[A-F0-9]{16}$/i.test(identity) ||
        (operation === 'REDEEM' && (!/^inv_[a-f0-9]{12,24}$/i.test(inviteId) ||
          !/^[a-f0-9]{64}$/i.test(tokenHash))) ||
        (operation === 'CREATE' && (inviteId !== '' || tokenHash !== '')) ||
        (operation.startsWith('OFFER_') && (inviteId !== '' || !/^[a-f0-9]{64}$/i.test(tokenHash))) ||
        (operation.startsWith('RECOVERY_') && (inviteId !== '' ||
          (['RECOVERY_ISSUE', 'RECOVERY_REVOKE'].includes(operation) ? tokenHash !== '' : !/^[a-f0-9]{64}$/i.test(tokenHash))))) return false;
    if (!/^[A-Za-z0-9+/]+={0,2}$/.test(publicKey) ||
        !/^[A-Za-z0-9+/]+={0,2}$/.test(signature)) return false;
    const der = Buffer.from(publicKey, 'base64');
    const key = crypto.createPublicKey({key:der,format:'der',type:'spki'});
    if (key.asymmetricKeyType !== 'ec' ||
        key.asymmetricKeyDetails?.namedCurve !== 'prime256v1') return false;
    const calculatedIdentity = crypto.createHash('sha256').update(der)
      .digest('hex').slice(0, 16).toUpperCase();
    // A mesma identidade pode ter nova chave APENAS após transferência
    // autenticada persistente. Sem vínculo autoritativo, exigir ID autocertificado.
    if (activePublicKey !== null) {
      if (publicKey !== activePublicKey) return false;
    } else if (identity.toUpperCase() !== calculatedIdentity) return false;
    const message = canonicalInviteProof(operation, identity.toUpperCase(),
      alias, inviteId, tokenHash, timestamp, nonce);
    const verify = crypto.createVerify('SHA256');
    verify.update(message, 'utf8');
    verify.end();
    return verify.verify(key, Buffer.from(signature, 'base64'));
  } catch (_) {
    return false;
  }
}
module.exports = {canonicalInviteProof, deriveInviteId, verifyInviteIdentityProof, PROOF_WINDOW_MS};
