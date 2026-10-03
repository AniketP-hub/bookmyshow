package com.paytm.seats;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.Semaphore;
import java.util.UUID;

/** Correlation id (request_id, echoed as X-Request-Id) + one structured access-log line per request. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger("access");
    private final Semaphore inflight;

    public RequestFilter(@Value("${app.max-inflight}") int maxInflight) {
        this.inflight = new Semaphore(maxInflight);
    }

    private static boolean isOps(String path) {
        return path.equals("/metrics") || path.equals("/healthz") || path.equals("/readyz") || path.startsWith("/logs");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        String rid = req.getHeader("X-Request-Id");
        if (rid == null || rid.isBlank() || rid.length() > 100) rid = UUID.randomUUID().toString();
        MDC.put("request_id", rid);
        res.setHeader("X-Request-Id", rid);
        long t0 = System.nanoTime();
        boolean limited = !isOps(req.getRequestURI());
        try {
            if (limited) inflight.acquireUninterruptibly(); // bulkhead: wait in line rather than swamp the DB pool
            try {
                chain.doFilter(req, res);
            } finally {
                if (limited) inflight.release();
            }
        } finally {
            String path = req.getRequestURI();
            if (!path.equals("/metrics") && !path.equals("/healthz") && !path.startsWith("/logs")) { // /logs* excluded: viewing logs must not generate logs
                MDC.put("method", req.getMethod());
                MDC.put("path", path.replaceAll("[0-9a-fA-F]{8}-[0-9a-fA-F-]{27}", ":id"));
                MDC.put("status", String.valueOf(res.getStatus()));
                MDC.put("ms", String.format("%.1f", (System.nanoTime() - t0) / 1e6));
                if (res.getStatus() >= 500) log.error("request"); else log.info("request");
            }
            MDC.clear();
        }
    }
}
