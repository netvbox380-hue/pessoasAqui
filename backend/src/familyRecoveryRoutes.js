'use strict';
/**
 * Recuperação familiar v2: mesma curva P-256/ECDSA/SHA-256 de LINK+QR,
 * domínio separado RECOVERY_*. Nenhuma autenticação é derivada de nomes,
 * PIN de fábrica, chaves fornecidas sem assinatura, ou vínculos só locais.
 *
 * Rotas antigas /issue e /claim são substituídas, não coexistem.
 * Por segurança esta funcionalidade REQUER PostgreSQL transacional.
 */
const crypto = require('node:crypto');
const {verifyInviteIdentityProof} = require('./inviteAuth');
const VALID_ID = /^[a-f0-9]{16}$/i;
const VALID_SECRET = /^PA-REC-[A-F0-9]{64}$/;
const TTL_MS = 15 * 60 * 1000;
const hexSha = value => crypto.createHash('sha256').update(value, 'utf8').digest('hex');
const normalizeId = value => String(value || '').trim().replace(/^peer-/i,'').toUpperCase();

function publicKeyIdentity(base64) {
  try {
    if (typeof base64 !== 'string' || base64.length > 512 ||
        !/^[a-z0-9+/]+={0,2}$/i.test(base64)) return null;
    const der=Buffer.from(base64,'base64');
    const parsed=crypto.createPublicKey({key:der,format:'der',type:'spki'});
    if (parsed.asymmetricKeyType !== 'ec' ||
        parsed.asymmetricKeyDetails?.namedCurve !== 'prime256v1') return null;
    return crypto.createHash('sha256').update(der).digest('hex').slice(0,16).toUpperCase();
  } catch (_) { return null; }
}

function registerFamilyRecoveryRoutes(app, deps) {
  const {query, withTransaction, isPostgres, activeSockets, WebSocket} = deps;
  const ready = () => Boolean((deps.databaseRequired ?? Boolean(process.env.DATABASE_URL)) && isPostgres());
  // O código é bearer e nunca deve ser armazenado em caches HTTP.
  if (typeof app.use === 'function') {
    app.use('/api/family/recovery', (_req, response, next) => {
      response.set('Cache-Control', 'no-store, private');
      response.set('Pragma', 'no-cache');
      next();
    });
  }
  function dbUnavailable(res) { return res.status(503).json({error:'Recuperação segura indisponível. Nenhuma identidade foi transferida.'}); }

  async function boundKey(identity, execute = query) {
    const result=await execute('SELECT public_key_spki FROM identity_key_bindings WHERE identity_id = $1',
      [identity]);
    if(!isPostgres()) throw Error('Banco de identidades indisponível');
    return result.rows[0]?.public_key_spki || null;
  }
  async function authenticated(operation, identity, alias, tokenHash, proof, execute=query) {
    if(!VALID_ID.test(identity)) return false;
    const active = await boundKey(identity,execute);
    return verifyInviteIdentityProof(operation, identity, alias, '', tokenHash, proof,
      Date.now(), active);
  }

  // Credenciamento consciente: proprietário autenticado concede a um
  // familiar específico permissão de recuperação. O ID do familiar precisa
  // ser autocertificado pela chave pública indicada.
  app.post('/api/family/recovery/delegate', async (req,res)=>{
    if(!ready()) return dbUnavailable(res);
    const owner=normalizeId(req.body?.targetIdentity);
    const family=normalizeId(req.body?.familyIdentity);
    const pub=req.body?.familyPublicKey;
    if(!VALID_ID.test(owner)||!VALID_ID.test(family)||owner===family||
       publicKeyIdentity(pub)===null) return res.status(400).json({error:'Identidades ou chave familiar inválidas.'});
    try {
      // Familiares que recuperaram sua identidade podem possuir chave rotacionada.
      // Só confiar nessa exceção quando a chave ativa vier do PostgreSQL.
      const boundFamilyKey=await boundKey(family);
      if(boundFamilyKey ? pub!==boundFamilyKey : publicKeyIdentity(pub)!==family)
        return res.status(401).json({error:'Chave familiar não corresponde à identidade ativa.'});
      const proof=req.body.identityProof;
      if(!await authenticated('RECOVERY_DELEGATE', owner, family, hexSha(pub), proof))
        return res.status(401).json({error:'Somente o titular pode autorizar um familiar.'});
      await withTransaction(async run=>{
        // Uso único do nonce, inclusive após revogações/novos credenciamentos.
        const nonces=await run(
          'INSERT INTO recovery_proof_nonces(identity_id, operation, nonce) VALUES ($1,$2,$3) ON CONFLICT DO NOTHING RETURNING nonce',
          [owner,'DELEGATE',proof.nonce]);
        if(nonces.rowCount!==1) throw Object.assign(Error('Prova já utilizada'),{status:409});
        const saved=await run(
          `INSERT INTO recovery_delegations(target_identity, family_identity, family_public_key, is_active)
           SELECT $1,$2,$3,TRUE FROM identities WHERE UPPER(id)=UPPER($1)
           ON CONFLICT (target_identity,family_identity) DO UPDATE
             SET family_public_key=EXCLUDED.family_public_key, is_active=TRUE, updated_at=NOW()
           RETURNING target_identity`,
          [owner,family,pub]);
        if(saved.rowCount!==1)throw Object.assign(Error('Identidade não registrada'),{status:409});
      });
      return res.json({success:true, targetIdentity:owner,familyIdentity:family});
    } catch(e) {
      if(e.status===409)return res.status(409).json({error:e.message});
      return dbUnavailable(res);
    }
  });

  // Emitido pelo familiar autorizado; código 256 bits, 15 minutos,
  // hash no banco. O familiar não recebe PIN ou material de chave privada.
  app.post('/api/family/recovery/issue', async (req,res)=>{
    if(!ready())return dbUnavailable(res);
    const target=normalizeId(req.body?.targetIdentity);
    const family=normalizeId(req.body?.familyIdentity);
    if(!VALID_ID.test(target)||!VALID_ID.test(family)||target===family)
      return res.status(400).json({error:'Identidade de recuperação inválida.'});
    try{
      const proof=req.body?.identityProof;
      if(!await authenticated('RECOVERY_ISSUE',family,target,'',proof))
        return res.status(401).json({error:'Assinatura do familiar inválida.'});
      const delegated=await query(
        `SELECT family_public_key FROM recovery_delegations
           WHERE target_identity=$1 AND family_identity=$2 AND is_active=TRUE`,
        [target,family]);
      if(!isPostgres())return dbUnavailable(res);
      if(delegated.rows.length!==1 || delegated.rows[0].family_public_key!==proof.publicKey)
        return res.status(403).json({error:'Familiar não autorizado pelo titular.'});
      const secret='PA-REC-'+crypto.randomBytes(32).toString('hex').toUpperCase();
      const hash=hexSha(secret);
      const grantId='rgr_'+hexSha(family+':'+proof.nonce).slice(0,24);
      const generated=await withTransaction(async run=>{
        const binding=await run(
          'SELECT generation FROM identity_key_bindings WHERE identity_id=$1',
          [target]);
        const generation=Number(binding.rows[0]?.generation||0);
        const created=await run(
          `INSERT INTO recovery_grants
             (id, token_hash, target_identity, family_identity, family_public_key, expected_generation, expires_at)
           SELECT $1,$2,$3,$4,$5,$6,NOW()+INTERVAL '15 minutes'
             FROM recovery_delegations WHERE target_identity=$3 AND family_identity=$4
               AND is_active=TRUE AND family_public_key=$5
           ON CONFLICT DO NOTHING RETURNING expires_at`,
          [grantId,hash,target,family,proof.publicKey,generation]);
        if(created.rowCount!==1)throw Object.assign(Error('Autorização já utilizada ou revogada'),{status:409});
        return created.rows[0];
      });
      return res.json({success:true,recoveryKey:secret,
        targetIdentity:target,expiresAt:generated.expires_at,
        expiresAtEpochMs:new Date(generated.expires_at).getTime(),
        notice:'Válido por 15 minutos • Uso único • Confirme a identidade do destinatário.'});
    }catch(e){
      if(e.status===409)return res.status(409).json({error:e.message});
      return dbUnavailable(res);
    }
  });

  // O aparelho novo cria sua própria chave P-256 e ASSINA o hash do
  // código recebido. A identidade original só muda de chave numa transação.
  app.post('/api/family/recovery/claim', async (req,res)=>{
    if(!ready())return dbUnavailable(res);
    const secret=String(req.body?.recoveryKey||'').trim().toUpperCase();
    const newId=normalizeId(req.body?.newDeviceIdentity);
    const proof=req.body?.identityProof;
    if(!VALID_SECRET.test(secret)||!VALID_ID.test(newId))
      return res.status(400).json({error:'Chave ou dispositivo inválido.'});
    if(!verifyInviteIdentityProof('RECOVERY_CLAIM',newId,'','',hexSha(secret),proof))
      return res.status(401).json({error:'Novo aparelho não comprovou posse da chave.'});
    let target;
    try{
      target=await withTransaction(async run=>{
        const found=await run(
          `SELECT id,target_identity,family_identity,family_public_key,
                  expected_generation, expires_at,used_at
             FROM recovery_grants WHERE token_hash=$1 FOR UPDATE`,
          [hexSha(secret)]);
        const grant=found.rows[0];
        if(!grant || grant.used_at || new Date(grant.expires_at).getTime()<=Date.now())
          throw Object.assign(Error('Chave inválida, expirada ou utilizada'),{status:410});
        // Lock serial por identidade: duas autorizações concorrentes para o
        // mesmo usuário não podem efetivar duas rotações.
        const owner=await run('SELECT id FROM identities WHERE UPPER(id)=UPPER($1) FOR UPDATE',
          [grant.target_identity]);
        if(owner.rowCount!==1)throw Object.assign(Error('Identidade indisponível'),{status:410});
        const active=await run(
          'SELECT generation FROM identity_key_bindings WHERE identity_id=$1 FOR UPDATE',
          [grant.target_identity]);
        const generation=Number(active.rows[0]?.generation||0);
        if(generation!==Number(grant.expected_generation))
          throw Object.assign(Error('Solicitação desatualizada após transferência'),{status:409});
        const delegated=await run(
          `SELECT family_public_key FROM recovery_delegations
             WHERE target_identity=$1 AND family_identity=$2 AND is_active=TRUE`,
          [grant.target_identity,grant.family_identity]);
        if(delegated.rows.length!==1||
           delegated.rows[0].family_public_key!==grant.family_public_key)
          throw Object.assign(Error('Autorização familiar revogada'),{status:403});
        const updated=await run(
          `INSERT INTO identity_key_bindings(identity_id,public_key_spki,generation,updated_at)
           VALUES($1,$2,$3,NOW())
           ON CONFLICT (identity_id) DO UPDATE
            SET public_key_spki=EXCLUDED.public_key_spki,
                generation=EXCLUDED.generation,updated_at=NOW()
           RETURNING generation`,
          [grant.target_identity,proof.publicKey,generation+1]);
        if(updated.rowCount!==1)throw Error('Falha na rotação de chave');
        // Invalida todas as autorizações ainda abertas para esta identidade.
        await run(
          'UPDATE recovery_grants SET used_at=NOW() WHERE target_identity=$1 AND used_at IS NULL',
          [grant.target_identity]);
        await run(
          'UPDATE devices SET is_active=FALSE,revoked_at=NOW() WHERE identity_id=$1 AND is_active=TRUE',
          [grant.target_identity]);
        // Nova sessão do Android será autenticada pela chave ativa em WS.
        return grant.target_identity;
      });
    }catch(e){
      if([403,409,410].includes(e.status))return res.status(e.status).json({error:e.message});
      return dbUnavailable(res);
    }
    // Só notificar/revogar em tempo real APÓS confirmação transacional.
    const old=activeSockets?.get(target.toLowerCase());
    if(old && old.readyState===WebSocket.OPEN){
      try{
        old.send(JSON.stringify({type:'SESSION_REVOKED',
          message:'Identidade transferida para novo aparelho.'}));
        old.close(4001,'Recovered identity');
      }catch(_){}
    }
    return res.json({success:true,targetIdentity:target,
      message:'Identidade recuperada com nova chave autorizada.'});
  });
}

module.exports={registerFamilyRecoveryRoutes,publicKeyIdentity,TTL_MS};
