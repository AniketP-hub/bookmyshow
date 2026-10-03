# Seat Reservation at Scale

Java 21 + Spring Boot 3 + PostgreSQL. Sells assigned seats; never double-sells, never exceeds the per-user limit,
never double-charges a retried request. Design details: [WRITEUP.md](WRITEUP.md).

- **Live URL:** https://13-207-216-228.sslip.io  (AWS EC2, Mumbai; admin token supplied separately)
- **Metrics:** https://13-207-216-228.sslip.io/metrics  |  **Health:** /healthz, /readyz  |  **Logs:** `docker compose -f docker-compose.aws.yml logs -f app` on the host (JSON lines with `request_id`)

## Run locally (Docker)

```sh
docker compose up --build        # app on http://127.0.0.1:8080, Postgres on host port 5433
```

Without Docker: start any Postgres, then `DATABASE_URL=postgres://user:pass@127.0.0.1:5432/db ./mvnw package && java -jar target/app.jar`.

## One-command burst

Needs JDK 21+ only (no other deps).

```sh
./burst.sh https://<live-url>                 # or: make burst BASE_URL=https://<live-url>
ADMIN_TOKEN=<admin token> ./burst.sh https://<live-url>   # if the deploy doesn't use the dev default
# Windows: java burst\Burst.java https://<live-url>
```

It waits for `/readyz`, creates a show, then: (1) hot-seat storm - 5 seats x 500 users at once, (2) 20,000-request mixed
stampede with concurrent same-key duplicates, polling the reconciliation invariant every 100 ms, (3) idempotency,
limit-race, spoofing, owner-only cancel, rebook and all-or-nothing checks, (4) final reconciliation of API state vs.
what clients saw vs. `/metrics`. Prints the outcome distribution and `RESULT: PASS|FAIL` (exit code 0 / 1).
Tunables (env): `SEATS HOT_SEATS HOT_USERS TOTAL USERS CONCURRENCY`.

## API

All bodies JSON. Money is integer **paise**.

| Method / path | Auth | Notes |
|---|---|---|
| `POST /auth/token` `{"user_id":"alice"}` | none | demo token issuer -> `{token}` |
| `POST /shows` `{name, seats[], price_paise, per_user_limit?}` | admin | 201, all seats `available` (default limit 4) |
| `POST /shows/{id}/reserve` `{seats[], idempotency_key}` | user | key may also be the `Idempotency-Key` header. 201 / 409 / 422 |
| `POST /reservations/{id}/cancel` | owner | releases seats; 403 for non-owner; idempotent |
| `GET /reservations/{id}` | owner | |
| `GET /shows/{id}[?seats=false]` | none | per-seat status + `counts` + `reconciled` |
| `GET /healthz` `GET /readyz` `GET /metrics` | none | liveness / readiness (checks DB, 503 if down) / Prometheus |

**Auth.** `Authorization: Bearer <token>`. Admin: the `ADMIN_TOKEN` env value (local default `dev-admin-token`).
User: a signed token from `POST /auth/token`, or `user:<id>` (dev token, enabled by `ALLOW_DEV_TOKENS=true` so a load
test can mint thousands of users without thousands of round trips; disable in a real deployment). The user id is
taken from the token only; a `user_id` in the body is ignored.

**Declines** are 409 with `error` = `seat_taken` | `per_user_limit` | `idempotency_conflict`; unknown seat -> 422;
bad input -> 400. Partial requests are **all-or-nothing**.

## Metrics

`reservations_confirmed_total`, `reservations_declined_total{reason}` (`seat_taken`, `per_user_limit`,
`idempotent_replay`, `idempotency_conflict`, `unknown_seat`), `reservations_cancelled_total`, `seats_confirmed_total`,
`seats_available|held|confirmed|total{show_id}` (gauges read from the DB at scrape time), plus HTTP latency
histograms, JVM and Hikari pool metrics. Counters are per-process (reset on restart).

## Deploy on AWS (single free-tier VM) - see [DEPLOY-AWS.md](DEPLOY-AWS.md)

Alternative below: Render + Neon.

## Deploy (Render + Neon)

1. Push this repo to GitHub.
2. Neon: create a free Postgres project; copy the connection string (`postgres://...?sslmode=require`).
3. Render: New -> Blueprint -> pick the repo (`render.yaml`). Set `DATABASE_URL` (Neon string) and `ADMIN_TOKEN`.
4. Wait for `/readyz` to return 200, then `./burst.sh https://<service>.onrender.com`.

Free instances sleep when idle; the first request after a sleep takes ~1 minute (the burst script waits for readiness).
