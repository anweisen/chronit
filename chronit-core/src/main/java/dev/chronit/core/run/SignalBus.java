package dev.chronit.core.run;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Distributes something the server sent to whoever is waiting for it.
 *
 * <p>The ordering rule matters and is the same for every signal type: a waiter is registered
 * <em>before</em> the action that provokes the response. Servers frequently answer within a few
 * milliseconds — an inventory opens practically instantly — so registering afterwards would lose
 * the race and wait out the full timeout for something that already happened.
 */
public class SignalBus<T> {

    private final List<SignalWaiter<T>> waiters = new CopyOnWriteArrayList<>();
    private final List<Consumer<T>> observers = new CopyOnWriteArrayList<>();

    /** Called from network threads; does no blocking work. */
    public void publish(T signal) {
        for (SignalWaiter<T> waiter : waiters) {
            waiter.offer(signal);
        }
        for (Consumer<T> observer : observers) {
            observer.accept(signal);
        }
    }

    /** Starts listening. Register this before triggering whatever produces the signal. */
    public SignalWaiter<T> expect(Predicate<T> matcher) {
        SignalWaiter<T> waiter = new SignalWaiter<>(matcher, waiters::remove);
        waiters.add(waiter);
        return waiter;
    }

    /** Subscribes to every signal, for logging and the web interface. */
    public AutoCloseable observe(Consumer<T> observer) {
        observers.add(observer);
        return () -> observers.remove(observer);
    }

    /** Fails every outstanding wait, so a lost session does not leave a sequence hanging. */
    public void abort(String reason) {
        for (SignalWaiter<T> waiter : waiters) {
            waiter.future.completeExceptionally(new IllegalStateException(reason));
        }
        waiters.clear();
    }
}
