'use strict';

/**
 * PessoasAqui / R2 authenticated action gate — STAGED, NOT ROUTED.
 *
 * Android identity proofs use ECDSA P-256 / SHA-256, as in inviteAuth.js.
 * Ed25519 is not the active identity-proof protocol for this Android client.
 * This is a fail-closed transaction gate, NOT an HTTP handler or R2 signer.
 *
 * Every caller must supply only server-selected operation and exact validated
 * action fields. Nonce insertion and the authorized database operation run in
 * the SAME PostgreSQL transaction. Migration 009 is required.
 */
const crypto = require('node:crypto');

const PROOF_WINDOW_MS = 120000;
const OPERATIONS = Object.freeze({
  R2_RESERVE: ['messageId', 'objectKey'],
  // Dedicated ECDSA domain for physical PUT capability issuance.
  // A signed R2_RESERVE proof must NEVER authorize storage access.
  R2_ISSUE_PUT: ['messageId', 'objectKey'],
  R2_CONFIRM: ['messageId', 'objectKey', 'generation', 'contentSha256', 'contentBytes'],
  R2_ATTACH: ['messageId', 'objectKey', 'generation', 'contentSha256', 'contentBytes',
    'ciphertextDigest'],
  R2_READ: ['messageId', 'objectKey']
});
const ID_PATTERN = /^[a-f0-9]{16}$/i;
const UUID_PATTERN = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i;

function lengthFields(values) {
  return values.map(value => {
    const s = String(value);
    return Buffer.byteLength(s, 'utf8') + ':' + s;
  }).join('|');
}

/**
 * Require operation-specific exact fields; no implicit JSON key order,
 * unknown fields, nulls, arrays or caller-controlled mode flags.
 * The attach digest is SHA-256 of the complete encrypted descriptor,
 * not plaintext or a decryption key.
 */
function canonicalR2Action(operation, identity, peerIdentity, fields, timestamp, nonce) {
  const names = OPERATIONS[operation];
  if (!names || !ID_PATTERN.test(identity || '') ||
      !ID_PATTERN.test(peerIdentity || '') ||
      identity.toUpperCase() === peerIdentity.toUpperCase() ||
      !fields || typeof fields !== 'object' || Array.isArray(fields) ||
      Object.keys(fields).length !== names.length ||
      !names.every(name => Object.prototype.hasOwnProperty.call(fields, name)) ||
      !Number.isSafeInteger(timestamp) || !UUID_PATTERN.test(nonce || '')) {
    throw new TypeError('Invalid R2 signed action');
  }
  const values = [];
  for (const name of names) {
    const v = fields[name];
    if (name === 'contentBytes') {
      if (!Number.isSafeInteger(v) || v < 1 || v > 25 * 1024 * 1024) {
        throw new TypeError('Invalid R2 content byte count');
      }
    } else if (name === 'generation') {
      if (v !== 1) throw new TypeError('Invalid R2 generation');
    } else if (name === 'contentSha256' || name === 'ciphertextDigest') {
      if (typeof v !== 'string' || !/^[a-f0-9]{64}$/.test(v)) {
        throw new TypeError('Invalid R2 digest');
      }
    } else if (name === 'messageId') {
      if (typeof v !== 'string' || !v || v.length > 256 ||
          /[\u0000-\u001f\u007f]/.test(v)) throw new TypeError('Invalid R2 message');
    } else if (name === 'objectKey') {
      const { validateOpaqueObjectKey } = require('./r2CiphertextAdapter');
      validateOpaqueObjectKey(v);
    }
    values.push(v);
  }
  return lengthFields(['PA-R2-ACTION-V1', operation, identity.toUpperCase(),
    peerIdentity.toUpperCase(), ...values, timestamp, nonce.toLowerCase()]);
}

function verifyR2ActionProof({ operation, identity, peerIdentity, fields, proof,
  activePublicKey, now = Date.now() }) {
  try {
    if (!proof || proof.v !== 1 || !Number.isSafeInteger(now) ||
        !Number.isSafeInteger(proof.timestamp) ||
        Math.abs(now - proof.timestamp) > PROOF_WINDOW_MS ||
        !UUID_PATTERN.test(proof.nonce || '') ||
        typeof activePublicKey !== 'string' || activePublicKey.length > 512 ||
        typeof proof.publicKey !== 'string' || proof.publicKey !== activePublicKey ||
        typeof proof.signature !== 'string' || proof.signature.length > 512 ||
        !/^[A-Za-z0-9+/]+={0,2}$/.test(activePublicKey) ||
        !/^[A-Za-z0-9+/]+={0,2}$/.test(proof.signature)) return false;
    const key = crypto.createPublicKey({
      key: Buffer.from(activePublicKey, 'base64'), format: 'der', type: 'spki'
    });
    if (key.asymmetricKeyType !== 'ec' ||
        key.asymmetricKeyDetails?.namedCurve !== 'prime256v1') return false;
    const bytes = canonicalR2Action(operation, identity, peerIdentity, fields,
      proof.timestamp, proof.nonce);
    const verifier = crypto.createVerify('SHA256');
    verifier.update(bytes, 'utf8');
    verifier.end();
    return verifier.verify(key, Buffer.from(proof.signature, 'base64'));
  } catch (_) { return false; }
}

/**
 * The only source of authenticatedIdentity is a verified, DB-bound P-256 key.
 * An HTTP route must NOT accept authenticatedIdentity from client JSON.
 *
 * action(tx, context) MUST perform object ownership and state checks for
 * the named message/object, and execute reservation/confirm/attach/read using
 * the SAME tx. This gate verifies identity, bilateral pair and replay only.
 *
 * Fail closed: missing PostgreSQL, key binding, connection or nonce table
 * throws or returns a rejected status. Do not fall back to RAM.
 */
async function executeAuthenticatedR2Action({ withTransaction, operation, identity,
  peerIdentity, fields, proof, action, now = Date.now() }) {
  if (typeof withTransaction !== 'function' || typeof action !== 'function') {
    throw new TypeError('PostgreSQL transaction and action required');
  }
  const actor = typeof identity === 'string' ? identity.toUpperCase() : '';
  const peer = typeof peerIdentity === 'string' ? peerIdentity.toUpperCase() : '';
  try {
    canonicalR2Action(operation, actor, peer, fields,
      proof && proof.timestamp, proof && proof.nonce);
  } catch (_) {
    return { authorized: false, statusCode: 400, code: 'INVALID_R2_ACTION' };
  }
  return withTransaction(async tx => {
    const binding = await tx(
      'SELECT public_key_spki FROM public.identity_key_bindings WHERE identity_id=$1 FOR SHARE',
      [actor]
    );
    const activeKey = binding.rows[0]?.public_key_spki;
    if (binding.rowCount !== 1 || !verifyR2ActionProof({
      operation, identity: actor, peerIdentity: peer, fields, proof,
      activePublicKey: activeKey, now
    })) {
      return { authorized: false, statusCode: 401, code: 'INVALID_R2_IDENTITY_PROOF' };
    }
    const mutual = await tx(
      `SELECT from_identity,to_identity,is_mutual FROM public.connections
        WHERE (LOWER(from_identity)=LOWER($1) AND LOWER(to_identity)=LOWER($2))
           OR (LOWER(from_identity)=LOWER($2) AND LOWER(to_identity)=LOWER($1))
        FOR SHARE`,
      [actor, peer]
    );
    const forward = mutual.rows.some(row =>
      row.from_identity.toUpperCase() === actor &&
      row.to_identity.toUpperCase() === peer && row.is_mutual === true);
    const reverse = mutual.rows.some(row =>
      row.from_identity.toUpperCase() === peer &&
      row.to_identity.toUpperCase() === actor && row.is_mutual === true);
    if (mutual.rowCount !== 2 || !forward || !reverse) {
      return { authorized: false, statusCode: 403, code: 'NO_MUTUAL_R2_CONNECTION' };
    }
    const nonceInsert = await tx(
      `INSERT INTO public.r2_action_nonces(identity_id,nonce)
        VALUES($1,$2) ON CONFLICT DO NOTHING RETURNING nonce`,
      [actor, proof.nonce.toLowerCase()]
    );
    if (nonceInsert.rowCount !== 1) {
      return { authorized: false, statusCode: 409, code: 'R2_PROOF_REPLAY' };
    }
    const value = await action(tx, Object.freeze({
      authenticatedIdentity: actor, peerIdentity: peer, operation
    }));
    return { authorized: true, value };
  });
}

module.exports = {
  PROOF_WINDOW_MS, OPERATIONS, canonicalR2Action,
  verifyR2ActionProof, executeAuthenticatedR2Action
};
