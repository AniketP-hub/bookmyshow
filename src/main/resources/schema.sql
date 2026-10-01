CREATE TABLE IF NOT EXISTS shows (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  name           text NOT NULL,
  price_paise    bigint NOT NULL CHECK (price_paise >= 0),
  per_user_limit int NOT NULL CHECK (per_user_limit > 0),
  total_seats    int NOT NULL CHECK (total_seats > 0),
  created_at     timestamptz NOT NULL DEFAULT now()
);

-- One row per physical seat. The row IS the lock: every state change is a guarded write on it.
CREATE TABLE IF NOT EXISTS seats (
  show_id        uuid NOT NULL REFERENCES shows(id),
  label          text NOT NULL,
  ord            int  NOT NULL,
  status         text NOT NULL DEFAULT 'available' CHECK (status IN ('available','held','confirmed')),
  reservation_id uuid,
  user_id        text,
  PRIMARY KEY (show_id, label),
  -- a seat has an owner iff it is not available; the DB itself refuses an ownerless "sold" seat
  CONSTRAINT seat_owner_consistent CHECK ((status = 'available') = (reservation_id IS NULL AND user_id IS NULL))
);

CREATE TABLE IF NOT EXISTS reservations (
  id              uuid PRIMARY KEY,
  show_id         uuid NOT NULL REFERENCES shows(id),
  user_id         text NOT NULL,
  idempotency_key text NOT NULL,
  request_hash    text NOT NULL,
  seats           text[] NOT NULL,
  amount_paise    bigint NOT NULL,
  status          text NOT NULL DEFAULT 'confirmed' CHECK (status IN ('confirmed','cancelled')),
  created_at      timestamptz NOT NULL DEFAULT now(),
  cancelled_at    timestamptz,
  -- exactly-once: the key is unique per user, enforced by the index, not by application code
  CONSTRAINT reservations_idem UNIQUE (user_id, idempotency_key)
);

-- Active-seat count per (show, user). A conditional upsert on this row enforces the per-user limit.
CREATE TABLE IF NOT EXISTS user_show_quota (
  show_id uuid NOT NULL REFERENCES shows(id),
  user_id text NOT NULL,
  held    int  NOT NULL CHECK (held >= 0),
  PRIMARY KEY (show_id, user_id)
);

-- The whole reserve decision as ONE atomic call (one network round trip, short lock hold time).
-- Every failure path happens before any seat/quota mutation and only deletes the freshly claimed key row,
-- so a decline leaves no trace. Locks: seat rows in label order (deterministic => no deadlock), then quota.
CREATE OR REPLACE FUNCTION reserve_seats(
  p_id uuid, p_show uuid, p_user text, p_key text, p_hash text,
  p_seats text[], p_amount bigint, p_limit int)
RETURNS TABLE (outcome text, res_id uuid, res_hash text, res_seats text[], res_amount bigint, res_status text, detail text[])
LANGUAGE plpgsql AS $$
DECLARE
  v_n     int := cardinality(p_seats);
  v_found int;
  v_taken text[];
  v_ex    reservations%ROWTYPE;
  v_cnt   int;
BEGIN
  -- 1. claim the idempotency key (a concurrent identical key blocks on the unique index until we finish)
  INSERT INTO reservations (id, show_id, user_id, idempotency_key, request_hash, seats, amount_paise, status)
  VALUES (p_id, p_show, p_user, p_key, p_hash, p_seats, p_amount, 'confirmed')
  ON CONFLICT (user_id, idempotency_key) DO NOTHING;
  IF NOT FOUND THEN
    SELECT * INTO v_ex FROM reservations WHERE user_id = p_user AND idempotency_key = p_key;
    RETURN QUERY SELECT 'replay'::text, v_ex.id, v_ex.request_hash, v_ex.seats, v_ex.amount_paise, v_ex.status, NULL::text[];
    RETURN;
  END IF;

  -- 2. lock the requested seat rows in label order; collect the ones not available
  SELECT count(*), array_agg(s.label ORDER BY s.label) FILTER (WHERE s.status <> 'available')
    INTO v_found, v_taken
    FROM (SELECT label, status FROM seats WHERE show_id = p_show AND label = ANY(p_seats) ORDER BY label FOR UPDATE) s;
  IF v_found <> v_n THEN
    RAISE EXCEPTION 'unknown seat' USING ERRCODE = 'SX404';
  END IF;
  IF v_taken IS NOT NULL THEN
    DELETE FROM reservations WHERE id = p_id;
    RETURN QUERY SELECT 'seat_taken'::text, NULL::uuid, NULL::text, NULL::text[], NULL::bigint, NULL::text, v_taken;
    RETURN;
  END IF;

  -- 3. per-user limit: conditional upsert (0 rows => over limit)
  INSERT INTO user_show_quota (show_id, user_id, held) VALUES (p_show, p_user, v_n)
  ON CONFLICT (show_id, user_id) DO UPDATE SET held = user_show_quota.held + EXCLUDED.held
    WHERE user_show_quota.held + EXCLUDED.held <= p_limit;
  IF NOT FOUND THEN
    DELETE FROM reservations WHERE id = p_id;
    RETURN QUERY SELECT 'per_user_limit'::text, NULL::uuid, NULL::text, NULL::text[], NULL::bigint, NULL::text, NULL::text[];
    RETURN;
  END IF;

  -- 4. guarded write; the row count must match or the whole call aborts (rolls back)
  UPDATE seats SET status = 'confirmed', reservation_id = p_id, user_id = p_user
   WHERE show_id = p_show AND label = ANY(p_seats) AND status = 'available';
  GET DIAGNOSTICS v_cnt = ROW_COUNT;
  IF v_cnt <> v_n THEN
    RAISE EXCEPTION 'seat state changed under lock' USING ERRCODE = 'SX500';
  END IF;

  RETURN QUERY SELECT 'ok'::text, p_id, p_hash, p_seats, p_amount, 'confirmed'::text, NULL::text[];
END
$$;
