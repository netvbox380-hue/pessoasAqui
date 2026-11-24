'use strict';
const test=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const sql=fs.readFileSync(path.join(__dirname,
  '../migrations/020_r2_winning_attempt_binding_PREPARE_ONLY.sql'),'utf8');

test('20 is an additive PREPARE ONLY migration with explicit transaction',()=>{
  assert.match(sql,/PREPARE ONLY/);
  assert.match(sql,/BEGIN;/);
  assert.match(sql,/COMMIT;/);
  assert.doesNotMatch(sql,/\bDROP TABLE\b/i);
  assert.doesNotMatch(sql,/\bUPDATE\s+public\.message_r2_objects\b/i);
});
test('claim FK matches exact key message actor and physical generation',()=>{
  assert.match(sql,/r2_claim_attempt_exact_binding/);
  assert.match(sql,/FOREIGN KEY\s*\(object_key,message_id,identity_id,generation\)/);
  assert.match(sql,/REFERENCES public\.message_r2_upload_attempts/);
});
test('winner links to full physical claim including hash and bytes',()=>{
  assert.match(sql,/CREATE TABLE IF NOT EXISTS public\.message_r2_winning_attempts/);
  assert.match(sql,/message_id varchar\(256\) PRIMARY KEY/);
  assert.match(sql,/object_key text NOT NULL UNIQUE/);
  assert.match(sql,/r2_winner_verified_claim_exact/);
  assert.match(sql,/FOREIGN KEY\(claim_id,object_key,message_id,sender_identity,\s*generation,content_sha256,content_bytes\)/);
  assert.match(sql,/r2_claim_winner_exact_reference/);
});
test('winner cannot be reassigned or removed by ordinary SQL',()=>{
  assert.match(sql,/BEFORE UPDATE OR DELETE ON public\.message_r2_winning_attempts/);
  assert.match(sql,/R2 winning attempt is permanently immutable/);
  assert.match(sql,/USING ERRCODE='23514'/);
});
test('verification claim descriptor cannot be swapped on retry',()=>{
  assert.match(sql,/NEW\.content_sha256 IS DISTINCT FROM OLD\.content_sha256/);
  assert.match(sql,/NEW\.content_bytes IS DISTINCT FROM OLD\.content_bytes/);
  assert.match(sql,/NEW\.generation IS DISTINCT FROM OLD\.generation/);
  assert.match(sql,/BEFORE UPDATE ON public\.r2_attempt_verification_claims/);
});
test('public and client DB roles cannot read or mutate new winner table',()=>{
  assert.match(sql,/ALTER TABLE public\.message_r2_winning_attempts ENABLE ROW LEVEL SECURITY/);
  assert.match(sql,/REVOKE ALL ON public\.message_r2_winning_attempts FROM PUBLIC/);
  assert.match(sql,/REVOKE ALL ON public\.message_r2_winning_attempts FROM anon/);
  assert.match(sql,/REVOKE ALL ON public\.message_r2_winning_attempts FROM authenticated/);
});
