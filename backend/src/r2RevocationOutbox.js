'use strict';

/**
 * PessoasAqui / R2 Stage 24: durable PostgreSQL revocation hints (STAGED).
 *
 * SECURITY CONTRACT:
 *  - recordCommittedRevocation() must be called WITHIN the same transaction
 *    that has actually revoked a message or mutual connection. No standalone
 *    client endpoint may call it.
 *  - pg_notify is transactional; listeners get only event_id AFTER COMMIT.
 *  - LISTEN is a hint, NOT durable. Always read the event row from PostgreSQL
 *    before dispatching. In-process registry is not a distributed bus.
 *  - On startup/reconnect, future gateway must reconcile ALL active streams
 *    against authoritative DB state, not just rely on a cursor/outbox window.
 *
 * This module does not create clients, listen to PostgreSQL, poll, modify
 * server.js, touch R2, or expose any routes.
 */
const crypto=require('node:crypto');

const CHANNEL='pessoasaqui_r2_stream_revocation_v1';
const ID=/^[A-F0-9]{16}$/;
const UUID=/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
function validateEvent(event) {
  if(!event||typeof event!=='object')throw new TypeError('Invalid R2 revocation event');
  if(event.kind==='MESSAGE') {
    if(typeof event.messageId!=='string'||event.messageId.length<1||
      event.messageId.length>256||/[\u0000-\u001f\u007f]/.test(event.messageId)||
      event.identityA!==undefined||event.identityB!==undefined) {
      throw new TypeError('Invalid R2 message revocation');
    }
    return {kind:'MESSAGE',messageId:event.messageId,identityA:null,identityB:null};
  }
  if(event.kind==='PAIR') {
    if(!ID.test(event.identityA||'')||!ID.test(event.identityB||'')||
      event.identityA===event.identityB||event.messageId!==undefined) {
      throw new TypeError('Invalid R2 pair revocation');
    }
    return {kind:'PAIR',messageId:null,identityA:event.identityA,identityB:event.identityB};
  }
  throw new TypeError('Invalid R2 revocation kind');
}

/** Must use the SQL query callback bound to the authoritative revocation TX. */
async function recordCommittedRevocation({query,event,assertRevocationApplied}) {
  if(typeof query!=='function'||assertRevocationApplied!==true) {
    throw new TypeError('Committed R2 revocation transaction required');
  }
  const e=validateEvent(event);
  const eventId=crypto.randomUUID();
  const inserted=await query(
    `INSERT INTO public.r2_stream_revocation_events(
       event_id,event_kind,message_id,identity_a,identity_b)
     VALUES ($1::uuid,$2,$3,$4,$5) RETURNING event_id`,
    [eventId,e.kind,e.messageId,e.identityA,e.identityB]
  );
  if(inserted.rowCount!==1)throw Error('R2_REVOCATION_EVENT_WRITE_FAILED');
  // This SELECT is within the current transaction: PostgreSQL NOTIFY will
  // be sent only when the whole transaction commits, never on ROLLBACK.
  await query('SELECT pg_notify($1,$2)',[CHANNEL,eventId]);
  return Object.freeze({eventId});
}

/** Untrusted NOTIFY payload is only a UUID: authoritative data comes from DB. */
async function dispatchCommittedRevocation({query,registry,eventId}) {
  if(typeof query!=='function'||!registry||
      typeof registry.revokeMessage!=='function'||
      typeof registry.revokePair!=='function'||!UUID.test(eventId||'')) {
    throw new TypeError('Invalid committed revocation dispatch');
  }
  const found=await query(
    `SELECT event_kind,message_id,identity_a,identity_b
       FROM public.r2_stream_revocation_events
      WHERE event_id=$1::uuid`,[eventId]
  );
  if(found.rowCount!==1)return Object.freeze({dispatched:false,reason:'EVENT_NOT_FOUND'});
  const row=found.rows[0];
  let stopped;
  if(row.event_kind==='MESSAGE') {
    const e=validateEvent({kind:'MESSAGE',messageId:row.message_id});
    stopped=registry.revokeMessage(e.messageId);
  }else if(row.event_kind==='PAIR') {
    const e=validateEvent({kind:'PAIR',identityA:row.identity_a,
      identityB:row.identity_b});
    stopped=registry.revokePair(e.identityA,e.identityB);
  }else throw Error('R2_REVOCATION_EVENT_INVALID');
  return Object.freeze({dispatched:true,stopped});
}

/**
 * Recent replay is a safety net only. Do not use a max(event_id) or
 * sequence watermark: concurrent transactions can COMMIT out of order.
 * This rejects truncated replays, rather than pretending a partial scan
 * is complete. An activated gateway must fail closed/disconnect active
 * streams and reconcile with current DB state on replay failure.
 */
async function replayRecentRevocations({query,registry,minutes=15,limit=256}) {
  if(typeof query!=='function'||!Number.isSafeInteger(minutes)||
      minutes<1||minutes>60||!Number.isSafeInteger(limit)||
      limit<1||limit>1000)throw new TypeError('Invalid R2 replay options');
  const rows=await query(
    `SELECT event_id
       FROM public.r2_stream_revocation_events
      WHERE created_at >= clock_timestamp() - ($1::integer * interval '1 minute')
      ORDER BY created_at DESC,event_id
      LIMIT $2::integer`,
    [minutes,limit+1]
  );
  if(rows.rowCount>limit)throw Error('R2_REVOCATION_REPLAY_OVERFLOW');
  let dispatched=0,stopped=0;
  for(const row of rows.rows) {
    const result=await dispatchCommittedRevocation({
      query,registry,eventId:row.event_id
    });
    if(!result.dispatched)throw Error('R2_REVOCATION_REPLAY_GAP');
    dispatched++;
    stopped+=result.stopped;
  }
  return Object.freeze({dispatched,stopped});
}

module.exports={CHANNEL,recordCommittedRevocation,
  dispatchCommittedRevocation,replayRecentRevocations};
