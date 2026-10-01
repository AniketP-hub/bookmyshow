package com.paytm.seats;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Cold start: the HTTP server is up immediately (liveness ok) while the schema is applied in the background,
 * retrying until the database answers. Until then readiness fails and API calls get 503.
 */
@Component
public class Lifecycle {
    private static final Logger log = LoggerFactory.getLogger(Lifecycle.class);
    private final JdbcTemplate jdbc;
    private final AtomicBoolean migrated = new AtomicBoolean(false);

    public Lifecycle(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isMigrated() {
        return migrated.get();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        Thread.ofPlatform().name("migrator").daemon().start(() -> {
            for (int i = 1; ; i++) {
                try {
                    String ddl = new String(new ClassPathResource("schema.sql").getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                    jdbc.execute((java.sql.Connection c) -> {
                        try (var st = c.createStatement()) {
                            st.execute("SELECT pg_advisory_lock(727001)"); // serialise concurrent boots
                            try { st.execute(ddl); } finally { st.execute("SELECT pg_advisory_unlock(727001)"); }
                        }
                        return null;
                    });
                    migrated.set(true);
                    log.info("schema ready");
                    return;
                } catch (Exception e) {
                    log.warn("database not ready (attempt {}): {}", i, e.getMessage());
                    try { Thread.sleep(Math.min(5000, 500L * i)); } catch (InterruptedException ie) { return; }
                }
            }
        });
    }
}
