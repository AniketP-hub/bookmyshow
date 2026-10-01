# Write-up

## The atomic decision

One Postgres transaction per reserve (`SeatService.reserve`). Seats are rows in `seats`; the row is the lock.

1. Claim the idempotency key (below).
2. `SELECT ... FROM seats WHERE show_id=? AND label = ANY(?) ORDER BY label FOR UPDATE` - row locks.
3. If any is not `available` -> abort the whole transaction (409 `seat_taken`).
4. `UPDATE seats SET status='confirmed', ... WHERE ... AND status='available'`; the updated row count must equal
   the number of seats requested, otherwise abort.
5. Conditional quota upsert (below). Commit.

Why it is race-free: two transactions can't both hold the lock on seat A12; the second blocks until the first
commits, then re-reads the committed `confirmed` status and declines. The `status='available'` guard on the UPDATE
means even if the check were skipped, the write cannot flip a non-available row. A DB `CHECK` (`status='available'`
iff no owner) means a half-written seat cannot exist.

**Multi-seat / deadlock.** All locks are taken in one statement with `ORDER BY label`, so every transaction acquires
seat locks in the same global order -> no lock cycle. Cancel locks reservation -> seats (ordered) -> quota and reserve
locks seats (ordered) -> quota, so the orders are compatible. A retry wrapper still re-runs the transaction on
deadlock/serialization errors (SQLSTATE 40P01/40001) as a belt-and-braces measure.

**Partial requests:** all-or-nothing. `["A12","A13"]` with A12 taken -> 409 and A13 stays available (tested by the
burst under real HTTP).

**Per-user limit.** `user_show_quota(show_id,user_id,held)`; `INSERT ... ON CONFLICT DO UPDATE SET held=held+n WHERE
held+n <= limit`. Zero rows affected = limit exceeded -> abort (seat changes roll back). The row lock serialises one
user's parallel requests, so 10 parallel reserves at limit 4 yield exactly 4. Cancel decrements it.

**Fast-path declines.** Before opening a transaction we do an unlocked read; if a seat is visibly not `available` we
return 409 immediately. This is safe (a seat leaves `available` only via a committed reserve) and is never used to
*grant*; it keeps 499 losers on a hot seat from queueing on a row lock and a connection each.

## Idempotency

Stored in `reservations`, `UNIQUE (user_id, idempotency_key)`. The first step of the transaction is `INSERT ... ON
CONFLICT DO NOTHING`; a concurrent request with the same key blocks on the unique index until the first commits or
aborts, so exactly one proceeds. The loser reads the committed row and replays it (201, header
`Idempotent-Replayed: true`). A `request_hash` (sha256 of show + sorted seats) is stored; same key with a different
hash -> 409 `idempotency_conflict`. The reservation insert is in the same transaction as the seat writes, so a key
is never recorded without its seats (and a declined attempt leaves no key, so a later retry can still succeed).
Keys are scoped per user, so one user cannot collide with or replay another's key.

## Holds and expiry

Model chosen: **explicit cancel** (`POST /reservations/{id}/cancel`, owner only). A reserve confirms immediately, so
the `held` state exists in the schema/counts (always 0 today) for a future pay-then-confirm flow. Cancel only frees
seats whose `reservation_id` still equals the cancelled reservation, so it can never free (resurrect or steal) a seat
that was re-sold to someone else; cancelling twice is a no-op.

## Consistency vs. availability under a partition

Single Postgres primary = CP for seat state: if the app cannot reach the DB it cannot decide, so it refuses
(`/readyz` -> 503, API calls -> 503 `overloaded` with `Retry-After`) rather than guess. We prefer an unavailable
seat map to a double-sold seat. Reads (`GET /shows`) also go to the primary, so they are never stale. Not done:
replicas/failover (a managed Postgres with synchronous standby would keep the same guarantee with higher availability).

## Observability / what pages me at 2am

- 5xx rate > 0 (alert on `http_server_requests_seconds_count{status=~"5.."}`) - the invariant is "declines are 4xx".
- `/readyz` failing / `db_up`-style: `up == 0` or readiness 503 for > 1 min.
- `hikaricp_connections_pending` > 0 sustained or `hikaricp_connections_acquire_seconds` p99 rising -> pool saturation.
- Reconciliation: `seats_available + seats_held + seats_confirmed != seats_total` for any show (must never fire).
- Business sanity: `reservations_declined_total{reason="seat_taken"}` vs confirmed ratio sudden shifts; latency p99 of
  `POST /shows/{id}/reserve`.
Logs are JSON with `request_id` (also returned as `X-Request-Id`), `user_id`, `outcome`, `status`, `ms`.

## AI usage (directed vs decided)

Built with Claude Code (Claude Sonnet 5.5) in a pair-style session. **Fill in/adjust to be accurate for you.**
Facts from the session: I (the candidate) chose Java over the assistant's initial Node draft because I know Java and
must extend it live; the assistant proposed Postgres + Render/Neon, wrote the first versions of the service, schema and
burst harness, and debugged startup issues (a second local Postgres on port 5432 shadowing Docker's, IPv6 `localhost`
delays). Things to be able to explain yourself before the interview: the lock ordering argument, why the fast-path
decline is safe, the quota upsert, and the idempotency flow above.

## What I'd do next

- Time-boxed holds + expiry sweeper (`held` state, `hold_expires_at`, guarded `UPDATE ... WHERE hold_expires_at < now()`)
  and a payment-confirm step; per-user limit would count held+confirmed.
- Persist/aggregate counters across restarts and instances (they are per-process now); multi-instance run behind a
  load balancer (the DB already makes this safe - nothing relies on in-process state except caches of immutable data).
- Real auth (JWT/OIDC), disable dev tokens, rate limiting per user at the edge.
- Waiting-room / queue in front of on-sale to protect the DB, read replica or cache for `GET /shows`.
- Property/integration tests with Testcontainers; chaos test killing the DB mid-burst.
