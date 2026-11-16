'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const registry = require('../src/r2MediaRegistry');
const { reserveLeasedMediaObject, markLeasedMediaUploaded } = require('../src/r2UploadLeaseRegistry');
const K = 'pending/12/34/12345678-1234-4234-8234-123456789abc';
const H = 'a'.repeat(64);
const reservation = {
  objectKey: K, messageId: 'msg-1',
  senderIdentity: 'SENDER0001', recipientIdentity: 'RECEIVER01'
};
test('no unleased reservation or upload API is exported', () => {
  assert.equal('reserveMediaObject' in registry, false);
  assert.equal('markMediaUploaded' in registry, false);
  assert.equal(typeof registry.attachMediaObject, 'function');
});
test('leased reservation writes durable DB-authored lease and generation', async () => {
  const calls = [];
  const value = await reserveLeasedMediaObject({
    query: async (sql,args) => {
      calls.push({sql,args});
      return {rowCount:1,rows:[{upload_generation:1,upload_lease_expires_at:'2026-11-16T15:00:00Z'}]};
    }, ...reservation
  });
  assert.equal(value.reserved,true);
  assert.equal(value.generation,1);
  assert.match(calls[0].sql,/clock_timestamp\(\)/);
  assert.match(calls[0].sql,/ON CONFLICT DO NOTHING/);
});
test('idempotent reservation never extends lease', async () => {
  const calls=[];
  const value=await reserveLeasedMediaObject({
    query:async sql=>{
      calls.push(sql);
      return sql.includes('INSERT INTO') ? {rowCount:0}:{
        rows:[{object_key:K,sender_identity:'sender0001',
          recipient_identity:'receiver01',state:'RESERVED',
          upload_generation:1,lease_active:true,
          upload_lease_expires_at:'2026-11-16T15:00:00Z'}]
      };
    },...reservation
  });
  assert.equal(value.duplicate,true);
  assert.equal(calls.filter(x=>x.includes('UPDATE')).length,0);
});
test('collision cannot repoint media object or identities', async () => {
  const value=await reserveLeasedMediaObject({
    query:async sql=>sql.includes('INSERT INTO')?{rowCount:0}:{
      rows:[{object_key:'other-key',sender_identity:'sender0001',
        recipient_identity:'receiver01',state:'RESERVED',upload_generation:1,lease_active:true}]
    },...reservation
  });
  assert.equal(value.reserved,false);
  assert.equal(value.code,'RESERVATION_CONFLICT');
});
test('leased upload confirmation checks generation, state and DB lease', async () => {
  const calls=[];
  const value=await markLeasedMediaUploaded({
    query:async(sql,args)=>{calls.push({sql,args});return {rowCount:1};},
    objectKey:K,messageId:'msg-1',generation:1,contentSha256:H,contentBytes:1024
  });
  assert.equal(value.uploaded,true);
  assert.match(calls[0].sql,/upload_generation=\$3/);
  assert.match(calls[0].sql,/upload_lease_expires_at > clock_timestamp\(\)/);
});
test('replayed upload requires original hash and byte count', async () => {
  for(const [hash,expected] of [[H,true],['b'.repeat(64),false]]){
    const value=await markLeasedMediaUploaded({
      query:async sql=>sql.includes('UPDATE')?{rowCount:0}:{
        rows:[{state:'UPLOADED',upload_generation:1,content_sha256:hash,content_bytes:'1024'}]
      },objectKey:K,messageId:'msg-1',generation:1,contentSha256:H,contentBytes:1024
    });
    assert.equal(value.uploaded,expected);
  }
});
test('invalid object identifiers, byte count and database outage fail closed', async () => {
  await assert.rejects(reserveLeasedMediaObject({
    query:async()=>{throw new Error('SHOULD_NOT_RUN');},...reservation,objectKey:'../private'
  }),/Invalid R2 object key/);
  assert.throws(()=>registry.validateContentDescriptor({contentSha256:H,contentBytes:0}),/Invalid/);
  assert.throws(()=>registry.validateContentDescriptor({contentSha256:'bad',contentBytes:3}),/Invalid/);
  assert.throws(()=>registry.validateContentDescriptor({contentSha256:H,contentBytes:26214401}),/Invalid/);
  await assert.rejects(markLeasedMediaUploaded({
    query:async()=>{throw Error('POSTGRES_DOWN');},
    objectKey:K,messageId:'msg-1',generation:1,contentSha256:H,contentBytes:1024
  }),/POSTGRES_DOWN/);
});
