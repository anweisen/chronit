package dev.chronit.core.run;

import dev.chronit.core.driver.ChatLine;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Distributes incoming chat to whoever is waiting for it.
 *
 * <p>The ordering matters: a waiter is registered <em>before</em> the command that provokes the
 * reply is sent. Servers frequently answer within a few milliseconds, so registering afterwards
 * would lose the race and wait out the full timeout for a message that already arrived.
 */
public final class ChatBus {

    private final List<Waiter> waiters = new CopyOnWriteArrayList<>();
    private final List<Consumer<ChatLine>> observers = new CopyOnWriteArrayList<>();

    /** Called from the network thread; does no blocking work. */
    public void publish(ChatLine line) {
        for (Waiter waiter : waiters) {
            waiter.offer(line);
        }
        for (Consumer<ChatLine> observer : observers) {
            observer.accept(line);
        }
    }

    /**
     * Starts listening for a message matching {@code pattern}.
     *
     * <p>Register this before triggering whatever produces the reply, then call
     * {@link Waiter#await}.
     */
    public Waiter expect(Pattern pattern) {
        Waiter waiter = new Waiter(pattern);
        waiters.add(waiter);
        return waiter;
    }

    /** Subscribes to every line, for logging and the web interface. */
    public AutoCloseable observe(Consumer<ChatLine> observer) {
        observers.add(observer);
        return () -> observers.remove(observer);
    }

    /** Fails every outstanding wait, so a lost session does not leave a sequence hanging. */
    public void abort(String reason) {
        for (Waiter waiter : waiters) {
            waiter.future.completeExceptionally(new IllegalStateException(reason));
        }
        waiters.clear();
    }

    /** A pending match. Always close it, or it keeps receiving every line. */
    public final class Waiter implements AutoCloseable {

        private final Pattern pattern;
        private final CompletableFuture<ChatLine> future = new CompletableFuture<>();

        private Waiter(Pattern pattern) {
            this.pattern = pattern;
        }

        private void offer(ChatLine line) {
            if (!future.isDone() && pattern.matcher(line.plainText()).find()) {
                future.complete(line);
            }
        }

        /**
         * Blocks until a matching line arrives.
         *
         * @throws TimeoutException if none arrives in time
         */
        public ChatLine await(Duration timeout) throws InterruptedException, TimeoutException {
            try {
                return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (ExecutionException e) {
                throw new TimeoutException("Wait abandoned: " + e.getCause().getMessage());
            }
        }

        @Override
        public void close() {
            waiters.remove(this);
        }
    }
}
