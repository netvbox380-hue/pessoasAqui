'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const {
  LEASE_MINUTES, reserveLeasedMediaObject, markLeasedMediaUploaded
} = require('../src/r2UploadLeaseRegistry');

const objectKey = 'pending/12/34/12345678-1234-4234-8234-123456789abc';
const reservation = {
  objectKey, messageId: 'msg-lease-1',
  senderIdentity: 'AAAABBBBCCCCDDDD', recipientIdentity: '1111222233334444'
};
const contentSha256 = 'b'.repeat(64);
const upload = {
  objectKey, messageId: 'msg-lease-1',
  generation: 1, contentSha256, contentBytes: 200
};

test('reservation inserts DB-owned 60 minute lease and immutable generation', async () => {
  const calls = [];
  const result = await reserveLeasedMediaObject({
    ...reservation,
    query: async (sql,args) => {
      calls.push({sql,args});
      return {rowCount:1,rows:[{
        object_key:objectKey,upload_generation:1,
        upload_lease_expires_at:'2026-11-16T15:00:00Z'
      }]};
    }
  });
  assert.equal(result.reserved,true);
  assert.equal(result.duplicate,false);
  assert.equal(result.generation,1);
  assert.deepEqual(calls[0].args.slice(-2),[LEASE_MINUTES,1]);
  assert.match(calls[0].sql,/clock_timestamp\(\)/);
  assert.match(calls[0].sql,/ON CONFLICT DO NOTHING/);
});

test('replayed reservation does not extend lease, and must match original binding', async () => {
  let calls = 0;
  const query = async sql => {
    calls++;
    if(sql.includes('INSERT'))return {rowCount:0,rows:[]};
    return {rows:[{
      object_key:objectKey, sender_identity:reservation.senderIdentity,
      recipient_identity:reservation.recipientIdentity, state:'RESERVED',
      upload_generation:1, upload_lease_expires_at:'2026-11-16T15:00:00Z',
      lease_active:true
    }]};
  };
  const result = await reserveLeasedMediaObject({...reservation,query});
  assert.equal(result.duplicate,true);
  assert.equal(calls,2);
});

test('expired lease cannot be refreshed by retry', async()=>{
  const query=async sql=>sql.includes('INSERT') ?
    {rowCount:0} : {rows:[{
      object_key:objectKey,sender_identity:reservation.senderIdentity,
      recipient_identity:reservation.recipientIdentity,
      state:'RESERVED',upload_generation:1,lease_active:false
    }]};
  assert.equal((await reserveLeasedMediaObject({...reservation,query})).code,'LEASE_EXPIRED');
});

test('changed object binding is a conflict even with same message ID', async()=>{
  const query=async sql=>sql.includes('INSERT') ?
    {rowCount:0} : {rows:[{
      object_key:'pending/12/34/aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
      sender_identity:reservation.senderIdentity,
      recipient_identity:reservation.recipientIdentity,
      state:'RESERVED',upload_generation:1,lease_active:true
    }]};
  const result=await reserveLeasedMediaObject({...reservation,query});
  assert.equal(result.code,'RESERVATION_CONFLICT');
});

test('upload confirmation accepts only current generation, lease and RESERVED',async()=>{
  const calls=[];
  const result=await markLeasedMediaUploaded({...upload,query:async(sql,args)=>{
    calls.push({sql,args});return {rowCount:1};
  }});
  assert.equal(result.uploaded,true);
  assert.equal(calls.length,1);
  assert.match(calls[0].sql,/upload_generation=\$3/);
  assert.match(calls[0].sql,/state='RESERVED'/);
  assert.match(calls[0].sql,/upload_lease_expires_at > clock_timestamp\(\)/);
  assert.deepEqual(calls[0].args,[objectKey,upload.messageId,1,contentSha256,200]);
});

test('expired upload fails closed without accepting late completion',async()=>{
  const query=async sql=>sql.includes('UPDATE') ? {rowCount:0} :
    {rows:[{state:'RESERVED',upload_generation:1,lease_active:false}]};
  const result=await markLeasedMediaUploaded({...upload,query});
  assert.deepEqual(result,{uploaded:false,code:'LEASE_EXPIRED'});
});

test('an orphaned key cannot be revived even if digest matches',async()=>{
  const query=async sql=>sql.includes('UPDATE') ? {rowCount:0} :
    {rows:[{state:'ORPHAN_QUEUED',upload_generation:1,
      lease_active:false,content_sha256:contentSha256,content_bytes:'200'}]};
  assert.equal((await markLeasedMediaUploaded({...upload,query})).code,'STATE_OR_CONTENT_CONFLICT');
});

test('exact upload confirmation retry is idempotent while no mutation runs',async()=>{
  const query=async sql=>sql.includes('UPDATE') ? {rowCount:0} :
    {rows:[{state:'UPLOADED',upload_generation:1,content_sha256:contentSha256,content_bytes:'200'}]};
  assert.deepEqual(await markLeasedMediaUploaded({...upload,query}),
    {uploaded:true,duplicate:true,state:'UPLOADED'});
});

test('wrong generation, invalid bytes and invalid key cannot authorize upload',async()=>{
  let calls=0;
  const query=async sql=>{calls++; return {rowCount:0,rows:[{upload_generation:1,state:'RESERVED'}]};};
  await assert.rejects(markLeasedMediaUploaded({...upload,generation:0,query}),/Invalid leased media identity/);
  await assert.rejects(markLeasedMediaUploaded({...upload,contentBytes:0,query}),/Invalid encrypted object descriptor/);
  await assert.rejects(markLeasedMediaUploaded({...upload,objectKey:'../escape',query}),/Invalid R2 object key/);
  assert.equal(calls,0);
});

test('database failures propagate instead of declaring success',async()=>{
  await assert.rejects(reserveLeasedMediaObject({
    ...reservation,query:async()=>{throw Error('DB_DOWN');}
  }),/DB_DOWN/);
  await assert.rejects(markLeasedMediaUploaded({
    ...upload,query:async()=>{throw Error('DB_DOWN');}
  }),/DB_DOWN/);
});
