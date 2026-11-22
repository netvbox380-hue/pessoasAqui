'use strict';
const test=require('node:test');
const assert=require('node:assert/strict');
const {
  chargeNewR2Reservation,MAX_NEW_RESERVATIONS_PER_DAY,QUOTA_CODE
}=require('../src/r2ReserveDailyBudget');

const actor='AABBCCDDEEFF0011';
test('single atomic PostgreSQL upsert charges a new reservation on UTC date',async()=>{
  const operations=[];
  const out=await chargeNewR2Reservation({
    authenticatedIdentity:actor,
    tx:async(sql,args)=>{
      operations.push({sql,args});
      return {rowCount:1,rows:[{reservations:1}]};
    }
  });
  assert.equal(out.charged,true);
  assert.equal(Object.isFrozen(out),true);
  assert.equal(operations.length,1);
  assert.match(operations[0].sql,/INSERT INTO public.r2_reserve_daily_budget/);
  assert.match(operations[0].sql,/ON CONFLICT\(identity_id,day_utc\) DO UPDATE/);
  assert.match(operations[0].sql,/WHERE public\.r2_reserve_daily_budget\.reservations < \$2/);
  assert.match(operations[0].sql,/clock_timestamp\(\) AT TIME ZONE 'UTC'/);
  assert.deepEqual(operations[0].args,[actor,MAX_NEW_RESERVATIONS_PER_DAY]);
});
test('quota exhausted throws to force rollback of prior reservation and nonce',async()=>{
  await assert.rejects(chargeNewR2Reservation({
    authenticatedIdentity:actor,
    tx:async()=>({rowCount:0,rows:[]})
  }),error=>error.code===QUOTA_CODE&&error.message===QUOTA_CODE);
});
test('invalid or forged identities never issue SQL',async()=>{
  let queries=0;
  for(const identity of ['aabbccddeeff0011','../bad','','AABBCCDDEEFF00XX']){
    await assert.rejects(chargeNewR2Reservation({
      authenticatedIdentity:identity,tx:async()=>{queries++;return {rowCount:1};}
    }),/Trusted R2/);
  }
  assert.equal(queries,0);
});
test('database error propagates, no RAM fallback or silent quota bypass',async()=>{
  await assert.rejects(chargeNewR2Reservation({
    authenticatedIdentity:actor,tx:async()=>{throw Error('PG_DOWN');}
  }),/PG_DOWN/);
});
test('quota module constructor rejects nontransaction callback',async()=>{
  await assert.rejects(chargeNewR2Reservation({
    authenticatedIdentity:actor,tx:null
  }),/Trusted R2/);
});
