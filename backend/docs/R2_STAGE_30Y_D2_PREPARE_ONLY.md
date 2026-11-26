# 30Y-D2 — Physical winner READ (PREPARE ONLY)

Checkpoint: backup/main-pre-r2-d2-20261126 (0462183086d996f3ec4e48cf2fd2fdc6837095da).
Implementation/test snapshot: b1d4b8d08e40f7932e7734e54661b54495c8d910.

Explicit server-side entry point: createStagedWinningR2ReadHandler. It uses signed R2_READ, existing replay protection and the same persistent 24 reads / 300 MiB daily budget. The legacy entry point is separate and excludes winner attachments. No routes activated. Migrations 022–023 required for winner path. No new migration, no ACK, no public GET URL.

The winner authorization joins the parent, immutable winner, physical attempt, completed claim and E2EE message; exact signed physical key, identities, state, descriptor, expiry and terminal receipt are checked. The stream-start gate rechecks identity binding and bilateral connection; registry and reconciliation preserve winner context. Gateway checks again periodically (2 seconds between completed checks) and before successful completion. This is not a guarantee of zero bytes after revocation or a hard upper bound on DB query latency. Physical integrity is checked at EOF; client must discard partial/unverified data and acknowledge only after verified persistence.

## Independent audit required
Run npm test from backend using isolated environment; run node --test backend/tests/r2WinningRecipientRead.test.js from root. Tests added are mocked SQL contract tests, not proof of actual PostgreSQL behavior. Apply migrations 001–023 only to disposable PostgreSQL 18.1 port 54333. Verify all joins and locks against real rows, negative cases (wrong key, abandoned attempt, claim mismatch, identity mismatch/rotation, cancellation, expiry, terminal receipt), quota concurrency (2/10/50), replay, legacy regression, GET exact physical key, revocation during GET/backpressure/stream, listener outage, periodic checks, digest and length mismatches, no ACK. Check deadlocks, query latency, outbox races, terminalization and identity lifecycle. Report exact counts and commands; no code changes, commits or deployments.

## Remaining blockers
Actual R2 behavior, PUT races, conditional writes, bucket immutability, least privilege, privileged SQL forgery, safe GC, retention, Android integration and local persistence ACK. No production authorization. No tests executed by author at time of this note.
