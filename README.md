# Seat Reservation at Scale

A JSON HTTP API that sells assigned seats for a show (concert / movie hall). It is the system of record that decides,
atomically, who gets each seat under heavy concurrency:

- a seat is **never sold twice** (500 people racing for seat A12 -> exactly one `201`, the rest a clean `409`)
- a user **never exceeds the per-user limit** (default 4 seats per show), even with parallel requests
- a retried request **never double-books** (idempotency keys)
- identity comes from the **auth token only**, never from the request body

Stack: Java 21, Spring Boot 3, PostgreSQL 16. Money is integer **paise** everywhere.
Design and reasoning: [WRITEUP.md](WRITEUP.md). AWS deployment guide: [DEPLOY-AWS.md](DEPLOY-AWS.md).

## Live deployment

| | |
|---|---|
| Base URL | `https://13-207-216-228.sslip.io` (AWS EC2, Mumbai) |
| Health / readiness | `/healthz`, `/readyz` |
| Metrics (Prometheus) | `/metrics` |
| **Live logs (public, read-only)** | **`/logs.html`** - watch requests arrive in real time while you run your own burst. Raw: `/logs?lines=500`, stream: `/logs/stream` |
| Admin token | supplied separately with the submission |

Quick check: `curl https://13-207-216-228.sslip.io/readyz` -> `{"status":"ready","db":"up"}`

## Try it in 2 minutes (curl)

```sh
BASE=https://13-207-216-228.sslip.io        # or http://127.0.0.1:8080 when running locally
ADMIN=<admin token>                          # local default: dev-admin-token
```

**1. Create a show (admin).** Returns the show `id` and every seat as `available`.

```sh
curl -s -X POST $BASE/shows -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' \
  -d '{"name":"friday-night","seats":["A1","A2","A3","A4","A5","A6"],"price_paise":25000}'
SHOW=<id from the response>
```

**2. Get a user token.** Any user id matching `[A-Za-z0-9_.@-]{1,64}`.

```sh
curl -s -X POST $BASE/auth/token -H 'Content-Type: application/json' -d '{"user_id":"alice"}'
TOKEN=<token from the response>
```

(For load tests the shortcut `Authorization: Bearer user:alice` is also accepted; see *Authentication*.)

**3. Reserve a seat.** `201` on success, with an idempotency key (header `Idempotency-Key` or body field).

```sh
curl -s -X POST $BASE/shows/$SHOW/reserve -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"seats":["A1"],"idempotency_key":"order-1001"}'
# {"reservation_id":"...","show_id":"...","user_id":"alice","seats":["A1"],"amount_paise":25000,"status":"confirmed"}
```

Repeat the exact same call: you get the **same** reservation back (`201`, header `Idempotent-Replayed: true`), nothing
extra is booked. Reuse the key with different seats: `409 idempotency_conflict`.

**4. See the state.**

```sh
curl -s $BASE/shows/$SHOW                 # per-seat status + counts
curl -s "$BASE/shows/$SHOW?seats=false"   # counts only (cheap for big halls)
# {"counts":{"available":5,"held":0,"confirmed":1},"total_seats":6,"reconciled":true,...}
```

`available + held + confirmed == total_seats` always holds; `reconciled` tells you so.

**5. Cancel.** Only the owner can cancel; the seat becomes bookable again.

```sh
curl -s -X POST $BASE/reservations/<reservation_id>/cancel -H "Authorization: Bearer $TOKEN"
```

## API reference

| Method and path | Auth | Description |
|---|---|---|
| `POST /auth/token` `{"user_id"}` | none | issue a signed user token (demo issuer) |
| `POST /shows` `{name, seats[], price_paise, per_user_limit?}` | admin | create a show; `201` |
| `GET /shows/{id}[?seats=false]` | none | per-seat state, `counts`, `reconciled` |
| `POST /shows/{id}/reserve` `{seats[], idempotency_key}` | user | reserve seats; `201` or a decline |
| `POST /reservations/{id}/cancel` | owner | release seats (idempotent) |
| `GET /reservations/{id}` | owner | fetch a reservation |
| `GET /healthz` | none | liveness (process is up) |
| `GET /readyz` | none | readiness: DB reachable and schema applied; `503` otherwise |
| `GET /metrics` | none | Prometheus metrics |
| `GET /logs.html`, `/logs`, `/logs/stream` | none | read-only live logs (see Observability) |

### Responses

| Status | `error` | Meaning |
|---|---|---|
| 201 | - | reserved (or an idempotent replay of an earlier success) |
| 409 | `seat_taken` | a requested seat is already taken (body lists which) |
| 409 | `per_user_limit` | would exceed the user's seat limit for this show |
| 409 | `idempotency_conflict` | same key used earlier with a different request |
| 422 | `unknown_seat` | a seat label doesn't exist in this show |
| 400 | `invalid_request` | malformed body / missing idempotency key |
| 401 / 403 | `unauthorized` / `forbidden` | no/invalid token; wrong role; cancelling someone else's reservation |
| 404 | `show_not_found` / `reservation_not_found` | |
| 503 | `not_ready` / `overloaded` | starting up, or database unreachable / pool saturated (`Retry-After: 1`) |

Declines are **4xx domain outcomes, never 500s**. Every response carries `X-Request-Id`, also logged.

### Behaviours worth knowing

- **All-or-nothing.** Asking for `["A12","A13"]` when A12 is taken returns `409 seat_taken` and A13 stays available;
  nothing is partially booked. This holds under concurrency.
- **Idempotency.** Keys are scoped per user. Same key + same request = same reservation. Same key + different seats = `409`.
  A declined attempt stores nothing, so the key can be retried later.
- **Per-user limit.** Counted per user per show across all of that user's active reservations; cancelling frees quota.
- **Cancel** only frees seats still held by *that* reservation, so it can never free a seat since re-sold to someone else.
  Cancelling twice is a no-op. There is no auto-expiry; cancel is the release model.

### Authentication

`Authorization: Bearer <token>`:

- **Admin:** the `ADMIN_TOKEN` environment value (local default `dev-admin-token`). Needed for `POST /shows`.
- **User:** a signed token from `POST /auth/token`, or the dev shortcut `user:<id>` (enabled by `ALLOW_DEV_TOKENS=true`,
  so a load test can use thousands of users without thousands of token calls). Disable it in a real deployment.

The user id is taken from the token only. A `user_id` field in a request body is ignored.

## One-command burst test

Reproduces the on-sale stampede against any deployment. Needs only **JDK 21+** (no build, no dependencies).

```sh
./burst.sh https://13-207-216-228.sslip.io                        # macOS / Linux / Git Bash
ADMIN_TOKEN=<admin token> ./burst.sh https://13-207-216-228.sslip.io
make burst BASE_URL=https://13-207-216-228.sslip.io
java burst/Burst.java https://13-207-216-228.sslip.io              # Windows (set ADMIN_TOKEN first)
```

What it does:

1. waits for `/readyz`, creates a fresh show (3,045 seats);
2. **hot-seat storm:** 5 hot seats x 500 distinct users, all at once -> exactly one winner per seat;
3. **stampede:** 20,000 requests from 6,000 users skewed to the same low-numbered seats, 10% concurrent same-key
   duplicates, while polling `GET /shows/{id}` every 100 ms to check the reconciliation invariant;
4. **targeted checks:** idempotent retries (8 parallel), same-key-different-seats, a user firing 10 parallel reserves
   at limit 4, spoofed `user_id` in the body, non-owner cancel, cancel + rebook, no resurrection, all-or-nothing;
5. **final reconciliation:** API counts vs. what the clients saw vs. `/metrics` counters and the `seats_available` gauge.

It prints the outcome distribution (confirmed / declined by reason / 5xx / network errors), every check as `ok:` or
`FAIL:`, and ends with `RESULT: PASS` (exit code 0) or `RESULT: FAIL` (exit code 1).

Tunables (environment): `ADMIN_TOKEN` (default `dev-admin-token`), `SEATS` (3000), `HOT_SEATS` (5), `HOT_USERS` (500),
`TOTAL` (20000), `USERS` (6000), `CONCURRENCY` (1000 simultaneous requests from the client).

## Observability

**Health.** `/healthz` is liveness (never touches the DB). `/readyz` runs a real `SELECT 1` on a dedicated connection and
returns `503` if the database is unreachable (fails closed), so it still answers while a burst saturates the main pool.

**Metrics** (`/metrics`, Prometheus text format):

| Metric | Type | Meaning |
|---|---|---|
| `reservations_confirmed_total` | counter | newly confirmed reservations (replays excluded) |
| `reservations_declined_total{reason}` | counter | `seat_taken`, `per_user_limit`, `idempotent_replay`, `idempotency_conflict`, `unknown_seat` |
| `reservations_cancelled_total` | counter | cancelled reservations |
| `seats_confirmed_total` | counter | seats newly confirmed |
| `seats_available`, `seats_held`, `seats_confirmed`, `seats_total` `{show_id}` | gauge | read from the database at scrape time, so they reconcile with `GET /shows/{id}` (newest 20 shows) |
| `http_server_requests_seconds_*`, `hikaricp_*`, `jvm_*` | | latency histograms, DB pool and JVM health |

Counters are per process and reset on restart.

**Logs.** One structured JSON line per request with `request_id` (echoed in `X-Request-Id` and in error bodies),
`user_id`, `method`, `path`, `status`, `ms`, `outcome`. Application logs are JSON too.

**Live logs, no server access needed.** Open `https://13-207-216-228.sslip.io/logs.html` in a browser (or
`curl -N https://13-207-216-228.sslip.io/logs/stream`). It streams the service's own structured log lines as they are
written, with running 2xx / 4xx / 5xx counters, a text filter (try `409`, `seat_taken`, a user id or a `request_id`), and
pause / clear. `GET /logs?lines=500&filter=...` returns the recent tail as NDJSON. When you run `Burst.java` with the admin token,
its own progress (phase headers, every `ok:` / `FAIL:` check and the final `RESULT`) is forwarded to the server through the
admin-only `POST /logs/note` and shows up in the same view, tagged **BURST**, in sequence with the requests that produced
it - so the page shows both the traffic and the verdict. It is read-only, shows request ids,
user ids and paths (never tokens), drops lines for slow viewers instead of slowing the service, and is switched by
`PUBLIC_LOGS=true` (off by default in the jar; on in both compose files).

**Recording logs during a burst** (on the server; two SSH windows):

```sh
# window 1: start recording (stops on Ctrl+C, or pass seconds: ./deploy/record-logs.sh 180)
cd ~/bookmyshow && ./deploy/record-logs.sh
# window 2 / your laptop: run the burst, then Ctrl+C in window 1
```

This writes `~/logs/app-<time>.jsonl` (raw) and `app-<time>.summary.txt` (request counts by status and outcome,
latency p50/p95/p99, any 5xx or application ERROR lines). A recorded summary from the live deployment is kept in
[docs/live-burst-summary.txt](docs/live-burst-summary.txt) once added. Any `request_id` in a response or error body can
be looked up with `grep <request_id> ~/logs/app-*.jsonl`. Live view: `docker compose -f docker-compose.aws.yml logs -f app`.

## Run locally

Requirements: Docker (or any PostgreSQL) and JDK 21 for the burst.

```sh
docker compose up --build          # app on http://127.0.0.1:8080, Postgres on host port 5433
./burst.sh http://127.0.0.1:8080   # in another terminal
```

Without Docker, with your own Postgres:

```sh
DATABASE_URL=postgres://user:pass@127.0.0.1:5432/seats ./mvnw package && java -jar target/app.jar
```

The schema is created automatically on startup.

### Configuration (environment variables)

| Variable | Default | Purpose |
|---|---|---|
| `DATABASE_URL` | `postgres://postgres:postgres@127.0.0.1:5432/seats` | `postgres://...` or `jdbc:postgresql://...` |
| `ADMIN_TOKEN` | `dev-admin-token` | bearer token for `POST /shows` - **set this in any real deployment** |
| `TOKEN_SECRET` | `dev-token-secret` | HMAC key for tokens from `/auth/token` |
| `ALLOW_DEV_TOKENS` | `true` | accept `Bearer user:<id>` (turn off in production) |
| `DB_POOL_MAX` | `20` | database connections |
| `PUBLIC_LOGS` | `false` | expose the read-only live log view (`/logs.html`) |
| `DEFAULT_PER_USER_LIMIT` | `4` | seats per user per show when not given at creation |
| `PORT` | `8080` | HTTP port |

## Deploy

- **AWS, single free-tier VM (what is running now):** app + Postgres + Caddy HTTPS via
  `docker-compose.aws.yml` - step-by-step in [DEPLOY-AWS.md](DEPLOY-AWS.md).
- **Render + Neon (alternative):** `render.yaml` blueprint; set `DATABASE_URL` and `ADMIN_TOKEN`. Free instances sleep
  when idle, so the first request after a pause takes about a minute.

Everything is containerised: a clean checkout builds with `docker compose up --build`.

## Repository layout

```
src/main/java/com/paytm/seats/   SeatService (the reserve/cancel logic), ApiController, Auth, Metrics, ...
src/main/resources/schema.sql    tables + the reserve_seats() function (the atomic decision)
burst/Burst.java                 the stampede / verification harness
Dockerfile, docker-compose*.yml  container builds (local, AWS)
deploy/                          Caddy config and VM setup script
WRITEUP.md, DEPLOY-AWS.md        design write-up, deployment guide
```

## Storage, retention and cleanup

| Data | Where | Bounded by |
|---|---|---|
| Live-log buffer behind `/logs.html` | app memory | last 3,000 lines (about 1-2 MB), older lines are dropped |
| Metrics | app memory | counters are fixed-size; seat gauges only for the newest 20 shows |
| Container logs | VM disk (Docker) | rotated: 10 MB x 3 files per container (about 30 MB max) |
| Shows, seats, reservations | Postgres volume on the VM disk | grows about 1-2 MB per full burst; delete with the script below |
| Recorded log files (`~/logs`), old Docker images | VM disk | removed by the script below |

Nothing is deleted automatically (reviewers may want to inspect results). To free space on the VM:

```sh
cd ~/bookmyshow
./deploy/cleanup.sh        # shows older than 24 h + Docker prune + recordings older than 7 days (newest show is always kept)
./deploy/cleanup.sh 0      # everything except the newest show
```
