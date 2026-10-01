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
