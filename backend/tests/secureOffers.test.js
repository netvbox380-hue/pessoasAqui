'use strict';
const test=require('node:test');
const assert=require('node:assert/strict');
const crypto=require('node:crypto');
const auth=require('../src/inviteAuth');
const offers=require('../src/offerAuth');
const {registerOfferRoutesV2}=require('../src/offerRoutesV2');

function person(){
  const {privateKey,publicKey}=crypto.generateKeyPairSync('ec',{namedCurve:'prime256v1'});
  const der=publicKey.export({format:'der',type:'spki'});
  return {id:crypto.createHash('sha256').update(der).digest('hex').slice(0,16).toUpperCase(),
    pub:der.toString('base64'),privateKey};
}
const me=person(),attacker=person();
const baseOffer={
  id:'offer-12345678-1234-1234-1234-123456789abc',authorId:me.id,
  authorAlias:'Maria',profession:'Mecânica',description:'Troco óleo e pneus.',
  imageBase64:'',externalLink:'https://example.com'
};
function proof(who,operation,id,canonical,claimedId=who.id){
  const nonce=crypto.randomUUID(),timestamp=Date.now();
  const digest=offers.sha(canonical);
  const message=auth.canonicalInviteProof(operation,claimedId,id,'',digest,timestamp,nonce);
  const sign=crypto.createSign('SHA256');sign.update(message);sign.end();
  return {v:1,publicKey:who.pub,signature:sign.sign(who.privateKey).toString('base64'),nonce,timestamp};
}
function fixture(){
  const routes=new Map(),rows=new Map(),nonces=new Set(),activeOffers=new Map();
  let postgres=true,failed=false,transactionCount=0;
  const query=async(sql,args=[])=>{
    if(failed)throw Error('database offline');
    if(sql.includes('SELECT public_key_spki FROM identity_key_bindings'))
      return {rows:[],rowCount:0};
    if(sql.includes('SELECT id FROM identities WHERE UPPER(id)'))
      return {rows:args[0]===me.id?[{id:me.id}]:[],rowCount:args[0]===me.id?1:0};
    if(sql.includes('INSERT INTO offer_action_nonces')){
      const key=args.join(':');if(nonces.has(key))return {rowCount:0,rows:[]};
      nonces.add(key);return {rowCount:1,rows:[{nonce:args[1]}]};
    }
    if(sql.includes('SELECT author_id FROM offers_secure')) {
      const row=rows.get(args[0]);return {rowCount:row?1:0,rows:row?[{author_id:row.authorId}]:[]};
    }
    if(sql.includes('COUNT(*)::integer AS count'))return {
      rowCount:1,rows:[{count:[...rows.values()].filter(x=>x.authorId===args[0]).length}]
    };
    if(sql.includes('INSERT INTO offers_secure')){
      const [id,owner,alias,profession,description,imageBase64,externalLink]=args;
      const old=rows.get(id);
      if(owner!==me.id||(old&&old.authorId!==owner))return {rowCount:0,rows:[]};
      rows.set(id,{id,authorId:owner,authorAlias:alias,profession,description,imageBase64,externalLink});
      return {rowCount:1,rows:[{id,created_at:new Date().toISOString()}]};
    }
    if(sql.includes('DELETE FROM offers_secure')){
      const old=rows.get(args[0]);if(!old||old.authorId!==args[1])return {rowCount:0,rows:[]};
      rows.delete(args[0]);return {rowCount:1,rows:[{id:args[0]}]};
    }
    throw Error('Unexpected SQL: '+sql.slice(0,80));
  };
  const app={post:(path,fn)=>routes.set('POST '+path,fn),
    delete:(path,fn)=>routes.set('DELETE '+path,fn),
    get:(path,fn)=>routes.set('GET '+path,fn)};
  registerOfferRoutesV2(app,{query,withTransaction:async fn=>{transactionCount++;return fn(query)},
    isPostgres:()=>postgres,activeOffers,requireDatabase:true});
  async function request(method,path,body={}){
    let code=200,data;
    const res={status(status){code=status;return this;},json(obj){data=obj;return this;}};
    await routes.get(method+' '+path)({body,params:{id:body.id}},res);
    return {code,data};
  }
  const publish=(offer=baseOffer,who=me)=>
    request('POST','/api/offers/publish',
      {...offer,identityProof:proof(who,'OFFER_PUBLISH',offer.id,offers.canonicalPublish(offer))});
  const remove=(offerId=baseOffer.id,who=me)=>
    request('POST','/api/offers/remove',{offerId,authorId:who.id,
      identityProof:proof(who,'OFFER_DELETE',offerId,offers.canonicalDelete(offerId))});
  return {publish,remove,request,rows,nonces,activeOffers,
    offline:()=>{postgres=false},fail:()=>{failed=true},get tx(){return transactionCount}};
}
test('publisher creates and deletes own signed offer',async()=>{
  const f=fixture();
  assert.equal((await f.publish()).code,200);
  assert.equal(f.rows.size,1);
  assert.equal((await f.remove()).code,200);
  assert.equal(f.rows.size,0);
});
test('signed identity cannot be substituted',async()=>{
  const f=fixture();
  const forged={...baseOffer,authorId:attacker.id};
  assert.equal((await f.publish(forged,me)).code,401);
  assert.equal(f.rows.size,0);
});
test('body tampering is rejected',async()=>{
  const f=fixture();
  const altered={...baseOffer,description:'Pague adiantado'};
  const originalProof=proof(me,'OFFER_PUBLISH',baseOffer.id,offers.canonicalPublish(baseOffer));
  assert.equal((await f.request('POST','/api/offers/publish',{
    ...altered,identityProof:originalProof
  })).code,401);
});
test('replayed signed publishing request cannot be accepted twice',async()=>{
  const f=fixture(),body={...baseOffer,
    identityProof:proof(me,'OFFER_PUBLISH',baseOffer.id,offers.canonicalPublish(baseOffer))};
  assert.equal((await f.request('POST','/api/offers/publish',body)).code,200);
  assert.equal((await f.request('POST','/api/offers/publish',body)).code,409);
});
test('stranger cannot delete offer from another identity',async()=>{
  const f=fixture();assert.equal((await f.publish()).code,200);
  assert.equal((await f.remove(baseOffer.id,attacker)).code,403);
  assert.equal(f.rows.size,1);
});
test('legacy DELETE is rejected with required update',async()=>{
  const f=fixture();assert.equal((await f.request('DELETE','/api/offers/'+baseOffer.id)).code,401);
});
test('backend refuses unauthenticated public nearby listing',async()=>{
  const f=fixture();await f.publish();
  const out=await f.request('GET','/api/offers/nearby');
  assert.equal(out.data.offers.length,0);
});
test('database unavailable rejects writes',async()=>{
  const f=fixture();f.offline();
  assert.equal((await f.publish()).code,503);
  assert.equal((await f.remove()).code,503);
});
test('invalid links and oversized photos are rejected',async()=>{
  const f=fixture();
  const bad={...baseOffer,externalLink:'javascript:alert(1)'};
  assert.equal((await f.publish(bad)).code,400);
  const huge={...baseOffer,imageBase64:'a'.repeat(160100)};
  assert.equal((await f.publish(huge)).code,400);
});
test('offers payload canonicalization uses UTF-16 code unit lengths',()=>{
  const o={...baseOffer,description:'🐈'};
  assert.ok(offers.canonicalPublish(o).includes('2:🐈'));
});
