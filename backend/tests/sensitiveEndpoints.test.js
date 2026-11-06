'use strict';
const test=require('node:test'),assert=require('node:assert/strict');
const fs=require('node:fs'),vm=require('node:vm'),path=require('node:path');
const crypto=require('node:crypto');
const src=fs.readFileSync(path.join(__dirname,'../src/server.js'),'utf8').replace(/\r\n/g, '\n');

function routeContext(startName,endName,extra={}){
  const start=src.indexOf(startName),end=src.indexOf(endName,start);
  assert.ok(start>=0 && end>start,`Endpoints ausentes: ${startName}`);
  const routes=new Map();
  const context={
    app:{post:(name,handler)=>routes.set(name,handler)},console,Date,crypto,
    process:{env:{}},
    cleanId:s=>String(s||'').replace(/^peer-/i,'').trim().toUpperCase(),
    keyId:s=>String(s||'').toLowerCase(),
    digestHttpParts:parts=>crypto.createHash('sha256').update(
      parts.map(value=>String(value).length+':'+String(value)).join('|'),'utf8').digest('hex'),
    localStore:{moderationReports:[]},isPostgres:()=>false,
    verifySignedHttp:async(req,res)=>{if(req.body?.identityProof)return true;
      res.status(401).json({error:'Assinatura obrigatória'});return false;},
    ...extra
  };
  vm.runInNewContext(src.slice(start,end),context);
  return {routes,context,async post(name,body){
    let status=200,data;
    await routes.get(name)({body},{status(code){status=code;return this;},json(x){data=x;return this;}});
    return {status,data};
  }};
}

test('denúncia exige identidade assinada e campos limitados',async()=>{
  const f=routeContext("app.post('/api/reports'","/**\n * Abertura de Petição");
  const body={reporterIdentity:'ABCDEF1234567890',accusedIdentity:'1234567890ABCDEF',
    reason:'Abuso',violationType:'ABUSE',evidenceMessages:[]};
  assert.equal((await f.post('/api/reports',body)).status,401);
  assert.equal(f.context.localStore.moderationReports.length,0);
  const valid=await f.post('/api/reports',{...body,identityProof:{nonce:'signed'}});
  assert.equal(valid.status,200);
  assert.equal(f.context.localStore.moderationReports.length,1);
  assert.equal((await f.post('/api/reports',{...body,reason:'x'.repeat(4001)})).status,400);
});

test('denúncias não têm falso sucesso com banco configurado indisponível',async()=>{
  const f=routeContext("app.post('/api/reports'","/**\n * Abertura de Petição",{
    process:{env:{DATABASE_URL:'postgres://fake'}}
  });
  const result=await f.post('/api/reports',{reporterIdentity:'ABCDEF1234567890',
    accusedIdentity:'1234567890ABCDEF',reason:'Denúncia',identityProof:{nonce:'signed'}});
  assert.equal(result.status,503);
  assert.equal(f.context.localStore.moderationReports.length,0);
});

test('chave ECDH ativa exige prova do solicitante, retornando geração persistida',async()=>{
  const binding='P256-SPKI-DER-BASE64';
  const f=routeContext("app.post('/api/identities/key'","/**\n * Consulta de Status",{
    isPostgres:()=>true,query:async()=>({rowCount:1,rows:[{
      public_key_spki:binding,generation:3
    }]}),process:{env:{DATABASE_URL:'postgres://fake'}}
  });
  const body={requesterIdentity:'ABCDEF1234567890',targetIdentity:'1234567890ABCDEF'};
  assert.equal((await f.post('/api/identities/key',body)).status,401);
  const yes=await f.post('/api/identities/key',{...body,identityProof:{nonce:'signed'}});
  assert.equal(yes.status,200);
  assert.equal(yes.data.publicKey,binding);
  assert.equal(yes.data.generation,3);
});

test('painel administrativo não aceita senha ou segredo de fallback',()=>{
  const start=src.indexOf('const ADMIN_USERNAME ='),end=src.indexOf('// -------------------------------------------------------------\n// 1. ENDPOINTS',start);
  assert.ok(start>=0&&end>start);
  const context={crypto,Buffer,Date,process:{env:{}},console};
  vm.runInNewContext(src.slice(start,end),context);
  assert.equal(vm.runInNewContext("verifyAdminToken('fake')",context),null);
  assert.throws(()=>vm.runInNewContext("generateAdminToken('admin')",context),/Admin desabilitado/);
});
