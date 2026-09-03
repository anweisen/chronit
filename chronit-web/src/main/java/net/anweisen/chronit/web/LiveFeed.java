package net.anweisen.chronit.web;

import com.sun.net.httpserver.HttpExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The live channel the dashboard listens on.
 *
 * <p>This replaces the six-second poll the page used to run. Server-sent events rather than
 * WebSockets, and that is a considered choice rather than a shortcut: the JDK's own HTTP server —
 * which this interface is built on precisely so the image does not carry a servlet container —
 * offers no way to hand a request's socket over for a protocol upgrade, so a WebSocket would mean
 * either a second listener on another port with its own authentication, or an embedded server and
 * the several megabytes that come with it. Meanwhile every byte here travels one way, from the
 * daemon to the page. A stream of events is exactly the shape of the problem.
 *
 * <p>What it buys beyond that: the browser's own {@code EventSource} reconnects on its own, the
 * session cookie is sent with the stream like any other same-origin request so authentication is
 * the one that already exists, and it is ordinary HTTP — a reverse proxy, {@code curl} and the
 * network tab all understand it.
 *
 * <p>Every event carries absolute state rather than a delta, which is what makes the coalescing
 * below safe: a subscriber that cannot keep up is served the newest value of each event and never
 * a backlog, and a subscriber that reconnects needs no replay.
 */
final class LiveFeed implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LiveFeed.class);

    /**
     * A comment line on an idle stream. Without it an intermediary with an idle timeout — nginx
     * defaults to a minute — closes a stream that is merely quiet, and the page spends its life
     * reconnecting.
     */
    private static final Duration HEARTBEAT = Duration.ofSeconds(20);

    /**
     * Each stream holds a thread for its lifetime, so this is a real limit rather than a
     * formality. A dashboard has a handful of viewers; anything approaching this is a client that
     * has stopped closing its connections.
     */
    private static final int MAX_SUBSCRIBERS = 24;

    private final List<Subscriber> subscribers = new CopyOnWriteArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    int subscriberCount() {
        return subscribers.size();
    }

    /**
     * Publishes to everyone listening.
     *
     * @param event the event name, which is also the coalescing key: a newer value replaces an
     *              older one that has not been written yet
     */
    void publish(String event, String data) {
        for (Subscriber subscriber : subscribers) {
            subscriber.offer(event, data);
        }
    }

    /**
     * Takes over the exchange and streams until the client goes away.
     *
     * <p>Blocks the calling thread for the life of the connection, which is why the server is
     * given an expanding pool rather than a fixed one.
     */
    void serve(HttpExchange exchange, Map<String, String> initial) throws IOException {
        if (closed.get() || subscribers.size() >= MAX_SUBSCRIBERS) {
            exchange.sendResponseHeaders(503, -1);
            return;
        }

        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("Connection", "keep-alive");
        // nginx buffers proxied responses by default, which holds every event until the buffer
        // fills — for a stream of a few hundred bytes a minute, that is indistinguishable from a
        // dead connection.
        exchange.getResponseHeaders().set("X-Accel-Buffering", "no");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        // Zero means chunked, which is what lets the body stay open.
        exchange.sendResponseHeaders(200, 0);

        Subscriber subscriber = new Subscriber();
        subscribers.add(subscriber);
        try (OutputStream out = exchange.getResponseBody()) {
            // Tells the browser how long to wait before reconnecting after a drop. The default is
            // three seconds; two is a better fit for something being watched during a run.
            write(out, "retry: 2000\n\n");
            for (Map.Entry<String, String> entry : initial.entrySet()) {
                writeEvent(out, entry.getKey(), entry.getValue());
            }
            subscriber.pump(out);
        } catch (IOException e) {
            // The ordinary way a stream ends: the tab was closed, or the network went away.
            log.debug("Live stream ended: {}", e.toString());
        } finally {
            subscribers.remove(subscriber);
        }
    }

    @Override
    public void close() {
        closed.set(true);
        subscribers.forEach(Subscriber::stop);
        subscribers.clear();
    }

    private static void writeEvent(OutputStream out, String event, String data) throws IOException {
        StringBuilder frame = new StringBuilder(data.length() + 32);
        frame.append("event: ").append(event).append('\n');
        // The wire format is line-oriented, so a payload containing a newline has to be sent as
        // several data lines; the browser rejoins them.
        for (String line : data.split("\n", -1)) {
            frame.append("data: ").append(line).append('\n');
        }
        frame.append('\n');
        write(out, frame.toString());
    }

    private static void write(OutputStream out, String text) throws IOException {
        out.write(text.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /**
     * One connected browser.
     *
     * <p>Holds the latest value of each event rather than a queue of them. A page that was in a
     * background tab, or on a slow link, then gets one current picture on its next write instead
     * of replaying a minute of history it no longer cares about.
     */
    private static final class Subscriber {

        private final Map<String, String> pending = new ConcurrentHashMap<>();
        private final ReentrantLock lock = new ReentrantLock();
        private final Condition arrived = lock.newCondition();
        private volatile boolean running = true;

        void offer(String event, String data) {
            pending.put(event, data);
            lock.lock();
            try {
                arrived.signalAll();
            } finally {
                lock.unlock();
            }
        }

        void stop() {
            running = false;
            lock.lock();
            try {
                arrived.signalAll();
            } finally {
                lock.unlock();
            }
        }

        void pump(OutputStream out) throws IOException {
            while (running) {
                if (pending.isEmpty()) {
                    lock.lock();
                    try {
                        if (pending.isEmpty() && running) {
                            arrived.await(HEARTBEAT.toMillis(), TimeUnit.MILLISECONDS);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    } finally {
                        lock.unlock();
                    }
                }
                if (!running) {
                    return;
                }
                if (pending.isEmpty()) {
                    // Nothing happened; say so, which is also how a dead connection is discovered.
                    write(out, ": keepalive\n\n");
                    continue;
                }
                for (String event : List.copyOf(pending.keySet())) {
                    String data = pending.remove(event);
                    if (data != null) {
                        writeEvent(out, event, data);
                    }
                }
            }
        }
    }
}
