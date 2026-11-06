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
const {eligibility, MIN_FAMILIES, canClaim} = require('./recoveryPolicy');
const VALID_ID = /^[a-f0-9]{16}$/i;
const VALID_SECRET = /^PA-REC-[A-F0-9]{64}$/;
const TTL_MS = 15 * 60 * 1000; // QR
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

  // Estado real da rede de recuperação, assinado pela própria identidade.
  // Conta apenas delegações autorizadas no PostgreSQL, nunca contatos locais.
  app.post('/api/family/recovery/readiness',async(req,res)=>{
    if(!ready())return dbUnavailable(res);
    const owner=normalizeId(req.body?.targetIdentity);
    if(!VALID_ID.test(owner))return res.status(400).json({error:'Identidade inválida.'});
    try{
      const proof=req.body?.identityProof;
      if(!await authenticated('RECOVERY_READINESS',owner,'','',proof))
        return res.status(401).json({error:'Chave do titular inválida.'});
      const result=await withTransaction(async run=>{
        const used=await run(
          `INSERT INTO recovery_proof_nonces(identity_id,operation,nonce)
           VALUES($1,'RECOVERY_READINESS',$2) ON CONFLICT DO NOTHING RETURNING nonce`,
          [owner,proof.nonce]);
        if(used.rowCount!==1)throw Object.assign(Error('Prova repetida.'),{status:409});
        const count=await run(
          `SELECT COUNT(DISTINCT family_identity)::int AS count FROM recovery_delegations
           WHERE target_identity=$1 AND is_active=TRUE`,[owner]);
        return Number(count.rows[0]?.count||0);
      });
      return res.json({success:true,authorizedFamilyCount:result,
        minimumRequired:MIN_FAMILIES,identityRecoveryReady:result>=MIN_FAMILIES,
        notice:result<MIN_FAMILIES
          ?'Cadastre e autorize mais familiares de confiança antes de ativar a recuperação distribuída.'
          :'Autorizações familiares encontradas. A proteção dos cofres também requer cópias confirmadas.'});
    }catch(e){
      if(e.status===409)return res.status(409).json({error:e.message});
      return dbUnavailable(res);
    }
  });

  /** O próprio titular pode contestar todas as recuperações em aberto.
   * A contestação suspende novas tentativas por 24h e não revoga sua conta.
   */
  app.post('/api/family/recovery/contest',async(req,res)=>{
    if(!ready())return dbUnavailable(res);
    const owner=normalizeId(req.body?.targetIdentity);
    const reason=String(req.body?.reason||'Tentativa de recuperação não reconhecida.').trim();
    if(!VALID_ID.test(owner)||reason.length<10||reason.length>1000)
      return res.status(400).json({error:'Identidade ou justificativa inválida.'});
    try{
      const proof=req.body?.identityProof;
      if(!await authenticated('RECOVERY_CONTEST',owner,'','',proof))
        return res.status(401).json({error:'Assinatura da identidade ativa obrigatória.'});
      const count=await withTransaction(async run=>{
        const ownerExists=await run(
          'SELECT id FROM identities WHERE id=$1 FOR UPDATE',[owner]);
        if(ownerExists.rowCount!==1)throw Object.assign(Error('Conta inexistente'),{status:404});
        const nonce=await run(
          `INSERT INTO recovery_proof_nonces(identity_id,operation,nonce)
            VALUES($1,'RECOVERY_CONTEST',$2) ON CONFLICT DO NOTHING RETURNING nonce`,
          [owner,proof.nonce]);
        if(nonce.rowCount!==1)throw Object.assign(Error('Contestação repetida.'),{status:409});
        const r=await run(
          'UPDATE recovery_grants SET used_at=NOW() WHERE target_identity=$1 AND used_at IS NULL RETURNING id',
          [owner]);
        await run(
          `INSERT INTO account_recovery_holds(target_identity,blocked_until,reason)
           VALUES($1,NOW()+INTERVAL '24 hours',$2)
           ON CONFLICT(target_identity) DO UPDATE SET
             blocked_until=EXCLUDED.blocked_until,reason=EXCLUDED.reason,created_at=NOW()`,
          [owner,reason]);
        await run(
          `INSERT INTO recovery_security_events(target_identity,actor_identity,event_type,details)
            VALUES($1,$2,'OWNER_CONTESTED_RECOVERY',$3::jsonb)`,
          [owner,owner,JSON.stringify({cancelled:r.rowCount})]);
        return r.rowCount;
      });
      return res.json({success:true,cancelled:count,blockedHours:24,
        message:'Recuperações canceladas e suspensas por 24 horas. O suporte poderá investigar.'});
    }catch(e){
      if([404,409].includes(e.status))return res.status(e.status).json({error:e.message});
      return dbUnavailable(res);
    }
  });

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

  // O titular pode revogar a delegação sem desfazer o vínculo de chat.
  // Tokens pendentes emitidos pelo familiar também são invalidados.
  app.post('/api/family/recovery/revoke', async(req,res)=>{
    if(!ready())return dbUnavailable(res);
    const owner=normalizeId(req.body?.targetIdentity);
    const family=normalizeId(req.body?.familyIdentity);
    if(!VALID_ID.test(owner)||!VALID_ID.test(family)||owner===family)
      return res.status(400).json({error:'Identidade inválida.'});
    try {
      const proof=req.body?.identityProof;
      if(!await authenticated('RECOVERY_REVOKE',owner,family,'',proof))
        return res.status(401).json({error:'Somente o titular pode revogar o recuperador.'});
      await withTransaction(async run=>{
        const nonce=await run(
          'INSERT INTO recovery_proof_nonces(identity_id,operation,nonce) VALUES ($1,$2,$3) ON CONFLICT DO NOTHING RETURNING nonce',
          [owner,'REVOKE',proof.nonce]);
        if(nonce.rowCount!==1)throw Object.assign(Error('Assinatura já utilizada'),{status:409});
        await run(
          'UPDATE recovery_delegations SET is_active=FALSE,updated_at=NOW() WHERE target_identity=$1 AND family_identity=$2',
          [owner,family]);
        await run(
          'UPDATE recovery_grants SET used_at=NOW() WHERE target_identity=$1 AND family_identity=$2 AND used_at IS NULL',
          [owner,family]);
      });
      return res.json({success:true});
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
      // A modalidade LINK integra o método na assinatura para impedir troca
      // unilateral de QR para LINK (ou vice-versa) no transporte.
      const method=String(req.body?.method||'QR').toUpperCase();
      if(!['QR','LINK'].includes(method))return res.status(400).json({error:'Método inválido.'});
      const operation=method==='LINK'?'RECOVERY_ISSUE_LINK':'RECOVERY_ISSUE';
      if(!await authenticated(operation,family,target,'',proof))
        return res.status(401).json({error:'Assinatura do familiar inválida.'});
      const hold=await query(
        'SELECT blocked_until FROM account_recovery_holds WHERE target_identity=$1 AND blocked_until>NOW()',
        [target]);
      if(hold.rowCount>0)return res.status(423).json({
        error:'Recuperação temporariamente protegida após alerta de segurança. Procure o suporte.'});
      const delegated=await query(
        `SELECT family_public_key FROM recovery_delegations
           WHERE target_identity=$1 AND family_identity=$2 AND is_active=TRUE`,
        [target,family]);
      if(!isPostgres())return dbUnavailable(res);
      if(delegated.rows.length!==1 || delegated.rows[0].family_public_key!==proof.publicKey)
        return res.status(403).json({error:'Familiar não autorizado pelo titular.'});
      const familyCount=await query(
        'SELECT COUNT(DISTINCT family_identity)::int AS count FROM recovery_delegations WHERE target_identity=$1 AND is_active=TRUE',
        [target]);
      if(Number(familyCount.rows[0]?.count||0)<MIN_FAMILIES)
        return res.status(409).json({error:'Adicione pelo menos dois familiares distintos e autorizados antes de recuperar sua conta.'});
      const policy=eligibility(method);
      const secret='PA-REC-'+crypto.randomBytes(32).toString('hex').toUpperCase();
      const hash=hexSha(secret);
      const grantId='rgr_'+hexSha(family+':'+proof.nonce).slice(0,24);
      const generated=await withTransaction(async run=>{
        // Serializar por identidade evita flood concorrente de códigos de recuperação.
        const owner=await run(
          'SELECT id FROM identities WHERE id=$1 FOR UPDATE',[target]);
        if(owner.rowCount!==1)
          throw Object.assign(Error('Identidade indisponível.'),{status:404});
        const recent=await run(
          `SELECT COUNT(*)::int AS count FROM recovery_grants
           WHERE target_identity=$1 AND created_at>NOW()-INTERVAL '1 hour'`,
          [target]);
        if(Number(recent.rows[0]?.count||0)>=3)
          throw Object.assign(Error('Limite de tentativas de recuperação atingido. Aguarde antes de gerar outra.'),{status:429});
        const binding=await run(
          'SELECT generation FROM identity_key_bindings WHERE identity_id=$1',
          [target]);
        const generation=Number(binding.rows[0]?.generation||0);
        const created=await run(
          `INSERT INTO recovery_grants
             (id, token_hash, target_identity, family_identity, family_public_key,
              expected_generation, expires_at, method, eligible_at)
           SELECT $1,$2,$3,$4,$5,$6,TO_TIMESTAMP($7/1000.0),$8,TO_TIMESTAMP($9/1000.0)
             FROM recovery_delegations WHERE target_identity=$3 AND family_identity=$4
               AND is_active=TRUE AND family_public_key=$5
           ON CONFLICT DO NOTHING RETURNING expires_at, eligible_at`,
          [grantId,hash,target,family,proof.publicKey,generation,
           policy.expiresAt,policy.method,policy.eligibleAt]);
        if(created.rowCount!==1)throw Object.assign(Error('Autorização já utilizada ou revogada'),{status:409});
        return created.rows[0];
      });
      // Aviso sem código secreto: o titular pode contestar durante o prazo.
      const ownerSocket=activeSockets?.get(target.toLowerCase());
      if(ownerSocket && ownerSocket.readyState===WebSocket.OPEN){
        try{ownerSocket.send(JSON.stringify({
          type:'RECOVERY_ALERT',targetIdentity:target,method:policy.method,
          eligibleAtEpochMs:policy.eligibleAt,
          expiresAtEpochMs:policy.expiresAt,
          message:'Nova tentativa de recuperação da sua identidade. Conteste se não reconhece.'
        }));}catch(_){}
      }
      return res.json({success:true,recoveryKey:secret,
        targetIdentity:target, method:policy.method,
        expiresAt:generated.expires_at,
        eligibleAtEpochMs:new Date(generated.eligible_at).getTime(),
        expiresAtEpochMs:new Date(generated.expires_at).getTime(),
        notice:method==='LINK'
          ?'LINK remoto: aguarde 1 hora; requer aprovação independente de outro familiar.'
          :'QR: disponível imediatamente após aprovação de outro familiar distinto.'});
    }catch(e){
      if([404,409,429].includes(e.status))return res.status(e.status).json({error:e.message});
      return dbUnavailable(res);
    }
  });



  // Um novo aparelho INICIA a sessão provando posse de sua própria chave.
  // Família não pode aprovar/liberar segredo sem esta preparação persistida.
  app.post('/api/family/recovery/prepare',async(req,res)=>{
    if(!ready())return dbUnavailable(res);
    const code=String(req.body?.recoveryKey||'').trim().toUpperCase();
    const newId=normalizeId(req.body?.newDeviceIdentity);
    const proof=req.body?.identityProof;
    if(!VALID_SECRET.test(code)||!VALID_ID.test(newId))
      return res.status(400).json({error:'Sessão ou aparelho inválido.'});
    if(!verifyInviteIdentityProof('RECOVERY_PREPARE',newId,'','',hexSha(code),proof))
      return res.status(401).json({error:'Prova do novo aparelho inválida.'});
    try{
      const prepared=await withTransaction(async run=>{
        const grant=(await run(`SELECT id,target_identity,family_identity,expires_at,
            used_at,request_device_identity,request_public_key,eligible_at
            FROM recovery_grants WHERE token_hash=$1 FOR UPDATE`,[hexSha(code)])).rows[0];
        if(!grant||grant.used_at||new Date(grant.expires_at).getTime()<=Date.now())
          throw Object.assign(Error('Solicitação expirada ou utilizada.'),{status:410});
        if(!grant.eligible_at)
          throw Object.assign(Error('Código anterior à política familiar atual; gere um novo QR/LINK.'),{status:410});
        const hold=await run(
          'SELECT target_identity FROM account_recovery_holds WHERE target_identity=$1 AND blocked_until>NOW()',
          [grant.target_identity]);
        if(hold.rowCount>0)throw Object.assign(Error('Recuperação protegida pelo suporte.'),{status:423});
        if(grant.request_device_identity &&
           (grant.request_device_identity!==newId||grant.request_public_key!==proof.publicKey))
          throw Object.assign(Error('Esta autorização já foi vinculada a outro aparelho.'),{status:409});
        const saved=await run(`UPDATE recovery_grants
          SET request_device_identity=$2,request_public_key=$3,
              prepared_at=COALESCE(prepared_at,NOW())
          WHERE id=$1 RETURNING id`,[grant.id,newId,proof.publicKey]);
        if(saved.rowCount!==1)throw Error('Falha ao iniciar a recuperação');
        const nonce=await run(
          `INSERT INTO recovery_proof_nonces(identity_id,operation,nonce)
           VALUES($1,'RECOVERY_PREPARE',$2) ON CONFLICT DO NOTHING RETURNING nonce`,
          [newId,proof.nonce]);
        if(nonce.rowCount!==1)throw Object.assign(Error('Prova reutilizada.'),{status:409});
        await run(`INSERT INTO recovery_security_events
          (target_identity,actor_identity,event_type,request_id)
          VALUES($1,$2,'NEW_DEVICE_PREPARED',$3)`,[grant.target_identity,newId,grant.id]);
        return {targetIdentity:grant.target_identity,
          eligibleAtEpochMs:new Date(grant.eligible_at).getTime(),
          expiresAtEpochMs:new Date(grant.expires_at).getTime()};
      });
      return res.json({success:true,...prepared,
        notice:'Nova sessão autenticada. Solicite aval de outro familiar diferente do emissor.'});
    }catch(e){
      if([409,410,423].includes(e.status))return res.status(e.status).json({error:e.message});
      return dbUnavailable(res);
    }
  });

  // O segundo familiar AUTORIZA a sessão, sem jamais receber PIN ou segredo
  // do cofre. A assinatura vincula alvo e hash do token de uso único.
  app.post('/api/family/recovery/approve',async(req,res)=>{
    if(!ready())return dbUnavailable(res);
    const family=normalizeId(req.body?.familyIdentity);
    const target=normalizeId(req.body?.targetIdentity);
    const token=String(req.body?.recoveryKey||'').trim().toUpperCase();
    if(!VALID_ID.test(family)||!VALID_ID.test(target)||!VALID_SECRET.test(token))
      return res.status(400).json({error:'Solicitação inválida.'});
    try{
      const proof=req.body?.identityProof;
      if(!await authenticated('RECOVERY_APPROVE',family,target,hexSha(token),proof))
        return res.status(401).json({error:'Segunda assinatura familiar inválida.'});
      const result=await withTransaction(async run=>{
        const grant=(await run(
          'SELECT id,target_identity,family_identity,expires_at,eligible_at,used_at,request_device_identity,request_public_key,prepared_at FROM recovery_grants WHERE token_hash=$1 FOR UPDATE',
          [hexSha(token)])).rows[0];
        if(!grant || grant.target_identity!==target || grant.used_at ||
           new Date(grant.expires_at).getTime()<=Date.now())
          throw Object.assign(Error('Sessão inválida ou expirada.'),{status:410});
        if(!grant.eligible_at)
          throw Object.assign(Error('Sessão anterior à política atual; gere novo código.'),{status:410});
        if(family===grant.family_identity)
          throw Object.assign(Error('O familiar que emitiu o QR/LINK não pode ser o segundo autorizador.'),{status:403});
        if(!grant.request_device_identity||!grant.request_public_key||!grant.prepared_at)
          throw Object.assign(Error('O novo aparelho precisa iniciar a recuperação antes do segundo aval.'),{status:409});
        const approved=await run(
          `INSERT INTO recovery_approvals(grant_id,family_identity)
           SELECT $1,$2 FROM recovery_delegations
           WHERE target_identity=$3 AND family_identity=$2 AND is_active=TRUE
             AND family_public_key=$4
           ON CONFLICT DO NOTHING RETURNING grant_id`,
          [grant.id,family,target,proof.publicKey]);
        if(approved.rowCount!==1)
          throw Object.assign(Error('Familiar não autorizado ou aprovação já utilizada.'),{status:409});
        await run(
          `INSERT INTO recovery_proof_nonces(identity_id,operation,nonce)
           VALUES($1,'RECOVERY_APPROVE',$2) ON CONFLICT DO NOTHING RETURNING nonce`,
          [family,proof.nonce]).then(r=>{
            if(r.rowCount!==1)throw Object.assign(Error('Prova repetida.'),{status:409});
          });
        await run(
          `INSERT INTO recovery_security_events(target_identity,actor_identity,event_type,request_id)
           VALUES($1,$2,'SECOND_FAMILY_APPROVED',$3)`,[target,family,grant.id]);
        return grant.id;
      });
      return res.json({success:true,requestId:result,
        message:'Aprovação registrada. A transferência depende das demais provas e do prazo aplicável.'});
    }catch(e){
      if([403,409,410,423].includes(e.status))return res.status(e.status).json({error:e.message});
      return dbUnavailable(res);
    }
  });

  // Acompanhamento autenticado do pedido pelo MESMO aparelho preparado.
  // Não expõe família, chaves ou token em resposta.
  app.post('/api/family/recovery/status',async(req,res)=>{
    if(!ready())return dbUnavailable(res);
    const code=String(req.body?.recoveryKey||'').trim().toUpperCase();
    const newId=normalizeId(req.body?.newDeviceIdentity);
    const proof=req.body?.identityProof;
    if(!VALID_SECRET.test(code)||!VALID_ID.test(newId))
      return res.status(400).json({error:'Código ou aparelho inválido.'});
    if(!verifyInviteIdentityProof('RECOVERY_STATUS',newId,'','',hexSha(code),proof))
      return res.status(401).json({error:'Prova da sessão inválida.'});
    try{
      const result=await withTransaction(async run=>{
        const found=await run(`SELECT id,target_identity,family_identity,
             request_device_identity,request_public_key,used_at,expires_at,eligible_at,method
             FROM recovery_grants WHERE token_hash=$1`,[hexSha(code)]);
        const grant=found.rows[0];
        if(!grant||grant.request_device_identity!==newId||
           grant.request_public_key!==proof.publicKey)
          throw Object.assign(Error('Sessão não pertence a este aparelho.'),{status:403});
        const nonce=await run(
          `INSERT INTO recovery_proof_nonces(identity_id,operation,nonce)
           VALUES($1,'RECOVERY_STATUS',$2) ON CONFLICT DO NOTHING RETURNING nonce`,
          [newId,proof.nonce]);
        if(nonce.rowCount!==1)throw Object.assign(Error('Prova de consulta repetida.'),{status:409});
        const families=await run(
          'SELECT family_identity FROM recovery_delegations WHERE target_identity=$1 AND is_active=TRUE',
          [grant.target_identity]);
        const other=await run(
          `SELECT family_identity FROM recovery_approvals
             WHERE grant_id=$1 AND family_identity<>$2`,
          [grant.id,grant.family_identity]);
        const validApprover=other.rows.some(a=>families.rows.some(f=>f.family_identity===a.family_identity));
        const held=await run(
          'SELECT target_identity FROM account_recovery_holds WHERE target_identity=$1 AND blocked_until>NOW()',
          [grant.target_identity]);
        const now=Date.now(),expiresAt=new Date(grant.expires_at).getTime(),
          eligibleAt=grant.eligible_at?new Date(grant.eligible_at).getTime():Infinity;
        return {
          status:grant.used_at?'USED':expiresAt<=now?'EXPIRED':held.rowCount?'SUSPENDED':
            !validApprover?'WAITING_SECOND_FAMILY':now<eligibleAt?'WAITING_SECURITY_WINDOW':'READY',
          method:grant.method,eligibleAtEpochMs:Number.isFinite(eligibleAt)?eligibleAt:0,
          expiresAtEpochMs:expiresAt,
          secondFamilyApproved:validApprover,
          canComplete:!grant.used_at && expiresAt>now && held.rowCount===0 &&
            validApprover && families.rows.length>=MIN_FAMILIES && now>=eligibleAt
        };
      });
      return res.json({success:true,...result});
    }catch(e){
      if([403,409].includes(e.status))return res.status(e.status).json({error:e.message});
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
                  expected_generation, expires_at,eligible_at,method,used_at,
                  request_device_identity,request_public_key,prepared_at
             FROM recovery_grants WHERE token_hash=$1 FOR UPDATE`,
          [hexSha(secret)]);
        const grant=found.rows[0];
        if(!grant || grant.used_at || new Date(grant.expires_at).getTime()<=Date.now())
          throw Object.assign(Error('Chave inválida, expirada ou utilizada'),{status:410});
        if(!grant.eligible_at)
          throw Object.assign(Error('Código legado inseguro desativado. Gere novo código.'),{status:410});
        if(grant.request_device_identity!==newId ||
           grant.request_public_key!==proof.publicKey || !grant.prepared_at)
          throw Object.assign(Error('Recuperação exige solicitação assinada por este aparelho.'),{status:403});
        // Lock serial por identidade: duas autorizações concorrentes para o
        // mesmo usuário não podem efetivar duas rotações.
        const owner=await run('SELECT id FROM identities WHERE UPPER(id)=UPPER($1) FOR UPDATE',
          [grant.target_identity]);
        if(owner.rowCount!==1)throw Object.assign(Error('Identidade indisponível'),{status:410});
        const hold=await run(
          'SELECT blocked_until FROM account_recovery_holds WHERE target_identity=$1 AND blocked_until>NOW() FOR SHARE',
          [grant.target_identity]);
        if(hold.rowCount>0)
          throw Object.assign(Error('Recuperação suspensa por incidente de segurança.'),{status:423});

        const delegates=await run(
          `SELECT family_identity FROM recovery_delegations
            WHERE target_identity=$1 AND is_active=TRUE FOR SHARE`,[grant.target_identity]);
        const approvals=await run(
          `SELECT family_identity FROM recovery_approvals
            WHERE grant_id=$1 AND family_identity<>$2 FOR SHARE`,
          [grant.id,grant.family_identity]);
        const approvedOther=approvals.rows.map(r=>r.family_identity)
          .find(id=>delegates.rows.some(d=>d.family_identity===id));
        if(!canClaim({
          issuer:grant.family_identity,approver:approvedOther,
          activeDelegates:delegates.rows.map(r=>r.family_identity),
          now:Date.now(),eligibleAt:new Date(grant.eligible_at).getTime(),
          expiresAt:new Date(grant.expires_at).getTime()
        })) {
          throw Object.assign(Error(
            Date.now()<new Date(grant.eligible_at).getTime()
              ?'LINK em período de proteção de 1 hora.'
              :'A recuperação exige uma segunda autorização de familiar diferente e ativa.'
          ),{status:409});
        }
        const active=await run(
          'SELECT generation FROM identity_key_bindings WHERE identity_id=$1 FOR UPDATE',
          [grant.target_identity]);
        const generation=Number(active.rows[0]?.generation||0);
        if(generation!==Number(grant.expected_generation))
          throw Object.assign(Error('Solicitação desatualizada após transferência'),{status:409});
        const delegated=await run(
          `SELECT family_public_key FROM recovery_delegations
             WHERE target_identity=$1 AND family_identity=$2 AND is_active=TRUE
             FOR SHARE`,
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
          `INSERT INTO recovery_security_events(target_identity,actor_identity,event_type,request_id)
            VALUES($1,$2,'IDENTITY_RECOVERED',$3)`,
          [grant.target_identity,newId,grant.id]);
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
