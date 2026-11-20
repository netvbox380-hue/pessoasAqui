'use strict';

/**
 * PessoasAqui / R2 Stage 25 — preparatory dedicated PostgreSQL LISTEN client.
 *
 * INERT until start() is explicitly called by a future backend integration.
 * Never imported from server.js, no real database config or production route.
 * Connect callback MUST supply a fresh dedicated pg.Client, not a pooled
 * transaction client. A listener hint MUST be verified against the outbox DB.
 *
 * Failure rule: any uncertainty while streams are active invokes the
 * trusted failClosed callback synchronously before recovery.
 *
 * NOTE: failClosed must abort ALL local active streams, not just mark health.
 * The gateway must never register new streams while ready !== true.
 */
const {CHANNEL,dispatchCommittedRevocation} = require('./r2RevocationOutbox');

const UUID=/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const MAX_PENDING=256;

function createR2RevocationListener({
  connect,registry,reconcile,failClosed,
  minRetryMs=500,maxRetryMs=30000,
  random=Math.random,setTimer=setTimeout,clearTimer=clearTimeout
}) {
  if(typeof connect!=='function'||typeof reconcile!=='function'||
      typeof failClosed!=='function'||!registry||
      typeof registry.activeCount!=='number'||
      typeof registry.revokeMessage!=='function'||
      typeof registry.revokePair!=='function'||
      !Number.isSafeInteger(minRetryMs)||minRetryMs<50||
      !Number.isSafeInteger(maxRetryMs)||maxRetryMs<minRetryMs||
      typeof random!=='function'||typeof setTimer!=='function'||
      typeof clearTimer!=='function')throw new TypeError('Invalid R2 listener setup');
  let running=false,ready=false,client=null,timer=null,attempt=0,epoch=0;
  let chain=Promise.resolve(),pending=0;
  const detach=(c,handler,down)=>{
    c.off('notification',handler);
    c.off('error',down);
    c.off('end',down);
  };
  const abortActive=()=>{
    // A future gateway MUST give a synchronous fail-closed implementation.
    // Never hide an error here and continue a download.
    ready=false;
    failClosed();
  };
  const cleanupClient=async()=>{
    const old=client;client=null;
    if(old){
      try{old.__r2Detach?.();}catch(_){}
      try{await old.end();}catch(_){}
    }
  };
  const retry=()=>{
    if(!running||timer!==null)return;
    const delay=Math.min(maxRetryMs,minRetryMs*2**Math.min(10,attempt++));
    const jitter=0.5+Math.max(0,Math.min(1,Number(random())||0));
    timer=setTimer(()=>{timer=null;void establish();},Math.floor(delay*jitter));
    timer?.unref?.();
  };
  const failed=(id)=>{
    if(!running||id!==epoch)return;
    ready=false;
    try{abortActive();}finally{
      epoch++;void cleanupClient().finally(retry);
    }
  };
  const establish=async()=>{
    if(!running)return;
    const id=++epoch;
    ready=false;
    let c;
    try{
      c=await connect();
      if(!running||id!==epoch){await c?.end?.();return;}
      if(!c||typeof c.query!=='function'||typeof c.on!=='function'||
          typeof c.off!=='function'||typeof c.end!=='function')
        throw Error('R2_LISTENER_REQUIRES_DEDICATED_CLIENT');
      client=c;
      const down=()=>failed(id);
      const handler=msg=>{
        if(!running||id!==epoch||msg?.channel!==CHANNEL)return;
        if(!UUID.test(msg.payload||'')){
          failed(id);return;
        }
        if(registry.activeCount===0)return; // no needless SQL
        if(++pending>MAX_PENDING){pending--;failed(id);return;}
        chain=chain.then(async()=>{
          if(!running||id!==epoch||!ready)return;
          await dispatchCommittedRevocation({
            query:(sql,args)=>c.query(sql,args),
            registry,eventId:msg.payload
          }).then(result=>{
            if(!result.dispatched)throw Error('R2_OUTBOX_EVENT_MISSING');
          });
        }).catch(()=>failed(id)).finally(()=>{pending--;});
      };
      c.on('notification',handler);
      c.on('error',down);
      c.on('end',down);
      c.__r2Detach=()=>detach(c,handler,down);
      // LISTEN must be live before the reconcile. A notification during
      // reconciliation is treated conservatively by the recheck contract:
      // the caller must reconcile all active sessions again before readiness.
      await c.query('LISTEN '+CHANNEL);
      if(!running||id!==epoch)return;
      await reconcile();
      if(!running||id!==epoch)return;
      // Safety: second reconciliation closes startup race with notifications
      // received while ready=false. No R2 streams may start until ready.
      await reconcile();
      if(!running||id!==epoch)return;
      ready=true;attempt=0;
    }catch(_){
      if(running&&id===epoch)failed(id);
    }
  };
  return Object.freeze({
    start(){
      if(running)return;
      running=true;
      // synchronous fail-closed before listener is connected
      abortActive();
      void establish();
    },
    async stop(){
      running=false;ready=false;epoch++;
      if(timer!==null){clearTimer(timer);timer=null;}
      abortActive();
      await cleanupClient();
    },
    get ready(){return ready;},
    get running(){return running;},
    get pending(){return pending;}
  });
}
module.exports={createR2RevocationListener,MAX_PENDING};
