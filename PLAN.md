# Plan: Seat Reservation at Scale

## Decisions

| Choice | Pick | Why |
|---|---|---|
| Language/framework | Java 21 + Spring Boot 3 (virtual threads, Hikari) | You know Java; virtual threads handle 20k concurrent requests, Micrometer gives Prometheus metrics, JSON logs built in |
| Datastore | **PostgreSQL 16** (single DB) | Row locks, `UNIQUE` constraints, `CHECK` constraints and `INSERT ... ON CONFLICT` give us the atomic decision in the DB itself. Not Redis/Mongo: no cross-key transactions or constraint safety net |
| Metrics | Micrometer at `/metrics` | Prometheus format; gauges are computed from the DB at scrape time so they reconcile with the API |
| Container | Dockerfile + docker-compose (app + Postgres) | Clean checkout runs the same as the deploy |
| Hosting | **Render** (Docker web service, free) + **Neon** (free serverless Postgres) | See below |

## Hosting options considered

- **Render web service (Docker) + Neon Postgres — recommended.** Both free with no card for Neon. Render gives public logs and a `render.yaml` blueprint. Neon's free DB does not expire (Render's own free Postgres is deleted after 30 days). Caveat: Render free instances sleep after ~15 min idle (cold start ~30-60s) — the service retries the DB on boot and `/readyz` reports not-ready until it is up; ping `/healthz` before a demo.
- Render web + Render Postgres: simplest single-vendor, but DB expires in 30 days.
- Fly.io: better always-on and regions, but requires a card.
- Railway: trial credits only.

## Correctness design (the part that is graded)

1. **No double-sell:** `seats` has one row per seat. Reserve runs in one transaction: lock the requested seat rows `ORDER BY label FOR UPDATE` (deterministic order => no deadlock on multi-seat), check all are `available`, then guarded `UPDATE ... WHERE status='available'`. Loser sees it taken -> 409 `seat_taken`.
2. **Partial requests:** all-or-nothing (any taken seat -> whole request 409, nothing changes).
3. **Per-user limit:** `user_show_quota` row with a conditional upsert (`... WHERE held + n <= limit`); the row lock serialises one user's parallel requests.
4. **Idempotency:** `UNIQUE(user_id, idempotency_key)` on `reservations`, claimed with `ON CONFLICT DO NOTHING` as the first step of the transaction. Stored request hash -> same key + different seats = 409.
5. **Release model:** explicit `POST /reservations/{id}/cancel` (owner only, 403 otherwise). It only frees seats still pointing at *that* reservation, so it can never resurrect a re-sold seat.
6. **Identity:** from the bearer token only; body `user_id` is ignored.
7. **Zero 5xx:** declines are 4xx; deadlock/serialization retries; a fast-path rejects obviously taken seats without a transaction; a bounded pool queues rather than errors.
8. **Reconciliation:** `available + held + confirmed == total_seats` is read from a single snapshot query, plus a DB `CHECK` that a seat is owned iff not available.

## Steps

1. [x] Project scaffold, schema, service logic, API, auth, metrics, structured logs (Java)
2. [x] Dockerfile, docker-compose, render.yaml, Makefile
3. [x] Run locally against Postgres; smoke test endpoints
4. [x] `burst/Burst.java`: hot-seat storm, 20k stampede, retries, limit race, spoof/cancel checks, invariant polling, metrics reconciliation
5. [x] Run the burst locally; fix anything that 5xxs or breaks the invariant
6. [x] README (run, burst, tokens, metrics/logs) and WRITEUP.md (incl. honest AI usage)
7. [x] Incremental local git commits throughout
8. [ ] **You:** push to GitHub, create Neon DB, deploy on Render with the blueprint, set `DATABASE_URL`, `ADMIN_TOKEN`, `TOKEN_SECRET`; then run `./burst.sh <URL>`

I cannot deploy for you (no account access), so step 8 is yours; I will give exact instructions.
