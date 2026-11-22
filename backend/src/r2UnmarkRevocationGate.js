'use strict';

/**
 * PessoasAqui R2 Stage 30M — opt-in transaction bridge for existing unmark.
 *
 * Disabled unless TWO explicit trusted backend flags are both true.
 * No process env is read at module load; no DB/PG/S3 connections on import.
 * The caller must pass txQuery from the still-open authoritative UNMARK
 * transaction and the actual rowCount values returned by DELETE/UPDATE.
 *
 * While disabled this does NO SQL (including no schema probing). If enabled,
 * missing migration 012 prevents COMMIT via throw (caller must ROLLBACK).
 * Do not ever enable in production before an approved SQL migration/rollout.
 */
const {recordPairRevocationInExistingTransaction}=
  require('./r2StagedRevocationBridge');

function r2RevocationBridgeEnabled(env=process.env){
  return env?.PESSOASAQUI_R2_REVOCATION_TX_ENABLED==='true' &&
    env?.PESSOASAQUI_R2_STAGE_ROLLOUT_APPROVED==='true';
}

async function assertR2RevocationSchemaInTransaction({txQuery}={}){
  if(typeof txQuery!=='function')
    throw new TypeError('PostgreSQL transaction query required');
  const found=await txQuery(
    "SELECT to_regclass('public.r2_stream_revocation_events')::text AS relation",
    []
  );
  if(found?.rowCount!==1||
     found.rows?.[0]?.relation!=='r2_stream_revocation_events')
    throw Error('R2_REVOCATION_SCHEMA_NOT_READY');
  return true;
}

/**
 * Invoke ONLY after both mutations have run under the same transaction.
 * This is a second schema validation for the guarded route, not a replacement
 * for its pre-mutation schema check.
 */
async function recordGuardedUnmarkRevocation({
  txQuery,identityA,identityB,deletedConnectionRows,cancelledMessageRows,
  enabled
}={}){
  if(enabled!==true)return Object.freeze({recorded:false,reason:'DISABLED'});
  await assertR2RevocationSchemaInTransaction({txQuery});
  return recordPairRevocationInExistingTransaction({
    txQuery,identityA,identityB,deletedConnectionRows,cancelledMessageRows
  });
}

module.exports={
  r2RevocationBridgeEnabled,assertR2RevocationSchemaInTransaction,
  recordGuardedUnmarkRevocation
};
