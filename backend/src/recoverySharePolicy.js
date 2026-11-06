'use strict';

/**
 * Recuperação do material V3 (PIN + segredo) é uma operação DIFERENTE da
 * recuperação de identidade. Duas aprovações recuperam a identidade, mas
 * liberar duas partes Shamir exige DOIS guardiões independentes que NÃO
 * emitiram o QR/LINK que iniciou a sessão.
 *
 * Assim, são necessários >=3 familiares ativos: emissor + 2 liberadores.
 * O backend somente autoriza; nunca recebe partes, PIN ou segredo V3.
 */
const MIN_SECRET_RECOVERY_FAMILIES = 3;

function validateShareRelease({
  issuer, approver, releaser, activeDelegates, alreadyReleasedBy = [],
  preparedDeviceIdentity, claimantIdentity, issuedAt, expiresAt, now
}) {
  const delegates = Array.isArray(activeDelegates) ? new Set(activeDelegates) : new Set();
  const released = Array.isArray(alreadyReleasedBy) ? new Set(alreadyReleasedBy) : new Set();
  if (!issuer || !approver || !releaser || issuer === approver ||
      releaser === issuer || releaser === claimantIdentity ||
      delegates.size < MIN_SECRET_RECOVERY_FAMILIES ||
      !delegates.has(issuer) || !delegates.has(approver) ||
      !delegates.has(releaser) || released.has(releaser) ||
      !preparedDeviceIdentity || preparedDeviceIdentity !== claimantIdentity ||
      !Number.isFinite(issuedAt) || !Number.isFinite(expiresAt) ||
      !Number.isFinite(now) || issuedAt > now || now >= expiresAt) return false;
  // Duas liberações distintas, além do emissor; a terceira requisição falha.
  return released.size < 2 && [...released].every(id =>
    id !== issuer && delegates.has(id));
}

module.exports = {MIN_SECRET_RECOVERY_FAMILIES, validateShareRelease};
