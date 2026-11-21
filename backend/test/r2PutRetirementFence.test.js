'use strict';
const test=require('node:test');
const assert=require('node:assert/strict');
const {parsedExpiry,issuePutWithDurableRetirementFence,
 sealPutKeyForRetirement,authorizePhysicalR2Delete,SAFETY_SECONDS}=
 require('../src/r2PutRetirementFence');

const bucket='pessoasaqui-media';
const objectKey='pending/12/34/12345678-1234-4234-8234-123456789abc';
const account='a'.repeat(32);
const now='2026-11-21T12:00:00.000Z';
function url(date='20261121T120000Z',expires=900){
 const u=new URL('https://'+account+'.r2.cloudflarestorage.com/'+bucket+'/'+objectKey);
 for(const [key,value] of Object.entries({
   'X-Amz-Algorithm':'AWS4-HMAC-SHA256',
   'X-Amz-Expires':String(expires),
   'X-Amz-SignedHeaders':'content-type;host;if-none-match',
   'X-Amz-Signature':'f'.repeat(64),
   'X-Amz-Credential':'test',
   'X-Amz-Date':date
 }))u.searchParams.set(key,value);
 return u.toString();
}
function database(opts={}){
 const statements=[];
 const tx=async(sql,args=[])=>{
   statements.push({sql,args});
   if(opts.down)throw Error('DATABASE_DOWN');
   if(sql.includes('SELECT sealed_at FROM public.r2_put_capability_windows')){
     return {rowCount:opts.existing?1:0,
       rows:opts.existing?[{sealed_at:opts.sealed?now:null}]:[]};
   }
   if(sql.includes('SELECT clock_timestamp() AS now'))
     return {rowCount:1,rows:[{now}]};
   if(sql.includes('INSERT INTO public.r2_put_capability_windows'))
     return {rowCount:opts.sealed?0:1,rows:opts.sealed?[]:[{object_key:objectKey}]};
   if(sql.includes('UPDATE public.r2_put_capability_windows'))
     return {rowCount:opts.unknown?0:1,
       rows:opts.unknown?[]:[{sealed_at:now,expires_at:now}]};
   if(sql.includes('AND sealed_at IS NOT NULL'))
     return {rowCount:opts.elapsed?1:0,
       rows:opts.elapsed?[{object_key:objectKey}]:[]};
   throw Error('UNEXPECTED_SQL:'+sql);
 };
 return {statements,tx,withTransaction:async callback=>callback(tx)};
}
async function issue(db,extra={}){
 return issuePutWithDurableRetirementFence({
   withTransaction:db.withTransaction,
   signPutUrl:async()=>url(),bucket,objectKey,
   authorizedReservation:{reserved:true,generation:1,objectKey,bucket},
   ...extra
 });
}
test('signed URL timestamps use exact SigV4 date and bounded expiry',()=>{
 assert.equal(parsedExpiry(url()).issued,Date.parse(now));
 assert.equal(parsedExpiry(url()).expires,Date.parse(now)+900000);
 assert.throws(()=>parsedExpiry(url('20261321T120000Z')),/Invalid signed PUT date/);
 assert.throws(()=>parsedExpiry(url('20261121T120000Z',901)),/Invalid signed PUT issuance time/);
});
test('new key recorded before a signed URL leaves trusted transaction',async()=>{
 const db=database();
 const cap=await issue(db);
 assert.equal(cap.method,'PUT');
 assert.equal(db.statements.length,3);
 const insert=db.statements[2];
 assert.match(insert.sql,/ON CONFLICT \(object_key\) DO UPDATE/);
 assert.match(insert.sql,/sealed_at IS NULL/);
 assert.equal(insert.args[0],objectKey);
 assert.equal(insert.args[1],new Date(Date.parse(now)+900000).toISOString());
});
test('reissue takes the max of expiration and fails if key was sealed',async()=>{
 const db=database({existing:true});
 await issue(db);
 assert.match(db.statements[2].sql,/GREATEST/);
 await assert.rejects(issue(database({existing:true,sealed:true})),/R2_PUT_KEY_RETIRED/);
});
test('late or early signature timestamp rejected without storing capability',async()=>{
 for(const date of ['20261121T115800Z','20261121T120200Z']){
   const db=database();
   await assert.rejects(issue(db,{signPutUrl:async()=>url(date)}),
     /R2_PUT_SIGNED_TIME_OUT_OF_RANGE/);
   assert.equal(db.statements.some(s=>s.sql.includes('INSERT INTO public.r2_put_capability_windows')),false);
 }
});
test('failed DB write never returns signed URL to outside caller',async()=>{
 await assert.rejects(issue(database({sealed:true})),/R2_PUT_KEY_RETIRED/);
 await assert.rejects(issue(database({down:true})),/DATABASE_DOWN/);
});
test('sealing is monotonic and missing legacy window blocks deletion',async()=>{
 const d=database();
 assert.deepEqual(await sealPutKeyForRetirement({tx:d.tx,objectKey}),{sealed:true});
 assert.match(d.statements[0].sql,/COALESCE\(sealed_at,clock_timestamp\(\)\)/);
 await assert.rejects(sealPutKeyForRetirement({
   tx:database({unknown:true}).tx,objectKey
 }),/R2_PUT_WINDOW_UNKNOWN/);
});
test('physical R2 delete is denied until sealed PUT expiry plus safety',async()=>{
 const denied=await authorizePhysicalR2Delete({
   tx:database({elapsed:false}).tx,objectKey
 });
 assert.equal(denied.allowed,false);
 assert.equal(denied.code,'R2_PUT_DELETE_BLOCKED');
 const db=database({elapsed:true});
 assert.equal((await authorizePhysicalR2Delete({
   tx:db.tx,objectKey
 })).allowed,true);
 assert.deepEqual(db.statements[0].args,[objectKey,SAFETY_SECONDS]);
 assert.match(db.statements[0].sql,/FOR UPDATE/);
});
test('unauthorized reservation and invalid identifiers fail before issuing',async()=>{
 const db=database();
 await assert.rejects(issue(db,{authorizedReservation:{reserved:false}}),
   /requires server-verified reservation/);
 await assert.rejects(issue(db,{objectKey:'../fake'}),
   /Invalid R2 object key/);
 assert.equal(db.statements.some(s=>s.sql.includes('INSERT INTO public.r2_put_capability_windows')),false);
});
