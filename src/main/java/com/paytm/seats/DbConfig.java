package com.paytm.seats;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * Builds the pool from DATABASE_URL. Accepts either a JDBC url or the postgres://user:pass@host/db form that
 * Render / Neon / Railway hand out.
 */
@Configuration
public class DbConfig {

    static HikariConfig baseConfig() {
        String url = System.getenv().getOrDefault("DATABASE_URL", "postgres://postgres:postgres@127.0.0.1:5432/seats");
        HikariConfig c = new HikariConfig();
        if (url.startsWith("jdbc:")) {
            c.setJdbcUrl(url);
            String u = System.getenv("DATABASE_USER"), p = System.getenv("DATABASE_PASSWORD");
            if (u != null) c.setUsername(u);
            if (p != null) c.setPassword(p);
        } else {
            URI uri = URI.create(url.replaceFirst("^postgres(ql)?://", "http://"));
            int port = uri.getPort() == -1 ? 5432 : uri.getPort();
            // drop libpq-only params pgjdbc does not understand (e.g. Neon's channel_binding=require)
            String q = uri.getRawQuery() == null ? "" : java.util.Arrays.stream(uri.getRawQuery().split("&"))
                    .filter(p -> !p.startsWith("channel_binding=")).collect(java.util.stream.Collectors.joining("&"));
            boolean pooler = uri.getHost().contains("-pooler");
            if (pooler) q += (q.isEmpty() ? "" : "&") + "prepareThreshold=0"; // PgBouncer transaction mode
            c.setJdbcUrl("jdbc:postgresql://" + uri.getHost() + ":" + port + uri.getPath() + (q.isEmpty() ? "" : "?" + q));
            if (uri.getRawUserInfo() != null) {
                String[] ui = uri.getRawUserInfo().split(":", 2);
                c.setUsername(URLDecoder.decode(ui[0], StandardCharsets.UTF_8));
                if (ui.length > 1) c.setPassword(URLDecoder.decode(ui[1], StandardCharsets.UTF_8));
            }
        }
        return c;
    }

    @Bean
    @Primary
    public DataSource dataSource(@Value("${spring.datasource.hikari.maximum-pool-size}") int max,
                                 @Value("${spring.datasource.hikari.connection-timeout}") long waitMs) {
        HikariConfig c = baseConfig();
        c.setPoolName("main");
        c.setMaximumPoolSize(max);
        c.setConnectionTimeout(waitMs);
        c.setInitializationFailTimeout(-1);
        // never leave a connection idle inside a transaction (e.g. a crashed request)
        // (startup `options` are rejected by PgBouncer-style poolers such as Neon's -pooler endpoint)
        if (!c.getJdbcUrl().contains("-pooler")) c.addDataSourceProperty("options", "-c idle_in_transaction_session_timeout=20000");
        return new HikariDataSource(c);
    }

    /** Dedicated 1-connection pool for readiness so probes still answer while a burst saturates the main pool. */
    static HikariDataSource healthDataSource() {
        HikariConfig c = baseConfig();
        c.setPoolName("health");
        c.setMaximumPoolSize(1);
        c.setMinimumIdle(0);
        c.setConnectionTimeout(2000);
        c.setInitializationFailTimeout(-1);
        return new HikariDataSource(c);
    }
}
