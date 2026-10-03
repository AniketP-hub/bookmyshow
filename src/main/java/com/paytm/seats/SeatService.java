package com.paytm.seats;

import com.paytm.seats.Views.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Pattern;

@Service
public class SeatService {
    private static final Pattern UUID_RE = Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final int MAX_SEATS_PER_REQUEST = 100;
    // 10^12 paise (10,000 crore rupees): keeps price x seats-per-request far below Long.MAX_VALUE, so amounts never overflow
    private static final long MAX_PRICE_PAISE = 1_000_000_000_000L;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Metrics metrics;
    private final int defaultLimit, maxSeatsPerShow;
    private final Map<UUID, ShowConfig> showCache = new ConcurrentHashMap<>(); // shows are immutable once created

    public SeatService(JdbcTemplate jdbc, PlatformTransactionManager tm, Metrics metrics,
                       @Value("${app.default-per-user-limit}") int defaultLimit,
                       @Value("${app.max-seats-per-show}") int maxSeatsPerShow) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(tm);
        this.metrics = metrics;
        this.defaultLimit = defaultLimit;
        this.maxSeatsPerShow = maxSeatsPerShow;
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private static DomainException bad(String msg) {
        return new DomainException(400, "invalid_request", msg);
    }

    /** Counts the decline in metrics and returns the exception to throw. */
    private DomainException decline(int status, String code, String msg, Map<String, Object> extra) {
        metrics.declined(code);
        return new DomainException(status, code, msg, extra);
    }

    private static Array textArray(PreparedStatement ps, Collection<String> v) throws SQLException {
        return ps.getConnection().createArrayOf("text", v.toArray());
    }

    private static UUID parseUuid(String s, int status, String code, String msg) {
        if (s == null || !UUID_RE.matcher(s).matches()) throw new DomainException(status, code, msg);
        return UUID.fromString(s);
    }

    /** Retry the whole transaction if Postgres aborts it for deadlock / serialization (should be rare: we lock in order). */
    private <T> T inTx(Supplier<T> body) {
        for (int attempt = 1; ; attempt++) {
            try {
                return tx.execute(status -> body.get());
            } catch (ConcurrencyFailureException e) {
                if (attempt >= 6) throw e;
                try { Thread.sleep((long) (Math.random() * 20 * attempt)); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw e; }
            }
        }
    }

    // ---- shows -----------------------------------------------------------------------------------

    public ShowState createShow(Map<String, Object> body) {
        Object name = body.get("name"), price = body.get("price_paise"), seatsRaw = body.get("seats"), lim = body.get("per_user_limit");
        if (!(name instanceof String n) || n.isBlank() || n.length() > 200) throw bad("name must be a non-empty string");
        if (!(price instanceof Integer || price instanceof Long) || ((Number) price).longValue() < 0 || ((Number) price).longValue() > MAX_PRICE_PAISE) {
            throw bad("price_paise must be an integer between 0 and " + MAX_PRICE_PAISE + " (paise)");
        }
        long pricePaise = ((Number) price).longValue();
        int limit = defaultLimit;
        if (lim != null) {
            if (!(lim instanceof Integer li) || li < 1 || li > 1000) throw bad("per_user_limit must be a positive integer");
            limit = li;
        }
        if (!(seatsRaw instanceof List<?> l) || l.isEmpty() || l.size() > maxSeatsPerShow) throw bad("seats must be a non-empty array (max " + maxSeatsPerShow + ")");
        List<String> seats = new ArrayList<>(l.size());
        for (Object o : l) {
            if (!(o instanceof String s) || s.isEmpty() || s.length() > 64) throw bad("seat labels must be non-empty strings (<= 64 chars)");
            seats.add(s);
        }
        if (new HashSet<>(seats).size() != seats.size()) throw bad("seat labels must be unique");

        final int fLimit = limit;
        UUID id = inTx(() -> {
            UUID sid = jdbc.queryForObject(
                    "INSERT INTO shows (name, price_paise, per_user_limit, total_seats) VALUES (?,?,?,?) RETURNING id",
                    UUID.class, n, pricePaise, fLimit, seats.size());
            jdbc.update(con -> {
                PreparedStatement ps = con.prepareStatement(
                        "INSERT INTO seats (show_id, label, ord) SELECT ?, t.label, t.ord FROM unnest(?::text[]) WITH ORDINALITY AS t(label, ord)");
                ps.setObject(1, sid);
                ps.setArray(2, textArray(ps, seats));
                return ps;
            });
            return sid;
        });
        metrics.trackShow(id);
        return showState(id, true);
    }

    private ShowConfig showConfig(UUID id) {
        ShowConfig c = showCache.get(id);
        if (c != null) return c;
        List<ShowConfig> r = jdbc.query("SELECT id, name, price_paise, per_user_limit, total_seats FROM shows WHERE id = ?",
                (rs, i) -> new ShowConfig((UUID) rs.getObject(1), rs.getString(2), rs.getLong(3), rs.getInt(4), rs.getInt(5)), id);
        if (r.isEmpty()) throw new DomainException(404, "show_not_found", "show not found");
        showCache.put(id, r.get(0));
        return r.get(0);
    }

    public ShowState showState(String rawId, boolean includeSeats) {
        return showState(parseUuid(rawId, 404, "show_not_found", "show not found"), includeSeats);
    }

    /** A single SELECT is one snapshot, so the counts always sum to total_seats. */
    private ShowState showState(UUID id, boolean includeSeats) {
        ShowConfig s = showConfig(id);
        long[] c = new long[3]; // available, held, confirmed
        List<SeatState> seats = null;
        if (includeSeats) {
            seats = new ArrayList<>(s.totalSeats());
            List<SeatState> out = seats;
            jdbc.query("SELECT label, status FROM seats WHERE show_id = ? ORDER BY ord", rs -> {
                String st = rs.getString(2);
                c[idx(st)]++;
                out.add(new SeatState(rs.getString(1), st));
            }, id);
        } else {
            jdbc.query("SELECT status, count(*) FROM seats WHERE show_id = ? GROUP BY status",
                    rs -> { c[idx(rs.getString(1))] = rs.getLong(2); }, id);
        }
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("available", c[0]);
        counts.put("held", c[1]);
        counts.put("confirmed", c[2]);
        return new ShowState(s.id(), s.name(), s.pricePaise(), s.perUserLimit(), s.totalSeats(), counts,
                c[0] + c[1] + c[2] == s.totalSeats(), seats);
    }

    private static int idx(String status) {
        return switch (status) { case "available" -> 0; case "held" -> 1; default -> 2; };
    }

    // ---- reserve ---------------------------------------------------------------------------------

    private record Existing(UUID id, UUID showId, String userId, String hash, List<String> seats, long amount, String status) {}

    private static final RowMapper<Existing> EXISTING = (rs, i) -> {
        try {
            return new Existing((UUID) rs.getObject("id"), (UUID) rs.getObject("show_id"), rs.getString("user_id"),
                    rs.getString("request_hash"), Arrays.asList((String[]) rs.getArray("seats").getArray()),
                    rs.getLong("amount_paise"), rs.getString("status"));
        } catch (SQLException e) { throw e; }
    };

    private static Reservation view(Existing e) {
        return new Reservation(e.id(), e.showId(), e.userId(), e.seats(), e.amount(), e.status());
    }

    private static String hash(UUID showId, List<String> seats) {
        try {
            List<String> sorted = new ArrayList<>(seats);
            Collections.sort(sorted);
            byte[] d = MessageDigest.getInstance("SHA-256").digest((showId + "|" + String.join("\u0000", sorted)).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private ReserveResult replay(Existing ex, String hash) {
        if (!ex.hash().equals(hash)) {
            throw decline(409, "idempotency_conflict", "idempotency key was already used with a different request", Map.of());
        }
        metrics.declined("idempotent_replay");
        return new ReserveResult(true, view(ex));
    }

    /**
     * All-or-nothing reservation. The atomic decision is the Postgres function reserve_seats (see schema.sql): in one
     * call it claims the idempotency key, locks the seat rows in label order, checks they are all 'available',
     * applies the conditional per-user-limit upsert and flips the seats - committing together or not at all.
     * Doing it in one round trip also keeps lock hold time short, which is what matters on a hot seat.
     */
    public ReserveResult reserve(String userId, String rawShowId, List<String> seats, String key) {
        UUID showId = parseUuid(rawShowId, 404, "show_not_found", "show not found");
        ShowConfig show = showConfig(showId);
        if (seats == null || seats.isEmpty() || seats.size() > MAX_SEATS_PER_REQUEST
                || seats.stream().anyMatch(s -> s == null || s.isEmpty() || s.length() > 64)) {
            throw bad("seats must be a non-empty array of seat labels");
        }
        if (new HashSet<>(seats).size() != seats.size()) throw bad("duplicate seats in request");
        if (key == null || key.isEmpty() || key.length() > 200) throw bad("idempotency key (Idempotency-Key header or idempotency_key) is required");

        String hash = hash(showId, seats);

        if (seats.size() > show.perUserLimit()) {
            throw decline(409, "per_user_limit", "at most " + show.perUserLimit() + " seats per user for this show", Map.of("limit", show.perUserLimit()));
        }

        // Fast path: reject without taking any lock when a seat is visibly unavailable. A seat leaves 'available'
        // only via a committed reserve, so this decline was true when read; it keeps a 500-way stampede on one seat
        // from queueing 499 losers on a row lock. It is never used to GRANT a seat. (A retry of the caller's own
        // successful request also sees its seats taken, so check for a stored key before declining.)
        List<String[]> peek = jdbc.query(con -> {
            PreparedStatement ps = con.prepareStatement("SELECT label, status FROM seats WHERE show_id = ? AND label = ANY(?)");
            ps.setObject(1, showId);
            ps.setArray(2, textArray(ps, seats));
            return ps;
        }, (rs, i) -> new String[]{rs.getString(1), rs.getString(2)});
        if (peek.size() != seats.size()) {
            Set<String> known = new HashSet<>();
            peek.forEach(p -> known.add(p[0]));
            List<String> unknown = seats.stream().filter(s -> !known.contains(s)).toList();
            throw decline(422, "unknown_seat", "unknown seat(s)", Map.of("seats", unknown));
        }
        List<String> unavailable = peek.stream().filter(p -> !p[1].equals("available")).map(p -> p[0]).toList();
        if (!unavailable.isEmpty()) {
            List<Existing> prior = jdbc.query("SELECT * FROM reservations WHERE user_id = ? AND idempotency_key = ?", EXISTING, userId, key);
            if (!prior.isEmpty()) return replay(prior.get(0), hash);
            throw decline(409, "seat_taken", "seat(s) already taken", Map.of("seats", unavailable));
        }

        long amount = Math.multiplyExact(show.pricePaise(), (long) seats.size());
        UUID id = UUID.randomUUID();

        record Out(String outcome, Existing existing, List<String> detail) {}
        Out out = callWithRetry(() -> jdbc.query(con -> {
            PreparedStatement ps = con.prepareStatement("SELECT * FROM reserve_seats(?,?,?,?,?,?,?,?)");
            ps.setObject(1, id);
            ps.setObject(2, showId);
            ps.setString(3, userId);
            ps.setString(4, key);
            ps.setString(5, hash);
            ps.setArray(6, textArray(ps, seats));
            ps.setLong(7, amount);
            ps.setInt(8, show.perUserLimit());
            return ps;
        }, rs -> {
            rs.next();
            String outcome = rs.getString("outcome");
            Existing ex = null;
            if (outcome.equals("ok") || outcome.equals("replay")) {
                ex = new Existing((UUID) rs.getObject("res_id"), outcome.equals("ok") ? showId : null, userId, rs.getString("res_hash"),
                        Arrays.asList((String[]) rs.getArray("res_seats").getArray()), rs.getLong("res_amount"), rs.getString("res_status"));
            }
            java.sql.Array d = rs.getArray("detail");
            return new Out(outcome, ex, d == null ? List.of() : Arrays.asList((String[]) d.getArray()));
        }));

        return switch (out.outcome()) {
            case "ok" -> {
                metrics.confirmed(seats.size());
                yield new ReserveResult(false, view(out.existing()));
            }
            case "replay" -> {
                // the stored row may belong to another show if the key was reused; the hash includes the show id
                Existing ex = out.existing();
                yield replay(new Existing(ex.id(), showId, userId, ex.hash(), ex.seats(), ex.amount(), ex.status()), hash);
            }
            case "seat_taken" -> throw decline(409, "seat_taken", "seat(s) already taken", Map.of("seats", out.detail()));
            case "per_user_limit" -> throw decline(409, "per_user_limit", "at most " + show.perUserLimit() + " seats per user for this show", Map.of("limit", show.perUserLimit()));
            default -> throw new IllegalStateException("unexpected outcome " + out.outcome());
        };
    }

    /** Retries when Postgres aborts for deadlock/serialization (should be rare: locks are taken in order); maps SX404. */
    private <T> T callWithRetry(Supplier<T> call) {
        for (int attempt = 1; ; attempt++) {
            try {
                return call.get();
            } catch (ConcurrencyFailureException e) {
                if (attempt >= 6) throw e;
                try { Thread.sleep((long) (Math.random() * 20 * attempt)); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw e; }
            } catch (org.springframework.jdbc.UncategorizedSQLException e) {
                if (e.getSQLException() != null && "SX404".equals(e.getSQLException().getSQLState())) {
                    throw decline(422, "unknown_seat", "unknown seat(s)", Map.of());
                }
                throw e;
            }
        }
    }

    // ---- cancel ----------------------------------------------------------------------------------

    private record CancelOutcome(Reservation reservation, boolean changed) {}

    public Reservation cancel(String userId, String rawId) {
        UUID rid = parseUuid(rawId, 404, "reservation_not_found", "reservation not found");
        CancelOutcome out = inTx(() -> {
            List<Existing> rows = jdbc.query("SELECT * FROM reservations WHERE id = ? FOR UPDATE", EXISTING, rid);
            if (rows.isEmpty()) throw new DomainException(404, "reservation_not_found", "reservation not found");
            Existing r = rows.get(0);
            if (!r.userId().equals(userId)) throw new DomainException(403, "forbidden", "only the owner can cancel a reservation");
            if (r.status().equals("cancelled")) return new CancelOutcome(view(r), false);

            // Lock (ordered) only seats that still point at THIS reservation, then free them. A seat that was
            // since re-sold carries another reservation_id, so it is untouched: no resurrection, no stealing.
            jdbc.query("SELECT label FROM seats WHERE show_id = ? AND reservation_id = ? ORDER BY label FOR UPDATE", rs -> { }, r.showId(), rid);
            int freed = jdbc.update("UPDATE seats SET status='available', reservation_id=NULL, user_id=NULL "
                    + "WHERE show_id = ? AND reservation_id = ? AND status = 'confirmed'", r.showId(), rid);
            jdbc.update("UPDATE user_show_quota SET held = held - ? WHERE show_id = ? AND user_id = ?", freed, r.showId(), userId);
            jdbc.update("UPDATE reservations SET status = 'cancelled', cancelled_at = now() WHERE id = ?", rid);
            return new CancelOutcome(new Reservation(r.id(), r.showId(), r.userId(), r.seats(), r.amount(), "cancelled"), true);
        });
        if (out.changed()) metrics.cancelled();
        return out.reservation();
    }

    public Reservation getReservation(String userId, String rawId) {
        UUID rid = parseUuid(rawId, 404, "reservation_not_found", "reservation not found");
        List<Existing> rows = jdbc.query("SELECT * FROM reservations WHERE id = ?", EXISTING, rid);
        if (rows.isEmpty()) throw new DomainException(404, "reservation_not_found", "reservation not found");
        if (!rows.get(0).userId().equals(userId)) throw new DomainException(403, "forbidden", "not your reservation");
        return view(rows.get(0));
    }
}
