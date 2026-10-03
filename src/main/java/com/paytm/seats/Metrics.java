package com.paytm.seats;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Prometheus names (Micrometer appends _total to counters):
 *   reservations_confirmed_total, seats_confirmed_total, reservations_cancelled_total,
 *   reservations_declined_total{reason}, seats_available/held/confirmed/total{show_id}.
 * The seat gauges are computed from the database at scrape time, so they reconcile with GET /shows/{id}.
 */
@Component
public class Metrics {
    private static final long CACHE_MS = 250; // one query serves all gauges of a scrape

    private final MeterRegistry registry;
    private final JdbcTemplate jdbc;
    private final Counter confirmed, seatsConfirmed, cancelled;
    private final Map<String, Counter> declined = new ConcurrentHashMap<>();
    private final Set<UUID> registered = ConcurrentHashMap.newKeySet();

    private record Snap(long available, long held, long confirmed, long total) {}
    private volatile Map<UUID, Snap> snap = Map.of();
    private volatile long snapAt = 0;

    public Metrics(MeterRegistry registry, JdbcTemplate jdbc) {
        this.registry = registry;
        this.jdbc = jdbc;
        confirmed = Counter.builder("reservations.confirmed").description("Reservations newly confirmed (replays excluded)").register(registry);
        seatsConfirmed = Counter.builder("seats.confirmed").description("Seats newly confirmed").register(registry);
        cancelled = Counter.builder("reservations.cancelled").description("Reservations cancelled").register(registry);
        for (String r : List.of("seat_taken", "per_user_limit", "idempotent_replay", "idempotency_conflict", "unknown_seat")) counter(r);
    }

    public void confirmed(int seats) {
        confirmed.increment();
        seatsConfirmed.increment(seats);
    }

    public void cancelled() { cancelled.increment(); }

    private Counter counter(String reason) {
        return declined.computeIfAbsent(reason, r -> Counter.builder("reservations.declined")
                .description("Reservation requests not newly confirmed, by reason").tag("reason", r).register(registry));
    }

    public void declined(String reason) {
        counter(reason).increment();
    }

    /** Start exporting seat gauges for a show (called on creation and for recent shows at first scrape). */
    public void trackShow(UUID id) {
        if (!registered.add(id)) return;
        String sid = id.toString();
        Gauge.builder("seats.available", () -> read(id, 0)).tag("show_id", sid).register(registry);
        Gauge.builder("seats.held", () -> read(id, 1)).tag("show_id", sid).register(registry);
        Gauge.builder("seats.confirmed", () -> read(id, 2)).tag("show_id", sid).register(registry);
        Gauge.builder("seats.total", () -> read(id, 3)).tag("show_id", sid).register(registry);
    }

    private double read(UUID id, int which) {
        Snap s = snapshot().get(id);
        if (s == null) return Double.NaN;
        return switch (which) { case 0 -> s.available(); case 1 -> s.held(); case 2 -> s.confirmed(); default -> s.total(); };
    }

    private Map<UUID, Snap> snapshot() {
        long now = System.currentTimeMillis();
        if (now - snapAt < CACHE_MS) return snap;
        synchronized (this) {
            if (System.currentTimeMillis() - snapAt < CACHE_MS) return snap;
            try {
                Map<UUID, Snap> m = new ConcurrentHashMap<>();
                jdbc.query("""
                        SELECT sh.id, sh.total_seats,
                               count(*) FILTER (WHERE s.status='available') a,
                               count(*) FILTER (WHERE s.status='held') h,
                               count(*) FILTER (WHERE s.status='confirmed') c
                          FROM (SELECT id, total_seats FROM shows ORDER BY created_at DESC LIMIT 20) sh
                          JOIN seats s ON s.show_id = sh.id GROUP BY sh.id, sh.total_seats""",
                        rs -> {
                            UUID id = (UUID) rs.getObject(1);
                            registered.add(id);
                            m.put(id, new Snap(rs.getLong("a"), rs.getLong("h"), rs.getLong("c"), rs.getLong("total_seats")));
                        });
                snap = m;
                snapAt = System.currentTimeMillis();
                for (UUID id : m.keySet()) ensureGauges(id);
                dropGaugesOutside(m.keySet());
            } catch (Exception e) {
                snap = Map.of(); // DB down => gauges NaN rather than stale lies
                snapAt = System.currentTimeMillis();
            }
            return snap;
        }
    }

    /** Shows that fell out of the newest-20 window stop being exported, so meters (and memory) do not pile up. */
    private void dropGaugesOutside(Set<UUID> keep) {
        for (UUID id : registered) {
            if (keep.contains(id)) continue;
            String sid = id.toString();
            for (String name : List.of("seats.available", "seats.held", "seats.confirmed", "seats.total")) {
                var g = registry.find(name).tag("show_id", sid).gauge();
                if (g != null) registry.remove(g);
            }
            registered.remove(id);
        }
    }

    private void ensureGauges(UUID id) {
        String sid = id.toString();
        if (registry.find("seats.available").tag("show_id", sid).gauge() == null) {
            registered.remove(id);
            trackShow(id);
        }
    }
}
