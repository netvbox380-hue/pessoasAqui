'use strict';

/**
 * PessoasAqui / R2 Stage 30A — in-process revocation wiring (STAGED ONLY).
 *
 * Returns an explicit lifecycle object, never connects or starts on import.
 * No server.js integration, production SQL, Android code, cloud SDK or GC.
 *
 * connectDedicated MUST create a NEW pg.Client dedicated to LISTEN, not
 * borrow from pg.Pool; withTransaction MUST be an isolated PostgreSQL
 * transaction (BEGIN/COMMIT/ROLLBACK) on the authoritative database.
 *
 * A listener failure MUST immediately abort all active streams.
 * The future HTTP route MUST reject R2 read whenever lifecycle.ready=false.
 */
const {createR2ActiveStreamRegistry}=require('./r2ActiveStreamRegistry');
const {createR2RevocationListener}=require('./r2RevocationListener');
const {reconcileR2ActiveStreams}=require('./r2ActiveStreamReconciliation');
const {streamAuthenticatedR2Ciphertext}=require('./r2StreamingGateway');

function createStagedR2DeliveryRuntime({
  connectDedicated,withTransaction,
  registryOptions,listenerOptions
}={}){
  if(typeof connectDedicated!=='function'||
     typeof withTransaction!=='function')
    throw new TypeError('Dedicated PG connection and transaction required');

  const registry=createR2ActiveStreamRegistry(registryOptions);
  const listener=createR2RevocationListener({
    ...listenerOptions,
    connect:connectDedicated,
    registry,
    reconcile:()=>reconcileR2ActiveStreams({registry,withTransaction}),
    failClosed:()=>registry.abortAll()
  });
  return Object.freeze({
    registry,listener,
    start:()=>listener.start(),
    stop:()=>listener.stop(),
    get ready(){return listener.ready;},
    // This function can only be called by a future trusted, authenticated
    // server route; admission must originate from the signed R2_READ gate.
    stream:({admission,openCiphertext,response,timeoutMs})=>
      streamAuthenticatedR2Ciphertext({
        admission,registry,listener,withTransaction,
        openCiphertext,response,timeoutMs
      })
  });
}

module.exports={createStagedR2DeliveryRuntime};
