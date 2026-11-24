'use strict';
const test=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const read=name=>fs.readFileSync(path.join(__dirname,'../migrations/',name),'utf8');
const sql=read('021_r2_winner_completed_state_gate_PREPARE_ONLY.sql');
test('migration 021 is staged, additive and transactional',()=>{
 assert.match(sql,/PREPARE ONLY/);
 assert.match(sql,/BEGIN;/);assert.match(sql,/COMMIT;/);
 assert.doesNotMatch(sql,/DROP TABLE/i);
});
test('before insert rejects RUNNING or FAILED claim',()=>{
 assert.match(sql,/BEFORE INSERT ON public.message_r2_winning_attempts/);
 assert.match(sql,/c\.state='COMPLETED'/);
 assert.match(sql,/c\.completed_at IS NOT NULL/);
 assert.match(sql,/IF NOT verified_claim OR NOT verified_attempt OR NOT valid_parent/);
 assert.match(sql,/USING ERRCODE='23514'/);
});
test('winner must have VERIFIED matching attempt and valid logical parent',()=>{
 assert.match(sql,/a\.state='VERIFIED'/);
 assert.match(sql,/a\.object_key=NEW\.object_key/);
 assert.match(sql,/a\.recipient_identity=NEW\.recipient_identity/);
 assert.match(sql,/m\.state='RESERVED'/);
 assert.match(sql,/m\.upload_lease_expires_at>clock_timestamp\(\)/);
});
test('migration explicitly preserves physical verification and access gates',()=>{
 assert.match(sql,/does NOT prove/i);
 assert.match(sql,/direct SQL/i);
});
