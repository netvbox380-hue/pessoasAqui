'use strict';
const test=require('node:test');
const assert=require('node:assert/strict');
const {CHANNEL,recordCommittedRevocation,dispatchCommittedRevocation,
  replayRecentRevocations}=require('../src/r2RevocationOutbox');
const uuid='12345678-1234-4234-8234-123456789abc';
const A='AAAABBBBCCCCDDDD',B='1111222233334444';

test('write and notify use same injected transaction callback',async()=>{
  const calls=[];
  const query=async(sql,args)=>{
    calls.push({sql,args});
    if(sql.includes('INSERT INTO public.r2_stream_revocation_events'))
      return {rowCount:1,rows:[{event_id:args[0]}]};
    if(sql.includes('pg_notify'))return {rowCount:1,rows:[{}]};
    throw Error('Unexpected SQL');
  };
  const r=await recordCommittedRevocation({
    query,event:{kind:'PAIR',identityA:A,identityB:B},
    assertRevocationApplied:true
  });
  assert.match(r.eventId,/^[0-9a-f-]{36}$/);
  assert.equal(calls.length,2);
  assert.deepEqual(calls[0].args.slice(1),['PAIR',null,A,B]);
  assert.deepEqual(calls[1].args,[CHANNEL,r.eventId]);
});
test('cannot publish without trusted applied revocation or valid ref',async()=>{
  const query=async()=>{throw Error('SHOULD_NOT_QUERY');};
  await assert.rejects(recordCommittedRevocation({
    query,event:{kind:'MESSAGE',messageId:'m'}
  }),/transaction required/);
  for(const event of [
    {kind:'MESSAGE',messageId:''},
    {kind:'PAIR',identityA:'bad',identityB:B},
    {kind:'PAIR',identityA:A,identityB:A},
    {kind:'BOGUS'}
  ])await assert.rejects(recordCommittedRevocation({
    query,event,assertRevocationApplied:true
  }),/Invalid R2/);
});
test('untrusted payload only authorizes lookup by event UUID',async()=>{
  const queries=[],calls=[];
  const query=async(sql,args)=>{
    queries.push({sql,args});
    return {rowCount:1,rows:[{event_kind:'MESSAGE',message_id:'message-1',
      identity_a:null,identity_b:null}]};
  };
  const registry={
    revokeMessage:msg=>{calls.push(msg);return 2;},
    revokePair:()=>{throw Error('Should not call pair');}
  };
  const r=await dispatchCommittedRevocation({query,registry,eventId:uuid});
  assert.deepEqual(r,{dispatched:true,stopped:2});
  assert.deepEqual(calls,['message-1']);
  assert.equal(queries.length,1);
  assert.deepEqual(queries[0].args,[uuid]);
});
test('pair revoke is driven only by persisted event, not notification JSON',async()=>{
  const pairs=[];
  const query=async()=>({rowCount:1,rows:[{
    event_kind:'PAIR',identity_a:A,identity_b:B,
    message_id:null
  }]});
  const registry={
    revokeMessage:()=>0,
    revokePair:(a,b)=>{pairs.push([a,b]);return 1;}
  };
  const r=await dispatchCommittedRevocation({query,registry,eventId:uuid});
  assert.equal(r.stopped,1);
  assert.deepEqual(pairs,[[A,B]]);
});
test('missing event fails closed and does not revoke arbitrary stream',async()=>{
  const query=async()=>({rowCount:0,rows:[]});
  const registry={revokeMessage:()=>{throw Error('called');},revokePair:()=>0};
  const r=await dispatchCommittedRevocation({query,registry,eventId:uuid});
  assert.deepEqual(r,{dispatched:false,reason:'EVENT_NOT_FOUND'});
});
test('malformed notification or malformed persisted event is rejected',async()=>{
  const registry={revokeMessage:()=>0,revokePair:()=>0};
  await assert.rejects(dispatchCommittedRevocation({
    query:async()=>({rowCount:0,rows:[]}),registry,eventId:'{evil}'
  }),/Invalid committed/);
  await assert.rejects(dispatchCommittedRevocation({
    query:async()=>({rowCount:1,rows:[{event_kind:'BAD'}]}),
    registry,eventId:uuid
  }),/R2_REVOCATION_EVENT_INVALID/);
});
test('replay recent events with bounded scan and idempotent dispatcher',async()=>{
  const sent=[];
  const query=async(sql,args)=>{
    if(sql.includes('ORDER BY created_at'))return {
      rowCount:2,rows:[{event_id:uuid},
        {event_id:'aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee'}]
    };
    return {rowCount:1,rows:[{
      event_kind:'MESSAGE',message_id:'m',identity_a:null,identity_b:null
    }]};
  };
  const registry={revokeMessage:msg=>{sent.push(msg);return sent.length===1?1:0;},
    revokePair:()=>0};
  const r=await replayRecentRevocations({query,registry,limit:10});
  assert.deepEqual(r,{dispatched:2,stopped:1});
  assert.deepEqual(sent,['m','m']);
});
test('bounded replay fails closed on overflow instead of skipping events',async()=>{
  const query=async()=>({rowCount:3,rows:[
    {event_id:uuid},{event_id:uuid},{event_id:uuid}
  ]});
  await assert.rejects(replayRecentRevocations({
    query,registry:{},limit:2
  }),/R2_REVOCATION_REPLAY_OVERFLOW/);
});
test('database query failures propagate without RAM fallback',async()=>{
  const registry={revokeMessage:()=>0,revokePair:()=>0};
  await assert.rejects(dispatchCommittedRevocation({
    query:async()=>{throw Error('DATABASE_DOWN');},registry,eventId:uuid
  }),/DATABASE_DOWN/);
});
