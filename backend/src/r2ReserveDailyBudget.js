'use strict';

/**
 * PessoasAqui / R2 Stage 30S — persistent reservation budget (STAGED ONLY).
 *
 * Only trusted backend code may call this with the transaction-bound query
 * AFTER a new (non-duplicate) object reservation is INSERTed, but BEFORE the
 * enclosing transaction COMMITs. If the quota is exhausted, this helper
 * THROWS and the caller MUST ROLLBACK the entire transaction (including
 * nonce and media INSERT). Never catch the quota error inside the action.
 *
 * Single atomic UPSERT holds the row lock across instances. PostgreSQL UTC
 * clock is authoritative. Does not access network, SDK, env or DB on import.
 */
const MAX_NEW_RESERVATIONS_PER_DAY=24;
const ID=/^[A-F0-9]{16}$/;
const QUOTA_CODE='R2_RESERVE_DAILY_QUOTA_EXCEEDED';

async function chargeNewR2Reservation({tx,authenticatedIdentity}={}){
  if(typeof tx!=='function'||typeof authenticatedIdentity!=='string'||
     !ID.test(authenticatedIdentity))
    throw new TypeError('Trusted R2 reserve quota transaction required');
  const charged=await tx(
    `INSERT INTO public.r2_reserve_daily_budget
       (identity_id,day_utc,reservations)
     VALUES($1,(clock_timestamp() AT TIME ZONE 'UTC')::date,1)
     ON CONFLICT(identity_id,day_utc) DO UPDATE
       SET reservations=public.r2_reserve_daily_budget.reservations+1
     WHERE public.r2_reserve_daily_budget.reservations < $2
     RETURNING reservations`,
    [authenticatedIdentity,MAX_NEW_RESERVATIONS_PER_DAY]
  );
  if(charged?.rowCount!==1){
    const err=new Error(QUOTA_CODE);
    err.code=QUOTA_CODE;
    throw err;
  }
  return Object.freeze({
    charged:true,
    reservations:charged.rows?.[0]?.reservations
  });
}

module.exports={
  chargeNewR2Reservation,MAX_NEW_RESERVATIONS_PER_DAY,QUOTA_CODE
};
