'use strict';

/**
 * PessoasAqui Stage 30L — atomic revocation bridge (PREPARE ONLY).
 *
 * This helper MUST only be invoked INSIDE the real PostgreSQL transaction
 * that already revoked the connection, with the exact SQL mutation results
 * supplied by that trusted transaction. No server.js import, no routes,
 * no PostgreSQL client or notification listener started on import.
 *
 * Do not call based on Android claims or a pre-transaction boolean.
 */
const {recordCommittedRevocation}=require('./r2RevocationOutbox');
const ID=/^[A-F0-9]{16}$/;

function positiveOrZero(n){
  return Number.isSafeInteger(n)&&n>=0;
}
function validatedPair({identityA,identityB}){
  if(typeof identityA!=='string'||typeof identityB!=='string'||
    !ID.test(identityA)||!ID.test(identityB)||identityA===identityB)
    throw new TypeError('Trusted distinct pair identities required');
}
function validateMutation({deletedConnectionRows,cancelledMessageRows}){
  if(!positiveOrZero(deletedConnectionRows)||
     !positiveOrZero(cancelledMessageRows))
    throw new TypeError('Authoritative PostgreSQL mutation counts required');
}
/**
 * Used after DELETE connections and UPDATE pending messages, within their
 * exact still-open transaction. Must be called before revision advancement
 * and before COMMIT, using transaction-bound query.
 *
 * The return value is only a durable notification hint. For revocation to
 * take effect, authoritative DB writes must already have happened. A PAIR
 * event covers all active streams of the pair, not only undelivered messages.
 */
async function recordPairRevocationInExistingTransaction({
  txQuery,identityA,identityB,
  deletedConnectionRows,cancelledMessageRows
}={}){
  if(typeof txQuery!=='function')
    throw new TypeError('Authoritative transaction query required');
  validatedPair({identityA,identityB});
  validateMutation({deletedConnectionRows,cancelledMessageRows});
  if(deletedConnectionRows===0&&cancelledMessageRows===0)
    return Object.freeze({recorded:false,reason:'NO_MUTATION'});
  const event=await recordCommittedRevocation({
    query:txQuery,
    event:{kind:'PAIR',identityA,identityB},
    assertRevocationApplied:true
  });
  return Object.freeze({recorded:true,eventId:event.eventId});
}

/**
 * If an existing transaction cancels a single message, only the actual
 * successful mutation (updatedRows > 0) can create the message revocation
 * event. Never emit before the database row is changed.
 */
async function recordMessageRevocationInExistingTransaction({
  txQuery,messageId,updatedRows
}={}){
  if(typeof txQuery!=='function'||typeof messageId!=='string'||
     messageId.length<1||messageId.length>256||
     /[\u0000-\u001f\u007f]/.test(messageId)||
     !positiveOrZero(updatedRows))
    throw new TypeError('Authoritative message revocation required');
  if(updatedRows===0)
    return Object.freeze({recorded:false,reason:'NO_MUTATION'});
  const event=await recordCommittedRevocation({
    query:txQuery,
    event:{kind:'MESSAGE',messageId},
    assertRevocationApplied:true
  });
  return Object.freeze({recorded:true,eventId:event.eventId});
}

module.exports={
  recordPairRevocationInExistingTransaction,
  recordMessageRevocationInExistingTransaction
};
