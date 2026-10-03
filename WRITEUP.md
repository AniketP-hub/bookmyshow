# Write-up

Service: Java 21 / Spring Boot 3 / PostgreSQL 16, deployed on one AWS EC2 instance (Mumbai) with Caddy for HTTPS.
Usage and API are in [README.md](README.md); this document explains the decisions.

## The atomic decision

One Postgres function call per reserve: `reserve_seats()` in `src/main/resources/schema.sql`, called from
`SeatService.reserve`. The whole decision is a single atomic statement and a single network round trip. (This matters:
my first version was a multi-statement transaction driven from Java. It was correct, but against a remote database it
was about 3x slower, because the hot-seat row locks were held across several round trips. Moving the logic into the
function cut lock hold time to one call.) Seats are rows in `seats`; the row is the lock.

Inside the function, in order:

1. Claim the idempotency key (see Idempotency).
2. `SELECT ... FROM seats WHERE show_id=? AND label = ANY(?) ORDER BY label FOR UPDATE` - take row locks on every
   requested seat.
3. If any seat is not `available`: delete the claim row and return `seat_taken` (HTTP 409). Nothing else has been
   mutated yet, so a decline leaves no trace.
4. Conditional quota upsert (see Per-user limit). Zero rows affected: delete the claim and return `per_user_limit`.
5. `UPDATE seats SET status='confirmed', ... WHERE ... AND status='available'`. The updated row count must equal the
   number of seats requested, otherwise the function raises and everything rolls back.

**Why it is race-free.** Two transactions cannot both hold the lock on seat A12. The second blocks on the row lock
until the first commits, then re-reads the committed `confirmed` status and declines. The `status='available'` guard
on the UPDATE means that even if step 3 were skipped, the write could not flip a row that is not available. A table
`CHECK` (`status='available'` if and only if the seat has no owner) means an ownerless "sold" seat cannot exist.

**Multi-seat and deadlock.** All seat locks are taken in one statement with `ORDER BY label`, so every transaction
acquires locks in the same global order and no lock cycle can form. Cancel locks reservation, then seats (ordered), then
quota; reserve locks seats (ordered), then quota, so the two orders are compatible. The Java side still retries on
deadlock / serialization errors (SQLSTATE 40P01 / 40001) as a safety net; the burst never needed it.

**Partial requests: all-or-nothing.** `["A12","A13"]` with A12 taken returns 409 and A13 stays available. The burst
checks this over real HTTP, and it holds under concurrency because the check and the write are in the same locked call.

**Per-user limit.** Table `user_show_quota(show_id, user_id, held)`. The function runs
`INSERT ... ON CONFLICT DO UPDATE SET held = held + n WHERE held + n <= limit`. Zero rows affected means the limit would
be exceeded, so the call aborts before any seat is changed. The row lock serialises one user's parallel requests: 10
parallel reserves at limit 4 yield exactly 4 confirmed and 6 `per_user_limit` (verified by the burst). Cancel decrements it.

**Fast-path declines.** Before calling the function, `reserve` does an unlocked read; if a seat is visibly not
`available` it returns 409 immediately (after first checking whether this exact key was already stored, so a retry of the
caller's own success is replayed rather than declined). This is safe because a seat leaves `available` only through a
committed reserve, so the decline was true when read; it is never used to grant a seat. It keeps 499 losers on a hot
seat from each taking a connection and queueing on the row lock.

## Idempotency

The key is stored in `reservations` with `UNIQUE (user_id, idempotency_key)`. The first step of `reserve_seats` is
`INSERT ... ON CONFLICT DO NOTHING`. A concurrent request with the same key blocks on the unique index until the first
commits or aborts, so exactly one proceeds. The loser reads the committed row and replays it (HTTP 201, header
`Idempotent-Replayed: true`) and nothing extra moves. A `request_hash` (sha256 of show id plus the sorted seat list) is
stored with the key; the same key with a different hash returns 409 `idempotency_conflict`. The key insert and the seat
writes are in the same atomic call, so a key is never recorded without its seats. A declined attempt deletes its claim,
so a later retry of that key can still succeed. Keys are scoped per user, so one user cannot collide with or replay
another user's key. Verified by the burst: 8 parallel retries yield one reservation and exactly one seat moves, and 10%
of the stampede requests are concurrent same-key duplicates that must all map to one reservation id.

## Holds and expiry

Model chosen: **explicit cancel** (`POST /reservations/{id}/cancel`, owner only; 403 for anyone else). A reserve confirms
immediately, so the `held` state exists in the schema and in the counts (always 0 today) ready for a future
pay-then-confirm flow. Cancel locks and frees only seats whose `reservation_id` still equals the cancelled reservation,
so it can never free, resurrect or steal a seat that was since re-sold to someone else; cancelling twice is a no-op. The
burst checks this: after mallory cancels and trent re-books, cancelling mallory's old reservation again leaves the seat
confirmed to trent. There is no time-based expiry.

## Consistency vs. availability under a partition

Seat state lives in a single Postgres primary, so the service is CP: if the app cannot reach the database it cannot
decide, so it refuses rather than guess. `/readyz` returns 503, and API calls return 503 `overloaded` with
`Retry-After: 1` once the connection pool wait times out. I prefer an unavailable seat map to a double-sold seat.
Reads (`GET /shows`) also go to the primary, so they are never stale. The cost is that the database is a single point of
failure: this deployment has one VM, one database and no failover. A managed Postgres with a synchronous standby would
keep the same guarantee with higher availability.

## Observability / what would page me at 2am

What exists today:

- `/healthz` (liveness, never touches the DB) and `/readyz` (a real `SELECT 1` on a dedicated connection, so it still
  answers while a burst saturates the main pool; 503 when the database is unreachable or the schema is not applied).
- `/metrics` (Prometheus): `reservations_confirmed_total`, `reservations_declined_total{reason}`,
  `reservations_cancelled_total`, `seats_confirmed_total`, `seats_available|held|confirmed|total{show_id}` (read from the
  database at scrape time, so they reconcile with the API; the burst asserts the deltas match what clients saw), plus HTTP
  latency, Hikari pool and JVM metrics.
- Structured JSON logs with a `request_id` (also returned as `X-Request-Id` and in error bodies), `user_id`, `outcome`,
  `status`, `ms`. A public read-only live view is at `/logs.html` (`/logs/stream`, `/logs`), so reviewers can watch a burst
  without server access. `deploy/record-logs.sh` records and summarises a run.

What I would alert on:

1. Any 5xx (`http_server_requests_seconds_count{status=~"5.."}`). The invariant of this service is that declines are
   4xx, so a single 5xx is a bug or an outage.
2. `/readyz` failing for more than a minute, or `up == 0`.
3. Pool saturation: `hikaricp_connections_pending > 0` sustained, or `hikaricp_connections_acquire_seconds` p99 rising.
4. Reconciliation: `seats_available + seats_held + seats_confirmed != seats_total` for any show. This must never fire;
   if it does, stop selling and investigate.
5. Business shifts: the ratio of `reservations_declined_total{reason="seat_taken"}` to confirmed changing abruptly, and
   p99 latency of `POST /shows/{id}/reserve`.
6. Host: disk above 80%, memory pressure or OOM kills on the VM.

## Operations and storage

- **Deployment:** one free-tier EC2 instance in Mumbai running `docker-compose.aws.yml`: Postgres (volume on the instance
  disk), the app, and Caddy (automatic HTTPS). Steps: [DEPLOY-AWS.md](DEPLOY-AWS.md). The burst passes against the live
  URL (zero 5xx, zero network errors, no seat sold twice, metrics reconcile).
- **What I measured and tuned:** a 300 MB heap made the JVM thrash in garbage collection under 2,500 concurrent
  requests (100% CPU, burst never finished); 400 MB with C1-only compilation and capped metaspace passes with about 500 MB
  resident, so a 1 GB instance works only with the swap file the setup script creates. Throughput on a micro instance is
  roughly 400 req/s from a home connection, versus roughly 2,000 req/s against a local database.
- **Storage is bounded:** the live-log buffer keeps the last 3,000 lines in memory, Docker container logs rotate at
  10 MB x 3 files, seat gauges are exported only for the newest 20 shows, and `deploy/cleanup.sh` removes old shows, unused
  Docker images and old recordings. Nothing is deleted automatically, because reviewers may want to inspect results.
- **Known limitations:** single instance and single database (no failover); counters are per-process and reset on
  restart; `POST /auth/token` is a demo issuer and `Bearer user:<id>` dev tokens are enabled for load testing
  (`ALLOW_DEV_TOKENS=false` turns them off); the public log view exposes user ids and request paths by design.

## AI usage (directed vs decided)

I built this in one long Claude Code session (Claude Sonnet 5.5, in VS Code). I want to be specific about who did what.

**I directed:** the language (the assistant started with a Node/Fastify draft; I asked for Java because I know it and must
extend the code live); the platform (an AWS free-tier VM in Mumbai instead of Render + Neon, after we measured how much a
remote database hurt throughput); public live logs for reviewers; bounded storage and a cleanup path; and the
instance-level choices (free-tier-eligible instance, CPU credits on Standard to avoid surprise charges, Elastic IP,
security group). I ran every AWS console step, the SSH session, and the deployment myself, and I ran the bursts against the
live URL.

**The assistant decided and wrote:** the schema and the locking design (row locks in label order, guarded UPDATE,
conditional quota upsert, unique-key idempotency), the `reserve_seats` function, the Spring Boot code, the metrics, the
burst harness, the Docker/Caddy/compose files and the documentation. Most of the code was written by the assistant, not by
me by hand.

**What testing caught (the burst and the deployment found real problems, not just passes):** the multi-statement
transaction was too slow against a remote database, so it became one function call; Neon's pooled endpoint rejects
startup options and mishandles a session-level advisory lock, so the migrator uses a transaction-scoped lock; a 300 MB heap
caused a GC death spiral; the burst client did not retry connect timeouts and counted a failed poll as an invariant
violation (both harness bugs, now fixed); port 80/443 were missing from the security group; a second local Postgres on
port 5432 shadowed Docker's during development.

**Before the interview I need to be able to explain without the assistant:** the lock-ordering argument and why a
decline in step 3 can safely delete the claim row, why the unlocked fast path can only ever reject, the conditional quota
upsert, and the idempotent-replay flow including the same-key-different-body case.

## What I'd do next

- Time-boxed holds with an expiry sweeper (`held` state, `hold_expires_at`, a guarded
  `UPDATE ... WHERE hold_expires_at < now()`) and a payment-confirm step; the per-user limit would count held plus confirmed.
- Persist and aggregate counters across restarts and instances; run several app instances behind a load balancer (the
  database already makes this safe: the only in-process state is a cache of immutable show config).
- Real auth (JWT/OIDC), dev tokens off, per-user rate limiting at the edge.
- A waiting room / queue in front of the on-sale to protect the database; a read replica or cache for `GET /shows`.
- A managed Postgres with a standby for failover, plus automated snapshots.
- Integration tests with Testcontainers in CI, and a chaos test that kills the database mid-burst.
