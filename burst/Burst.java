import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.regex.*;

/**
 * On-sale stampede against a live deployment. No dependencies; needs JDK 21.
 *
 *   java burst/Burst.java https://your-service.onrender.com
 *
 * Env: ADMIN_TOKEN (default dev-admin-token), SEATS (3000), HOT_SEATS (5), HOT_USERS (500),
 *      TOTAL (20000), USERS (6000), CONCURRENCY (1000)
 *
 * Phases: 1) hot-seat storm  2) 20k mixed stampede (+ same-key concurrent duplicates) with live invariant polling
 *         3) targeted checks (idempotency, per-user limit race, spoofing, cancel/rebook, all-or-nothing)
 *         4) final reconciliation: API state vs. what the clients saw vs. /metrics
 * Exit code 0 only if every check passes.
 */
public class Burst {
    record Res(int status, String body, boolean replayed) {
        String err() { return str(body, "error"); }
    }

    static String base, adminToken;
    static final String RUN = Long.toString(System.currentTimeMillis(), 36) + "-";
    static HttpClient http;
    static Semaphore permits;
    static final List<String> failures = Collections.synchronizedList(new ArrayList<>());
    static final ConcurrentHashMap<String, LongAdder> outcomes = new ConcurrentHashMap<>();
    static final ConcurrentHashMap<String, Set<String>> seatSales = new ConcurrentHashMap<>(); // seat -> reservation ids ever 201'd
    static final ConcurrentHashMap<String, String> active = new ConcurrentHashMap<>();         // seat -> current reservation id
    static final ConcurrentHashMap<String, Set<String>> resByKey = new ConcurrentHashMap<>();   // user|key -> reservation ids
    static final ConcurrentHashMap<String, List<String>> resSeats = new ConcurrentHashMap<>();  // reservation id -> seats
    static final ConcurrentHashMap<String, String> resUser = new ConcurrentHashMap<>();
    static final LongAdder netErrors = new LongAdder(), fiveXX = new LongAdder();
    static final ConcurrentHashMap<String, LongAdder> netKinds = new ConcurrentHashMap<>();
    static final LongAdder clientConfirmed = new LongAdder(), clientReplay = new LongAdder();
    static final LongAdder clientCancelled = new LongAdder();
    static final ConcurrentHashMap<String, LongAdder> clientDeclined = new ConcurrentHashMap<>();

    // ---- progress reporting: print locally AND send to the server's live log (POST /logs/note, admin token) ----
    static final ExecutorService noteExec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "notes");
        t.setDaemon(true);
        return t;
    });

    static String jsonEsc(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') sb.append('\\').append(c);
            else if (c < 0x20) sb.append(' ');
            else sb.append(c);
        }
        return sb.toString();
    }

    /** Print a line; also best-effort forward it (in order) so it shows in the service's live log view. */
    static void out(String s) {
        System.out.println(s);
        if (base == null || http == null || s.isBlank()) return;
        String text = s.strip();
        noteExec.submit(() -> {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/logs/note")).timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/json").header("Authorization", "Bearer " + adminToken)
                        .POST(HttpRequest.BodyPublishers.ofString("{\"text\":\"" + jsonEsc(text) + "\"}")).build();
                http.send(req, HttpResponse.BodyHandlers.discarding());
            } catch (Exception ignored) {
                // live-log forwarding is optional; never affect the test
            }
        });
    }

    static void finish(int code) {
        noteExec.shutdown();
        try {
            noteExec.awaitTermination(8, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        System.exit(code);
    }

    static String str(String body, String field) {
        Matcher m = Pattern.compile("\"" + field + "\":\"([^\"]*)\"").matcher(body == null ? "" : body);
        return m.find() ? m.group(1) : "";
    }

    static long num(String body, String field) {
        Matcher m = Pattern.compile("\"" + field + "\":(\\d+)").matcher(body == null ? "" : body);
        return m.find() ? Long.parseLong(m.group(1)) : -1;
    }

    static void fail(String msg) {
        failures.add(msg);
        out("  FAIL: " + msg);
    }

    static void check(boolean ok, String msg) {
        if (!ok) fail(msg);
        else out("  ok:   " + msg);
    }

    static Res call(String method, String path, String token, String key, String json) {
        try {
            permits.acquire();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(90))
                    .header("Content-Type", "application/json");
            if (token != null) b.header("Authorization", "Bearer " + token);
            if (key != null) b.header("Idempotency-Key", key);
            b.method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
            HttpResponse<String> r = null;
            for (int attempt = 1; ; attempt++) {
                try {
                    r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
                    break;
                } catch (java.net.ConnectException | java.net.http.HttpConnectTimeoutException ce) { // never reached the server (connect failed/timed out): safe to retry
                    if (attempt >= 6) throw ce;
                    Thread.sleep(200L * attempt + ThreadLocalRandom.current().nextInt(300));
                }
            }
            Res res = new Res(r.statusCode(), r.body(), r.headers().firstValue("Idempotent-Replayed").isPresent());
            if (res.status() >= 500) fiveXX.increment();
            return res;
        } catch (Exception e) {
            netErrors.increment();
            netKinds.computeIfAbsent(e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage()).replaceAll("\s+", " ").substring(0, Math.min(70, String.valueOf(e.getMessage()).length())), k -> new LongAdder()).increment();
            return new Res(-1, "{\"error\":\"net:" + e.getClass().getSimpleName() + "\"}", false);
        } finally {
            permits.release();
        }
    }

    static String seatsJson(List<String> seats) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < seats.size(); i++) sb.append(i > 0 ? "," : "").append('"').append(seats.get(i)).append('"');
        return sb.append("]").toString();
    }

    static void tally(String phase, Res r) {
        String k = phase + " | " + (r.status() == -1 ? "network_error" : r.status() + " " + (r.status() == 201 ? (r.replayed() ? "idempotent_replay" : "confirmed") : r.err()));
        outcomes.computeIfAbsent(k, x -> new LongAdder()).increment();
        if (r.status() == 201) {
            if (r.replayed()) clientReplay.increment();
            else clientConfirmed.increment();
        } else if (r.status() == 409 || r.status() == 422) {
            clientDeclined.computeIfAbsent(r.err(), x -> new LongAdder()).increment();
        }
        if (r.status() == 201 && r.replayed()) clientDeclined.computeIfAbsent("idempotent_replay", x -> new LongAdder()).increment();
    }

    /** reserve + record into the ledgers. */
    static Res reserve(String phase, String showId, String user, String key, List<String> seats) {
        // the body deliberately carries a spoofed user_id: only the token may decide identity
        String body = "{\"seats\":" + seatsJson(seats) + ",\"idempotency_key\":\"" + key + "\",\"user_id\":\"spoofed-admin\"}";
        Res r = call("POST", "/shows/" + showId + "/reserve", "user:" + user, null, body);
        tally(phase, r);
        if (r.status() == 201) {
            String rid = str(r.body(), "reservation_id");
            if (!user.equals(str(r.body(), "user_id"))) fail("response user_id '" + str(r.body(), "user_id") + "' != token user '" + user + "'");
            resByKey.computeIfAbsent(user + "|" + key, x -> ConcurrentHashMap.newKeySet()).add(rid);
            resSeats.put(rid, seats);
            resUser.put(rid, user);
            if (!r.replayed()) {
                for (String s : seats) {
                    seatSales.computeIfAbsent(s, x -> ConcurrentHashMap.newKeySet()).add(rid);
                    active.put(s, rid);
                }
            }
        }
        return r;
    }

    record Counts(long available, long held, long confirmed, long total, boolean ok) {}

    static Counts counts(String showId) {
        Res r = call("GET", "/shows/" + showId + "?seats=false", null, null, null);
        if (r.status() != 200) return new Counts(-1, -1, -1, -1, false);
        long a = num(r.body(), "available"), h = num(r.body(), "held"), c = num(r.body(), "confirmed"), t = num(r.body(), "total_seats");
        return new Counts(a, h, c, t, a + h + c == t);
    }

    static String seatStatus(String showId, String seat) {
        Res r = call("GET", "/shows/" + showId, null, null, null);
        Matcher m = Pattern.compile("\"seat\":\"" + Pattern.quote(seat) + "\",\"status\":\"([a-z]+)\"").matcher(r.body());
        return m.find() ? m.group(1) : "?";
    }

    static Map<String, Double> metrics() {
        Map<String, Double> out = new HashMap<>();
        Res r = call("GET", "/metrics", null, null, null);
        for (String line : r.body().split("\n")) {
            if (line.startsWith("#") || line.isBlank()) continue;
            int sp = line.lastIndexOf(' ');
            try { out.put(line.substring(0, sp), Double.parseDouble(line.substring(sp + 1))); } catch (Exception ignored) { }
        }
        return out;
    }

    static double delta(Map<String, Double> a, Map<String, Double> b, String k) {
        return b.getOrDefault(k, 0.0) - a.getOrDefault(k, 0.0);
    }

    static void runAll(List<Runnable> tasks, ExecutorService ex) throws Exception {
        List<Future<?>> fs = new ArrayList<>(tasks.size());
        for (Runnable t : tasks) fs.add(ex.submit(t));
        for (Future<?> f : fs) f.get();
    }

    static int envInt(String k, int d) {
        String v = System.getenv(k);
        return v == null || v.isBlank() ? d : Integer.parseInt(v);
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            out("usage: java burst/Burst.java <BASE_URL>");
            System.exit(2);
        }
        base = args[0].replaceAll("/+$", "");
        adminToken = Optional.ofNullable(System.getenv("ADMIN_TOKEN")).orElse("dev-admin-token");
        int N = envInt("SEATS", 3000), HOT = envInt("HOT_SEATS", 5), HOT_USERS = envInt("HOT_USERS", 500);
        int TOTAL = envInt("TOTAL", 20000), USERS = envInt("USERS", 6000), CONC = envInt("CONCURRENCY", 1000);
        permits = new Semaphore(CONC);
        ExecutorService vt = Executors.newVirtualThreadPerTaskExecutor();
        http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(30)).executor(vt).build();
        Random rnd = new Random(42);

        out("== target " + base + "  (seats=" + N + " hot=" + HOT + "x" + HOT_USERS + " total=" + TOTAL + " conc=" + CONC + ")");

        // ---- wait for readiness (cold start on free tiers can take a minute) --------------------------
        long deadline = System.currentTimeMillis() + 180_000;
        while (true) {
            Res r = call("GET", "/readyz", null, null, null);
            if (r.status() == 200) break;
            if (System.currentTimeMillis() > deadline) { out("service never became ready: " + r.status()); System.exit(1); }
            out("  waiting for /readyz (" + r.status() + ")...");
            Thread.sleep(3000);
        }
        fiveXX.reset(); netErrors.reset();

        // ---- setup: create the show --------------------------------------------------------------------
        List<String> hot = new ArrayList<>(), all = new ArrayList<>(), tSeats = new ArrayList<>(), cold = new ArrayList<>();
        for (int i = 1; i <= HOT; i++) hot.add("HOT" + i);
        for (int i = 1; i <= N; i++) cold.add("S" + i);
        for (int i = 1; i <= 40; i++) tSeats.add("T" + i);
        all.addAll(hot); all.addAll(cold); all.addAll(tSeats);
        Res created = call("POST", "/shows", adminToken, null, "{\"name\":\"burst-" + System.currentTimeMillis() + "\",\"seats\":" + seatsJson(all) + ",\"price_paise\":25000}");
        if (created.status() != 201) { out("create show failed: " + created.status() + " " + created.body()); System.exit(1); }
        String showId = str(created.body(), "id");
        long totalSeats = all.size();
        out("show " + showId + " total_seats=" + totalSeats);
        Map<String, Double> m0 = metrics();

        // ---- live invariant poller ----------------------------------------------------------------------
        AtomicBoolean polling = new AtomicBoolean(true);
        AtomicLong polls = new AtomicLong(), badPolls = new AtomicLong(), pollErrors = new AtomicLong();
        Thread poller = Thread.ofVirtual().start(() -> {
            while (polling.get()) {
                Counts c = counts(showId);
                if (c.total() == -1) { pollErrors.incrementAndGet(); continue; } // transport error, says nothing about the invariant
                polls.incrementAndGet();
                if (!c.ok() || c.total() != totalSeats) {
                    badPolls.incrementAndGet();
                    fail("invariant broken mid-burst: " + c);
                }
                try { Thread.sleep(100); } catch (InterruptedException e) { return; }
            }
        });

        // ---- phase 1: hot seat storm ----------------------------------------------------------------------
        out("\n== phase 1: hot-seat storm (" + HOT + " seats x " + HOT_USERS + " users, all at once)");
        long t1 = System.nanoTime();
        Map<String, LongAdder> hotWins = new ConcurrentHashMap<>();
        List<Runnable> tasks = new ArrayList<>();
        for (String seat : hot) {
            for (int u = 0; u < HOT_USERS; u++) {
                final int uu = u;
                tasks.add(() -> {
                    Res r = reserve("hot", showId, "hot-" + seat + "-u" + uu, UUID.randomUUID().toString(), List.of(seat));
                    if (r.status() == 201) hotWins.computeIfAbsent(seat, x -> new LongAdder()).increment();
                    else if (r.status() != 409 || !"seat_taken".equals(r.err())) fail("hot seat loser got " + r.status() + " " + r.err());
                });
            }
        }
        Collections.shuffle(tasks, rnd);
        runAll(tasks, vt);
        out(String.format("  %d requests in %.1fs", tasks.size(), (System.nanoTime() - t1) / 1e9));
        for (String seat : hot) check(hotWins.getOrDefault(seat, new LongAdder()).sum() == 1, seat + ": exactly one winner (got " + hotWins.getOrDefault(seat, new LongAdder()).sum() + ")");

        // ---- phase 2: mixed stampede ----------------------------------------------------------------------
        out("\n== phase 2: stampede (" + TOTAL + " requests, " + USERS + " users, skewed to low seat numbers, 10% concurrent same-key duplicates)");
        long t2 = System.nanoTime();
        tasks = new ArrayList<>();
        for (int i = 0; i < TOTAL; i++) {
            String user = "u" + rnd.nextInt(USERS);
            int k = rnd.nextInt(100) < 70 ? 1 : (rnd.nextInt(100) < 80 ? 2 : 3);
            LinkedHashSet<String> pick = new LinkedHashSet<>();
            while (pick.size() < k) pick.add(cold.get((int) (N * Math.pow(rnd.nextDouble(), 3))));
            List<String> seats = new ArrayList<>(pick);
            String key = UUID.randomUUID().toString();
            tasks.add(() -> reserve("stampede", showId, user, key, seats));
            if (rnd.nextInt(100) < 10) tasks.add(() -> reserve("stampede", showId, user, key, seats)); // same key, same body, concurrently
        }
        Collections.shuffle(tasks, rnd);
        runAll(tasks, vt);
        double secs2 = (System.nanoTime() - t2) / 1e9;
        out(String.format("  %d requests in %.1fs (%.0f req/s)", tasks.size(), secs2, tasks.size() / secs2));

        // verify the ledgers right after phase 1+2 (no cancels yet, so any seat with >1 reservation is a double sale)
        long doubleSold = seatSales.entrySet().stream().filter(e -> e.getValue().size() > 1).count();
        check(doubleSold == 0, "no seat sold twice (" + seatSales.size() + " distinct seats sold, " + doubleSold + " double-sold)");
        long dupKeys = resByKey.values().stream().filter(s -> s.size() > 1).count();
        check(dupKeys == 0, "every idempotency key maps to exactly one reservation (" + dupKeys + " violations)");
        Map<String, Integer> perUser = new HashMap<>();
        for (String rid : new HashSet<>(active.values())) perUser.merge(resUser.get(rid), resSeats.get(rid).size(), Integer::sum);
        int maxHeld = perUser.values().stream().max(Integer::compare).orElse(0);
        check(maxHeld <= 4, "per-user limit held under stampede (max seats for one user = " + maxHeld + ")");
        Counts afterStampede = counts(showId);
        check(afterStampede.ok(), "reconciliation after stampede: " + afterStampede);
        check(afterStampede.confirmed() == active.size(), "API confirmed (" + afterStampede.confirmed() + ") == seats won by clients (" + active.size() + ")");

        // ---- phase 3: targeted checks ------------------------------------------------------------------------
        out("\n== phase 3: targeted checks");
        Res r;
        r = call("POST", "/shows/" + showId + "/reserve", null, "k", "{\"seats\":[\"T1\"]}");
        check(r.status() == 401, "no token -> 401 (got " + r.status() + ")");
        r = call("POST", "/shows", "user:mallory", null, "{}");
        check(r.status() == 403, "user token on admin endpoint -> 403 (got " + r.status() + ")");

        // idempotent retries (8 parallel with the same key)
        Counts before = counts(showId);
        Set<String> ids = ConcurrentHashMap.newKeySet();
        List<Runnable> par = new ArrayList<>();
        for (int i = 0; i < 8; i++) par.add(() -> { Res x = reserve("idem", showId, "idem-user", RUN + "idem-key", List.of("T1")); if (x.status() == 201) ids.add(str(x.body(), "reservation_id")); else fail("idem retry got " + x.status()); });
        runAll(par, vt);
        Counts after = counts(showId);
        check(ids.size() == 1, "8 parallel retries, same key -> one reservation id");
        check(after.confirmed() - before.confirmed() == 1, "...and exactly one seat moved (delta " + (after.confirmed() - before.confirmed()) + ")");
        r = reserve("idem", showId, "idem-user", RUN + "idem-key", List.of("T2"));
        check(r.status() == 409 && "idempotency_conflict".equals(r.err()), "same key, different seats -> 409 idempotency_conflict (got " + r.status() + " " + r.err() + ")");
        check(!"confirmed".equals(seatStatus(showId, "T2")), "...and T2 untouched");

        // per-user limit race
        before = counts(showId);
        AtomicInteger limitWins = new AtomicInteger(), limitDeclines = new AtomicInteger();
        par = new ArrayList<>();
        for (int i = 3; i <= 12; i++) {
            final String seat = "T" + i;
            par.add(() -> { Res x = reserve("limit", showId, "limit-user", UUID.randomUUID().toString(), List.of(seat)); if (x.status() == 201) limitWins.incrementAndGet(); else if (x.status() == 409 && "per_user_limit".equals(x.err())) limitDeclines.incrementAndGet(); else fail("limit race got " + x.status() + " " + x.err()); });
        }
        runAll(par, vt);
        after = counts(showId);
        check(limitWins.get() == 4 && limitDeclines.get() == 6, "10 parallel reserves, limit 4 -> 4 confirmed / 6 per_user_limit (got " + limitWins + "/" + limitDeclines + ")");
        check(after.confirmed() - before.confirmed() == 4, "...and exactly 4 seats moved");

        // identity spoofing + owner-only cancel + no resurrection
        r = reserve("auth", showId, "mallory", RUN + "m-key", List.of("T13"));
        check(r.status() == 201 && "mallory".equals(str(r.body(), "user_id")), "spoofed body user_id ignored; reservation belongs to token user");
        String mrid = str(r.body(), "reservation_id");
        r = call("POST", "/reservations/" + mrid + "/cancel", "user:victim", null, null);
        check(r.status() == 403, "other user cancel -> 403 (got " + r.status() + ")");
        check("confirmed".equals(seatStatus(showId, "T13")), "...seat still confirmed");
        r = call("POST", "/reservations/" + mrid + "/cancel", "user:mallory", null, null);
        check(r.status() == 200 && "cancelled".equals(str(r.body(), "status")), "owner cancel -> 200 cancelled");
        if (r.status() == 200) { clientCancelled.increment(); active.remove("T13", mrid); }
        check("available".equals(seatStatus(showId, "T13")), "released seat is available again");
        r = reserve("auth", showId, "trent", RUN + "t-key", List.of("T13"));
        check(r.status() == 201, "released seat is re-bookable (got " + r.status() + ")");
        r = call("POST", "/reservations/" + mrid + "/cancel", "user:mallory", null, null);
        check(r.status() == 200, "re-cancel of old reservation is a harmless no-op");
        check("confirmed".equals(seatStatus(showId, "T13")), "...and does NOT free the seat now owned by trent");

        // all-or-nothing
        reserve("partial", showId, "p0", RUN + "p0-key", List.of("T14"));
        r = reserve("partial", showId, "partial-user", RUN + "pp-key", List.of("T14", "T15"));
        check(r.status() == 409, "[taken, free] -> 409 (got " + r.status() + ")");
        check("available".equals(seatStatus(showId, "T15")), "...free seat T15 was NOT taken (all-or-nothing)");

        polling.set(false);
        poller.join();

        // ---- phase 4: final reconciliation --------------------------------------------------------------------
        out("\n== phase 4: final reconciliation");
        Counts fin = counts(showId);
        check(fin.ok() && fin.total() == totalSeats, "available + held + confirmed == total_seats: " + fin.available() + " + " + fin.held() + " + " + fin.confirmed() + " == " + fin.total());
        check(fin.confirmed() == active.size(), "API confirmed (" + fin.confirmed() + ") == seats held by clients (" + active.size() + ")");
        check(badPolls.get() == 0, "invariant held on all " + polls.get() + " live polls during the burst (" + pollErrors.get() + " polls failed in transport and are not counted)");

        Map<String, Double> m1 = metrics();
        check(delta(m0, m1, "reservations_confirmed_total") == clientConfirmed.sum(), "metrics reservations_confirmed_total delta (" + (long) delta(m0, m1, "reservations_confirmed_total") + ") == client-observed new 201s (" + clientConfirmed.sum() + ")");
        for (String reason : List.of("seat_taken", "per_user_limit", "idempotent_replay", "idempotency_conflict")) {
            long mine = clientDeclined.getOrDefault(reason, new LongAdder()).sum();
            long theirs = (long) delta(m0, m1, "reservations_declined_total{reason=\"" + reason + "\"}");
            check(mine == theirs, "metrics declined{" + reason + "} delta (" + theirs + ") == client-observed (" + mine + ")");
        }
        check(delta(m0, m1, "reservations_cancelled_total") == clientCancelled.sum(), "metrics reservations_cancelled_total delta == client-observed cancels");
        double gauge = m1.getOrDefault("seats_available{show_id=\"" + showId + "\"}", -1.0);
        check((long) gauge == fin.available(), "metrics seats_available gauge (" + (long) gauge + ") == API available (" + fin.available() + ")");

        // ---- report ----------------------------------------------------------------------------------------------
        out("\n== outcome distribution (client side)");
        new TreeMap<>(outcomes).forEach((k, v) -> out(String.format("  %-52s %d", k, v.sum())));
        out("  " + "-".repeat(60));
        out(String.format("  5xx responses: %d    network errors: %d", fiveXX.sum(), netErrors.sum()));
        netKinds.forEach((k, v) -> out("    network error kind: " + k + "  x" + v.sum()));
        check(fiveXX.sum() == 0, "zero 5xx responses");
        check(netErrors.sum() == 0, "zero network errors / timeouts");

        out("");
        if (failures.isEmpty()) {
            out("RESULT: PASS");
        } else {
            out("RESULT: FAIL (" + failures.size() + " checks)");
            finish(1);
        }
        finish(0);
    }
}
