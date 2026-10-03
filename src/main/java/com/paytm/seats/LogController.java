package com.paytm.seats;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Read-only public view of the service's own structured logs (enabled with PUBLIC_LOGS=true):
 *   GET /logs?lines=200&filter=text    last N log lines (NDJSON)
 *   GET /logs/stream?filter=text       live Server-Sent Events stream
 *   /logs.html                         small web page that renders the stream
 * Logs contain request ids, user ids and paths - never tokens or secrets.
 */
@RestController
public class LogController {
    private static final int MAX_VIEWERS = 25;
    private final boolean enabled;

    public LogController(@Value("${app.public-logs}") boolean enabled) {
        this.enabled = enabled;
    }

    private void check() {
        if (!enabled) throw new DomainException(404, "not_found", "not found");
    }

    @GetMapping(value = "/logs", produces = "application/x-ndjson")
    public String recent(@RequestParam(defaultValue = "200") int lines, @RequestParam(required = false) String filter) {
        check();
        StringBuilder sb = new StringBuilder();
        for (String l : LogBuffer.recent(Math.min(Math.max(lines, 1), 2000))) {
            if (matches(l, filter)) sb.append(l).append('\n');
        }
        return sb.toString();
    }

    @GetMapping(value = "/logs/stream", produces = "text/event-stream")
    public SseEmitter stream(@RequestParam(required = false) String filter) {
        check();
        if (LogBuffer.subscriberCount() >= MAX_VIEWERS) {
            throw new DomainException(503, "overloaded", "too many log viewers, retry shortly");
        }
        SseEmitter emitter = new SseEmitter(TimeUnit.MINUTES.toMillis(30)); // browsers reconnect automatically
        LogBuffer.Subscriber sub = LogBuffer.subscribe();
        AtomicBoolean open = new AtomicBoolean(true);
        Runnable stop = () -> {
            open.set(false);
            LogBuffer.unsubscribe(sub);
        };
        emitter.onCompletion(stop);
        emitter.onTimeout(stop);
        emitter.onError(e -> stop.run());

        Thread.ofVirtual().name("log-stream").start(() -> {
            try {
                for (String l : LogBuffer.recent(50)) {
                    if (matches(l, filter)) emitter.send(SseEmitter.event().data(l));
                }
                while (open.get()) {
                    String line = sub.queue.poll(10, TimeUnit.SECONDS);
                    if (line == null) {
                        emitter.send(SseEmitter.event().comment("keep-alive"));
                    } else if (matches(line, filter)) {
                        emitter.send(SseEmitter.event().data(line));
                    }
                }
            } catch (Exception ignored) {
                // the viewer went away
            } finally {
                stop.run();
                emitter.complete();
            }
        });
        return emitter;
    }

    private static boolean matches(String line, String filter) {
        return filter == null || filter.isEmpty() || line.contains(filter);
    }
}
