'use strict';
const crypto=require('node:crypto');
const {verifyInviteIdentityProof}=require('./inviteAuth');
const sha=s=>crypto.createHash('sha256').update(s,'utf8').digest('hex');
const fields=parts=>parts.map(x=>String(x).length+':'+String(x)).join('|');

function canonicalPublish(o) {
  return fields(['PA-OFFER-PUBLISH-V1',o.id,String(o.authorId).toUpperCase(),
    o.authorAlias,o.profession,o.description,o.imageBase64||'',o.externalLink||'']);
}
function canonicalDelete(id){return fields(['PA-OFFER-DELETE-V1',id]);}
function validOffer(input){
  if(!input||typeof input!=='object')return false;
  const {id,authorId,authorAlias,profession,description,imageBase64,externalLink}=input;
  if(typeof id!=='string'||!/^offer-[0-9a-f-]{36}$/i.test(id)||
     typeof authorId!=='string'||!/^[0-9a-f]{16}$/i.test(authorId)||
     typeof authorAlias!=='string'||authorAlias.length>64||
     typeof profession!=='string'||!profession.trim()||profession.length>64||
     typeof description!=='string'||!description.trim()||description.length>500||
     typeof imageBase64!=='string'||imageBase64.length>160000||
     typeof externalLink!=='string'||externalLink.length>2048)return false;
  if(imageBase64 && (!/^[a-z0-9+/]+={0,2}$/i.test(imageBase64)))return false;
  if(externalLink) {
    try {
      const u=new URL(externalLink);
      if(!['http:','https:'].includes(u.protocol)||!u.hostname||u.username||u.password) return false;
    }catch(_){return false;}
  }
  return true;
}
function validateOperationProof(operation,identity,id,digest,proof,activeKey=null,now=Date.now()){
  if(!/^[a-f0-9]{64}$/.test(digest))return false;
  return verifyInviteIdentityProof(operation,identity,id,'',digest,proof,now,activeKey);
}
module.exports={canonicalPublish,canonicalDelete,validOffer,validateOperationProof,sha};
