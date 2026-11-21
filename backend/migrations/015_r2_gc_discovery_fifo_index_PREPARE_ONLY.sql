-- PessoasAqui — Stage 30D — GC candidate discovery partial index.
-- STAGED / PREPARE ONLY. Apply to isolated PostgreSQL 18 after 004–014.
-- Do not apply in Supabase production or start a worker.
BEGIN;
CREATE INDEX IF NOT EXISTS idx_r2_objects_delete_queued_fifo
  ON public.message_r2_objects (last_changed_at ASC, object_key ASC)
  WHERE state = 'DELETE_QUEUED';
COMMIT;
-- Benchmark before rollout: a tiny table can still legitimately use Seq Scan.
-- Confirm query plan and buffers on realistic data in a disposable database.
