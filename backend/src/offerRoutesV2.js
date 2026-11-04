'use strict';
/**
 * Ofertas v2: POST/DELETE autorizados por assinatura EC P-256.
 * Publicações persistentes nunca são tratadas como prova de proximidade.
 * Distribuição dos anúncios para desconhecidos é somente pelo rádio BLE.
 */
const {canonicalPublish,canonicalDelete,validOffer,validateOperationProof,sha}=require('./offerAuth');
const MAX_PER_OWNER=12, LIFE_MS=48*60*60*1000;
function registerOfferRoutesV2(app,{query,withTransaction,isPostgres,activeOffers,requireDatabase=Boolean(process.env.DATABASE_URL)}) {
  const ready=()=>Boolean(requireDatabase&&isPostgres());
  async function auth(operation,owner,id,body,proof) {
    if(!ready())return {error:503};
    const binding=await query('SELECT public_key_spki FROM identity_key_bindings WHERE identity_id=$1',[owner]);
    if(!isPostgres())return {error:503};
    const key=binding.rows[0]?.public_key_spki||null;
    const digest=sha(body);
    return {valid:validateOperationProof(operation,owner,id,digest,proof,key),digest};
  }
  app.post('/api/offers/publish', async(req,res)=>{
    if(!ready())return res.status(503).json({error:'Publicação segura exige banco de homologação ativo.'});
    const src=req.body||{}, owner=String(src.authorId||'').toUpperCase();
    const offer={
      id:src.id,authorId:owner,authorAlias:src.authorAlias,
      profession:src.profession,description:src.description,
      imageBase64:src.imageBase64||'',externalLink:src.externalLink||''
    };
    if(!validOffer(offer))return res.status(400).json({error:'Dados de oferta inválidos ou grandes demais.'});
    try{
      const verified=await auth('OFFER_PUBLISH',owner,offer.id,canonicalPublish(offer),src.identityProof);
      if(verified.error)return res.status(503).json({error:'Autenticação indisponível.'});
      if(!verified.valid)return res.status(401).json({error:'Assinatura do autor inválida.'});
      const saved=await withTransaction(async run=>{
        // Bloqueio por identidade serializa limite por autor em POSTs concorrentes.
        const ownerRow=await run('SELECT id FROM identities WHERE UPPER(id)=UPPER($1) FOR UPDATE',[owner]);
        if(ownerRow.rowCount!==1)return {status:409};
        const nonce=await run(
          'INSERT INTO offer_action_nonces(author_id,nonce) VALUES($1,$2) ON CONFLICT DO NOTHING RETURNING nonce',
          [owner,src.identityProof.nonce]);
        if(nonce.rowCount!==1) return {status:409};
        const exists=await run('SELECT author_id FROM offers_secure WHERE id=$1 FOR UPDATE',[offer.id]);
        if(exists.rowCount&&exists.rows[0].author_id!==owner)return {status:403};
        if(!exists.rowCount) {
          const count=await run('SELECT COUNT(*)::integer AS count FROM offers_secure WHERE author_id=$1 AND created_at>NOW()-INTERVAL \'48 hours\'', [owner]);
          if(Number(count.rows[0]?.count||0)>=MAX_PER_OWNER) return {status:429};
        }
        const row=await run(`INSERT INTO offers_secure
          (id,author_id,author_alias,profession,description,image_base64,external_link)
          SELECT $1,$2,$3,$4,$5,$6,$7 FROM identities WHERE UPPER(id)=UPPER($2)
          ON CONFLICT(id) DO UPDATE SET
            author_alias=EXCLUDED.author_alias,profession=EXCLUDED.profession,
            description=EXCLUDED.description,image_base64=EXCLUDED.image_base64,
            external_link=EXCLUDED.external_link,updated_at=NOW()
          WHERE offers_secure.author_id=EXCLUDED.author_id
          RETURNING id,created_at`,
          [offer.id,owner,offer.authorAlias,offer.profession,offer.description,offer.imageBase64,offer.externalLink]);
        return row.rowCount===1?{status:200,createdAt:Number(new Date(row.rows[0].created_at))}:{status:409};
      });
      if(saved.status!==200)return res.status(saved.status).json({error:'Publicação não autorizada ou duplicada.'});
      const record={...offer,createdAt:saved.createdAt};
      activeOffers.set(offer.id,record);
      return res.json({success:true,offer:record});
    }catch(_){return res.status(503).json({error:'Banco indisponível. Publicação não confirmada.'});}
  });

  app.post('/api/offers/remove',async(req,res)=>{
    if(!ready())return res.status(503).json({error:'Exclusão autenticada exige banco.'});
    const owner=String(req.body?.authorId||'').toUpperCase(),id=String(req.body?.offerId||'');
    if(!/^[a-f0-9]{16}$/i.test(owner)||!/^offer-[0-9a-f-]{36}$/i.test(id))
      return res.status(400).json({error:'Identidade ou anúncio inválido.'});
    try{
      const verified=await auth('OFFER_DELETE',owner,id,canonicalDelete(id),req.body?.identityProof);
      if(verified.error)return res.status(503).json({error:'Autenticação indisponível.'});
      if(!verified.valid)return res.status(401).json({error:'Assinatura de exclusão inválida.'});
      const removed=await withTransaction(async run=>{
        const nonce=await run(
          'INSERT INTO offer_action_nonces(author_id,nonce) VALUES($1,$2) ON CONFLICT DO NOTHING RETURNING nonce',
          [owner,req.body.identityProof.nonce]);
        if(nonce.rowCount!==1)return {status:409};
        const deleted=await run('DELETE FROM offers_secure WHERE id=$1 AND author_id=$2 RETURNING id',[id,owner]);
        return {status:deleted.rowCount===1?200:403};
      });
      if(removed.status!==200)return res.status(removed.status).json({error:'Anúncio inexistente, revogado ou não pertence ao autor.'});
      activeOffers.delete(id);
      return res.json({success:true,deleted:id});
    }catch(_){return res.status(503).json({error:'Banco indisponível. Exclusão não confirmada.'});}
  });

  // Compatibilidade de rota antiga sem manter brecha de autoria.
  app.delete('/api/offers/:id',(_req,res)=>
    res.status(401).json({error:'Atualize o aplicativo: exclusão exige assinatura do autor.'}));
  // O servidor NÃO conhece a distância BLE: não pode disponibilizar
  // dados de anúncios de pessoas que nunca estiveram fisicamente próximas.
  app.get('/api/offers/nearby',(_req,res)=>
    res.json({success:true,count:0,offers:[],transport:'BLE_ONLY_FOR_PROXIMITY'}));
}
module.exports={registerOfferRoutesV2,MAX_PER_OWNER,LIFE_MS};
