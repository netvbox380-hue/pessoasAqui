'use strict';

/**
 * PessoasAqui R2 Stage 23 — LOCAL active-download cancellation (STAGED).
 *
 * This is a bounded in-process registry, NOT a distributed revocation bus.
 * No HTTP routes, SQL, R2 SDK calls or production integration.
 *
 * Registrations may only be made by a trusted backend gateway after
 * signed R2_READ admission and fresh pre-stream authorization. Never accept
 * a registration from Android JSON. Only server-originated revocation
 * notifications may call revokeMessage/revokePair.
 *
 * SECURITY: This registry only affects this Node process. All Render workers
 * must receive an authenticated post-commit event and reconcile against DB.
 * Do not activate routes based on this module alone.
 */
const crypto=require('node:crypto');

const ID=/^[0-9A-F]{16}$/;
const MAX_ACTIVE=64;
const MAX_PER_RECIPIENT=2;

function validateRef(ref) {
  if (!ref || typeof ref!=='object' ||
      !ID.test(ref.senderIdentity) || !ID.test(ref.recipientIdentity) ||
      ref.senderIdentity===ref.recipientIdentity ||
      typeof ref.messageId!=='string' || ref.messageId.length===0 ||
      ref.messageId.length>256 || /[\u0000-\u001f\u007f]/.test(ref.messageId)) {
    throw new TypeError('Invalid trusted R2 stream reference');
  }
}
function pairMatches(entry, a, b) {
  return (entry.senderIdentity===a&&entry.recipientIdentity===b) ||
    (entry.senderIdentity===b&&entry.recipientIdentity===a);
}

function createR2ActiveStreamRegistry({
  maxActive=MAX_ACTIVE, maxPerRecipient=MAX_PER_RECIPIENT
}={}) {
  if (!Number.isSafeInteger(maxActive)||maxActive<1||maxActive>1024||
      !Number.isSafeInteger(maxPerRecipient)||maxPerRecipient<1||
      maxPerRecipient>maxActive) throw new TypeError('Invalid stream capacity');
  const active=new Map();

  function remove(token) {
    const entry=active.get(token);
    if (!entry) return false;
    active.delete(token);
    if (entry.signalListener) {
      entry.abortController.signal.removeEventListener('abort',entry.signalListener);
    }
    for (const [emitter,event,handler] of entry.listeners) {
      emitter.off(event,handler);
    }
    return true;
  }

  function abort(token, reason='R2_STREAM_REVOKED') {
    const entry=active.get(token);
    if (!entry) return false;
    // Remove first to avoid reentrant close/aborted listeners.
    remove(token);
    entry.abortController.abort(reason);
    // Node HTTP response is destroyed to halt downstream bytes as well.
    if (!entry.response.destroyed && !entry.response.writableEnded) {
      entry.response.destroy();
    }
    return true;
  }

  function register({reference,abortController,response}) {
    validateRef(reference);
    if (!(abortController instanceof AbortController) ||
        !response || typeof response.destroy!=='function' ||
        typeof response.once!=='function' || typeof response.off!=='function') {
      throw new TypeError('Trusted AbortController and HTTP response required');
    }
    if (abortController.signal.aborted || response.destroyed ||
        response.writableEnded) throw new Error('R2_STREAM_ALREADY_CLOSED');
    if (active.size>=maxActive) throw new Error('R2_STREAM_CAPACITY_EXCEEDED');
    const recipientActive=[...active.values()].filter(x=>
      x.recipientIdentity===reference.recipientIdentity).length;
    if (recipientActive>=maxPerRecipient) throw new Error('R2_RECIPIENT_STREAM_CAPACITY');

    const token=crypto.randomUUID();
    const entry={
      senderIdentity:reference.senderIdentity,
      recipientIdentity:reference.recipientIdentity,
      messageId:reference.messageId,
      // Incomplete legacy references are aborted during reconciliation.
      objectKey:reference.objectKey,
      expectedSha256:reference.expectedSha256,
      expectedBytes:reference.expectedBytes,
      abortController,response,listeners:[]
    };
    const cleanup=()=>remove(token);
    // A premature HTTP close means the peer disconnected. Remove the session
    // and abort the upstream R2 read so a pending 'drain' cannot hang until
    // the gateway deadline. A normal close after 'finish' is cleanup only.
    const onResponseClose=()=>{
      if (!response.writableEnded) abort(token,'CLIENT_DISCONNECT');
      else cleanup();
    };
    const onAbort=()=>abort(token,'R2_STREAM_ABORTED');
    for (const [emitter,event,handler] of [
      [response,'close',onResponseClose],
      [response,'finish',cleanup]
    ]) {
      emitter.once(event,handler);
      entry.listeners.push([emitter,event,handler]);
    }
    // Attach after active registration to avoid race in cleanup.
    active.set(token,entry);
    abortController.signal.addEventListener('abort',onAbort,{once:true});
    entry.signalListener=onAbort;

    return Object.freeze({
      close:()=>remove(token),
      cancel:()=>abort(token),
      get active(){return active.has(token);}
    });
  }

  function revokeMessage(messageId) {
    if (typeof messageId!=='string'||!messageId||messageId.length>256) {
      throw new TypeError('Invalid R2 message revocation');
    }
    let stopped=0;
    for (const [token,entry] of [...active]) {
      if (entry.messageId===messageId && abort(token)) stopped++;
    }
    return stopped;
  }

  function revokePair(identityA,identityB) {
    if (!ID.test(identityA)||!ID.test(identityB)||identityA===identityB) {
      throw new TypeError('Invalid R2 pair revocation');
    }
    let stopped=0;
    for (const [token,entry] of [...active]) {
      if (pairMatches(entry,identityA,identityB)&&abort(token)) stopped++;
    }
    return stopped;
  }

  // A snapshot never exposes sockets or controllers. The token is a
  // process-local opaque handle, not a URL or a client capability.
  function listActive() {
    return Object.freeze([...active].map(([token,e])=>Object.freeze({
      token,senderIdentity:e.senderIdentity,
      recipientIdentity:e.recipientIdentity,messageId:e.messageId,
      objectKey:e.objectKey,expectedSha256:e.expectedSha256,
      expectedBytes:e.expectedBytes
    })));
  }

  function abortAll() {
    let stopped=0;
    for(const token of [...active.keys()]){
      if(abort(token,'R2_STREAM_FAIL_CLOSED'))stopped++;
    }
    return stopped;
  }

  return Object.freeze({
    register,revokeMessage,revokePair,abortAll,listActive,
    cancelToken:token=>abort(token,'R2_STREAM_RECONCILE_REJECTED'),
    get activeCount(){return active.size;}
  });
}

module.exports={createR2ActiveStreamRegistry,MAX_ACTIVE,MAX_PER_RECIPIENT};
