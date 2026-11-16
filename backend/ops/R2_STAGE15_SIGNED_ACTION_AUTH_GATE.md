# PessoasAqui — ETAPA 15: R2 signed-action authentication (STAGED)

**No HTTP/WS routes, no production integrations, no Cloudflare credentials, no deploy.**
**Prerequisite:** Etapa 14 MUST be audited; no report has been received yet.

## Protocol matched to existing Android identity architecture
The existing identityAuth.js and inviteAuth.js verify **ECDSA P-256 with SHA-256**, not Ed25519. This stage follows the existing cryptographic identity binding; do not claim compatibility with an Android client until tested.

backend/src/r2ActionAuth.js supports signed R2_RESERVE, R2_CONFIRM, R2_ATTACH, R2_READ requests with operation-specific exact fields. Domain PA-R2-ACTION-V1; canonical length-prefixed UTF-8 bytes, uppercase 16-hex identities, exact structured fields, timestamp and UUID nonce. A proof is valid within 120 seconds of backend clock.

## Mandatory transactional gate
executeAuthenticatedR2Action() requires an injected PostgreSQL withTransaction callback, and:
1. Reads authoritative identity_key_bindings.public_key_spki under FOR SHARE.
2. Verifies P-256 ECDSA signature against that binding, never a self-asserted public key.
3. Checks BOTH rows of connections as mutual under FOR SHARE.
4. Atomically INSERTs identity+nonce into r2_action_nonces with ON CONFLICT DO NOTHING.
5. Calls a server-provided callback only within that transaction with the verified actor.
6. Errors in SQL propagate; never fall back to RAM or accept a failed signature.

IMPORTANT: the action callback MUST perform database-only operations, with no R2 PUT/GET, presigned URL emission, WS dispatch or other irreversible external side effects before COMMIT.

## PREPARE ONLY migration
backend/migrations/009_r2_action_auth_PREPARE_ONLY.sql creates the anti-replay table, enables RLS, revokes public roles, and supplies an index for future bounded nonce pruning. Do not apply it to production.

## NOT DONE / deployment blockers
- The gate verifies the identity and bilateral relationship; **it does not authorize ownership of the specific R2 object**. Future READ handlers MUST check recipient_identity = authenticatedIdentity, sender_identity = peerIdentity, message/object binding, attachment state, expiry, revocation, and both-way mutual state at issue time.
- Future reserve/confirm/attach handlers MUST bind the actual DB record and object to the verified sender and peer. Never let JSON choose the DB callback, signature operation, or disable the lease.
- The real R2 ciphertext SHA-256 and byte-size check and the 2h acceptance window are separate prerequisites. The descriptor digest signed for R2_ATTACH is not proof that the blob exists.
- R2 presigned PUT/GET issuance and Android implementation are deliberately absent.
- Runtime ban/revocation checks, identity key rebinding after recovery, object ownership, rate limits, and clock-skew UX need integration verification.
- SQL lease cannot revoke an in-flight PUT; late PUT and 7d R2 lifecycle mitigation are unresolved. Orphan GC is disabled.
- Consumed nonces should be pruned only after an audited retention rule longer than the proof-validity window and clock skew; no production maintenance job is introduced.

## Antigravity — isolated audit only
Verify main HEAD, checkpoint backup/main-pre-r2-delivery-20261114, and perform these tests:
node --test backend/test/r2ActionAuth.test.js
node --test backend/test/r2MediaRegistry.test.js
node --test backend/test/r2MediaAttach.test.js
node --test backend/test/r2LeasedMediaAttach.test.js
node --test backend/test/r2UploadLeaseRegistry.test.js
node backend/run-tests.js

PostgreSQL 18 isolated migrations 004–009: replay and simultaneous nonce collisions, transaction rollback, correct key binding, unilateral/revoked connection, stale timestamp, forged signature, wrong peer, DB outage and RLS/roles. Check key binding schema, exact Android signature interoperability, lock ordering, and absence of R2 imports in server.js.

Report ALL issues. Antigravity must NOT change code, commit, deploy, alter production Supabase/Render/Cloudflare, or build Android packages.
**R2 rollout remains blocked.**
