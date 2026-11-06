'use strict';
/** Recovery policy: QR authorizations are immediate; remote LINK has a 1h dispute delay.
 * A delay never substitutes for two distinct, active cryptographic family approvals.
 */
const LINK_WAIT_MS = 60 * 60 * 1000;
const QR_TTL_MS = 15 * 60 * 1000;
const LINK_TTL_MS = LINK_WAIT_MS + 15 * 60 * 1000;
const MIN_FAMILIES = 2;
function normalizeMethod(method) {
  return String(method || 'QR').toUpperCase() === 'LINK' ? 'LINK' : 'QR';
}
function eligibility(method, now = Date.now()) {
  const kind = normalizeMethod(method);
  return {method:kind, eligibleAt:now + (kind === 'LINK' ? LINK_WAIT_MS : 0),
    expiresAt:now + (kind === 'LINK' ? LINK_TTL_MS : QR_TTL_MS)};
}
function canClaim({issuer, approver, activeDelegates, now, eligibleAt, expiresAt}) {
  return Boolean(issuer && approver && issuer !== approver &&
    Array.isArray(activeDelegates) && new Set(activeDelegates).size >= MIN_FAMILIES &&
    activeDelegates.includes(issuer) && activeDelegates.includes(approver) &&
    Number.isFinite(now) && Number.isFinite(eligibleAt) &&
    Number.isFinite(expiresAt) && now >= eligibleAt && now < expiresAt);
}
module.exports={LINK_WAIT_MS,QR_TTL_MS,LINK_TTL_MS,MIN_FAMILIES,normalizeMethod,eligibility,canClaim};
