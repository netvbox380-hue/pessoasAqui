-- PessoasAqui / Stage 30Y-D1: E2EE envelope to immutable winning attempt.
-- PREPARE ONLY. Never apply to production before isolated PostgreSQL audit.
-- Requires 001-021. Legacy text messages remain NULL/unaffected.
BEGIN;

-- Composite FK guarantees that the winning physical key belongs to the
-- SAME message ID, not just to some other message's winner.
ALTER TABLE public.message_r2_winning_attempts
  ADD CONSTRAINT r2_winner_message_key_reference
    UNIQUE (message_id,object_key);

ALTER TABLE public.e2ee_messages
  ADD COLUMN IF NOT EXISTS r2_winning_object_key text;

ALTER TABLE public.e2ee_messages
  ADD CONSTRAINT e2ee_messages_r2_exact_winner_fk
    FOREIGN KEY (id,r2_winning_object_key)
    REFERENCES public.message_r2_winning_attempts(message_id,object_key)
    ON UPDATE RESTRICT ON DELETE RESTRICT;

CREATE UNIQUE INDEX IF NOT EXISTS idx_r2_e2ee_winning_object_key
  ON public.e2ee_messages(r2_winning_object_key)
  WHERE r2_winning_object_key IS NOT NULL;

-- No silent detaching, swapping, or rewriting the winner when an envelope
-- already references it. A message cannot be 'resurrected' via UPDATE.
CREATE OR REPLACE FUNCTION public.pessoasaqui_guard_e2ee_r2_winner_binding()
RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog,public
AS $fn$
BEGIN
  IF OLD.r2_winning_object_key IS NOT NULL
     AND NEW.r2_winning_object_key IS DISTINCT FROM OLD.r2_winning_object_key
  THEN
    RAISE EXCEPTION 'R2 E2EE winning physical object binding immutable'
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END
$fn$;
DROP TRIGGER IF EXISTS pessoasaqui_guard_e2ee_r2_winner_binding_trg
  ON public.e2ee_messages;
CREATE TRIGGER pessoasaqui_guard_e2ee_r2_winner_binding_trg
BEFORE UPDATE ON public.e2ee_messages
FOR EACH ROW EXECUTE FUNCTION public.pessoasaqui_guard_e2ee_r2_winner_binding();

COMMIT;

-- FK and trigger cannot prove physical object permanence or E2EE payload
-- semantics, only the relational binding. Trusted ATTACH must verify the
-- winning claim and attempt, ciphertext digest, pair, nonce, leases and
-- message terminal receipt in a single committed transaction.
