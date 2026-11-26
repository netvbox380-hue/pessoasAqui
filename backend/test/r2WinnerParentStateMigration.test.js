'use strict';
const test=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const migration=fs.readFileSync(
 path.join(__dirname,'../migrations/023_r2_allow_parent_winner_attach_PREPARE_ONLY.sql'),
 'utf8'
);
const attach=fs.readFileSync(
 path.join(__dirname,'../src/r2StagedWinningMediaAttach.js'),'utf8'
);

test('023 is additive PREPARE ONLY; explicit false-by-default discriminator',()=>{
 assert.match(migration,/PREPARE ONLY/);
 assert.match(migration,/BEGIN;/);
 assert.match(migration,/COMMIT;/);
 assert.match(migration,/r2_winner_attach_mode boolean NOT NULL DEFAULT FALSE/);
 assert.match(migration,/r2_winner_attach_mode=FALSE AND state IN \('RESERVED'/);
});
test('winner branch requires exact RESERVED->ATTACHED with mode switch',()=>{
 assert.match(migration,/OLD.state='RESERVED' AND NEW.state='ATTACHED'/);
 assert.match(migration,/OLD.r2_winner_attach_mode=FALSE/);
 assert.match(migration,/NEW.r2_winner_attach_mode=TRUE/);
 assert.match(migration,/OLD.upload_lease_expires_at<=clock_timestamp\(\)/);
 assert.match(migration,/NEW.upload_generation IS DISTINCT FROM OLD.upload_generation/);
});
test('winner validates verified physical claim and exact E2EE FK',()=>{
 assert.match(migration,/FROM public.message_r2_winning_attempts w/);
 assert.match(migration,/JOIN public.message_r2_upload_attempts a/);
 assert.match(migration,/JOIN public.r2_attempt_verification_claims c/);
 assert.match(migration,/JOIN public.e2ee_messages e/);
 assert.match(migration,/e.r2_winning_object_key=w.object_key/);
 assert.match(migration,/a.state='VERIFIED'/);
 assert.match(migration,/c.state='COMPLETED'/);
 assert.match(migration,/c.completed_at IS NOT NULL/);
 assert.match(migration,/NOT EXISTS \(\s*SELECT 1 FROM public.message_delivery_receipts/);
});
test('winner parent cannot be handed to legacy delete-by-logical-key GC',()=>{
 assert.match(migration,/OLD.r2_winner_attach_mode=TRUE/);
 assert.match(migration,/pending audited GC/);
 assert.match(migration,/OR \(r2_winner_attach_mode=TRUE AND state='ATTACHED'/);
});
test('legacy state machine and orphan guard remain present',()=>{
 for(const s of [
  "OLD.state='RESERVED' AND NEW.state='UPLOADED'",
  "OLD.state='UPLOADED' AND NEW.state='ATTACHED'",
  "OLD.state='ATTACHED' AND NEW.state='DELETE_QUEUED'",
  "OLD.state='DELETE_QUEUED' AND NEW.state='DELETED'",
  "NEW.state='ORPHAN_QUEUED'",
  "NEW.state='ORPHAN_DELETED'",
  "OLD.upload_lease_expires_at > clock_timestamp()-interval '3 hours'",
  'public.message_r2_orphan_outbox',
  'public.message_r2_delete_outbox'
 ])assert.ok(migration.includes(s),s);
});
test('staged attach changes mode and state in the same UPDATE',()=>{
 assert.match(attach,/SET state='ATTACHED',r2_winner_attach_mode=TRUE/);
 assert.match(attach,/WHERE message_id=\$1 AND state='RESERVED'/);
 assert.match(attach,/R2_ATTACH_PARENT_TRANSITION_FAILED/);
 assert.doesNotMatch(attach,/state='UPLOADED'/);
});
