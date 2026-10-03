package com.paytm.seats;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory tail of the application's own log output (the same JSON lines written to the console), so a read-only
 * live view can be offered over HTTP. Publishing never blocks a request: slow viewers lose lines instead.
 */
public final class LogBuffer {
    private static final int CAPACITY = 3000;
    private static final ArrayDeque<String> RING = new ArrayDeque<>(CAPACITY);
    private static final List<Subscriber> SUBSCRIBERS = new CopyOnWriteArrayList<>();

    private LogBuffer() {}

    public static final class Subscriber {
        public final BlockingQueue<String> queue = new ArrayBlockingQueue<>(4000);
        public final AtomicLong dropped = new AtomicLong();
    }

    /** Wrap System.out so every console line is also kept here. Call once, before logging starts. */
    public static void install() {
        System.setOut(new PrintStream(new Tee(System.out), true, StandardCharsets.UTF_8));
    }

    static void publish(String line) {
        // never expose credentials, even if one were ever logged by mistake
        if (line.contains("Bearer ") || line.contains("password")) return;
        synchronized (RING) {
            if (RING.size() == CAPACITY) RING.removeFirst();
            RING.addLast(line);
        }
        for (Subscriber s : SUBSCRIBERS) {
            if (!s.queue.offer(line)) s.dropped.incrementAndGet();
        }
    }

    public static List<String> recent(int n) {
        synchronized (RING) {
            List<String> all = new ArrayList<>(RING);
            return all.subList(Math.max(0, all.size() - n), all.size());
        }
    }

    public static Subscriber subscribe() {
        Subscriber s = new Subscriber();
        SUBSCRIBERS.add(s);
        return s;
    }

    public static void unsubscribe(Subscriber s) {
        SUBSCRIBERS.remove(s);
    }

    public static int subscriberCount() {
        return SUBSCRIBERS.size();
    }

    /** Passes bytes through unchanged and publishes each complete line. */
    private static final class Tee extends OutputStream {
        private final PrintStream target;
        private final ByteArrayOutputStream line = new ByteArrayOutputStream(512);

        Tee(PrintStream target) {
            this.target = target;
        }

        @Override
        public synchronized void write(int b) {
            target.write(b);
            if (b == '\n') {
                emit();
            } else {
                line.write(b);
            }
        }

        @Override
        public synchronized void write(byte[] buf, int off, int len) {
            target.write(buf, off, len);
            for (int i = off; i < off + len; i++) {
                if (buf[i] == '\n') {
                    emit();
                } else {
                    line.write(buf[i]);
                }
            }
        }

        private void emit() {
            String s = line.toString(StandardCharsets.UTF_8);
            line.reset();
            if (!s.isBlank()) publish(s.endsWith("\r") ? s.substring(0, s.length() - 1) : s);
        }

        @Override
        public void flush() {
            target.flush();
        }
    }
}
