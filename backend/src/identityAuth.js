'use strict';
const crypto=require('node:crypto');
function identityFromPublicKey(base64){
  try{
    if(typeof base64!=='string'||base64.length>512||!/^[A-Za-z0-9+/]+={0,2}$/.test(base64))return null;
    const der=Buffer.from(base64,'base64');
    const key=crypto.createPublicKey({key:der,format:'der',type:'spki'});
    if(key.asymmetricKeyType!=='ec'||key.asymmetricKeyDetails?.namedCurve!=='prime256v1')return null;
    return crypto.createHash('sha256').update(der).digest('hex').slice(0,16).toUpperCase();
  }catch(_){return null;}
}
function verifyWsIdentity(identity,pub,signature,nonce,challenge,binding=null){
  if(typeof identity!=='string'||!/^[A-F0-9]{16}$/i.test(identity)||typeof signature!=='string'||
     signature.length>512||nonce!==challenge||!/^[a-f0-9]{48}$/.test(challenge||''))return false;
  const derived=identityFromPublicKey(pub);
  if(!derived||(binding!==null?pub!==binding:derived!==identity.toUpperCase()))return false;
  try{
    const key=crypto.createPublicKey({key:Buffer.from(pub,'base64'),format:'der',type:'spki'});
    const v=crypto.createVerify('SHA256');
    v.update('PA-WS-AUTH-V2|'+identity.toUpperCase()+'|'+challenge,'utf8');v.end();
    return v.verify(key,Buffer.from(signature,'base64'));
  }catch(_){return false;}
}
module.exports={identityFromPublicKey,verifyWsIdentity};
