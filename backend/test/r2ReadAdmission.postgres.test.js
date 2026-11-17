'use strict';

/**
 * SQL integration regression for the exact quota UPDATE in r2ReadAdmission.
 *
 * Run against a disposable LOCAL PostgreSQL 18 with migration 011 applied:
 * PESSOASAQUI_TEST_PG_URL=postgresql://...@127.0.0.1:54333/... \
 *   node --test backend/test/r2ReadAdmission.postgres.test.js
 *
 * This test REFUSES network connections except loopback port 54333 and
 * never reads DATABASE_URL. All changes are inside ROLLBACK transactions.
 */
const test=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');

const testUrl=process.env.PESSOASAQUI_TEST_PG_URL;
const allowed=(()=>{
  if(!testUrl)return false;
  try {
    const u=new URL(testUrl);
    return ['postgresql:','postgres:'].includes(u.protocol) &&
      ['127.0.0.1','localhost','[::1]'].includes(u.hostname) && u.port==='54333';
  }catch(_){return false;}
})();
if(testUrl&&!allowed)throw Error('TEST DATABASE MUST BE LOOPBACK PORT 54333');

const source=fs.readFileSync(
  path.join(__dirname,'../src/r2ReadAdmission.js'),'utf8'
);
const extracted=source.match(/\x60(UPDATE public\.r2_read_daily_budget[\s\S]*?RETURNING attempts,reserved_bytes)\x60/);
if(!extracted)throw Error('Quota UPDATE was not located: check regression test');
const quotaUpdate=extracted[1];
const MAX_BYTES=300*1024*1024;

test('EXACT application SQL is parsed and executed by isolated PostgreSQL', {
  skip:!allowed?'requires PESSOASAQUI_TEST_PG_URL pointing to 127.0.0.1:54333 with migration 011':false
},async()=>{
  const {Client}=require('pg');
  const client=new Client({connectionString:testUrl,ssl:false,connectionTimeoutMillis:3000});
  await client.connect();
  try{
    await client.query('BEGIN');
    const actor='A0A0A0A0A0A0A0A0';
    // Explicitly avoid changing persisted test fixtures.
    await client.query(
      "INSERT INTO public.r2_read_daily_budget(identity_id,day_utc) VALUES($1,(clock_timestamp() AT TIME ZONE 'UTC')::date) ON CONFLICT DO NOTHING",
      [actor]
    );
    const charge=async bytes=>client.query(quotaUpdate,[actor,bytes,24,MAX_BYTES]);
    assert.equal((await charge(1024*1024)).rowCount,1);
    const row=await client.query(
      "SELECT attempts,reserved_bytes FROM public.r2_read_daily_budget WHERE identity_id=$1 AND day_utc=(clock_timestamp() AT TIME ZONE 'UTC')::date",
      [actor]
    );
    assert.equal(row.rowCount,1);
    assert.equal(Number(row.rows[0].attempts)>=1,true);
    assert.equal((await charge(MAX_BYTES)).rowCount,0);
  } finally{
    await client.query('ROLLBACK').catch(()=>{});
    await client.end();
  }
});

test('typed quota arithmetic is present in production query',()=>{
  assert.match(quotaUpdate,/reserved_bytes <= \(\$4::bigint - \$2::bigint\)/);
});
