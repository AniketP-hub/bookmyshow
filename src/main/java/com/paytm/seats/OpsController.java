package com.paytm.seats;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class OpsController {
    private final Lifecycle lifecycle;
    private final HikariDataSource healthDs = DbConfig.healthDataSource();
    private final JdbcTemplate healthJdbc = new JdbcTemplate(healthDs);

    public OpsController(Lifecycle lifecycle) {
        this.lifecycle = lifecycle;
        healthJdbc.setQueryTimeout(2);
    }

    /** Liveness: the process is up and serving HTTP. Deliberately does not touch the DB. */
    @GetMapping("/healthz")
    public Map<String, String> healthz() {
        return Map.of("status", "ok");
    }

    /** Readiness: schema applied AND the database answers right now; fails closed with 503 otherwise. */
    @GetMapping("/readyz")
    public ResponseEntity<Map<String, String>> readyz() {
        try {
            if (!lifecycle.isMigrated()) throw new IllegalStateException("schema not applied yet");
            healthJdbc.queryForObject("SELECT 1", Integer.class);
            return ResponseEntity.ok(Map.of("status", "ready", "db", "up"));
        } catch (Exception e) {
            return ResponseEntity.status(503).body(Map.of("status", "not_ready", "db", "down", "reason", String.valueOf(e.getMessage())));
        }
    }

    @PreDestroy
    void close() {
        healthDs.close();
    }
}
