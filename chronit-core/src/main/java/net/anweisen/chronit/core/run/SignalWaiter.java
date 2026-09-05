package net.anweisen.chronit.core.run;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * A pending match for something the server has yet to send.
 *
 * <p>Always close it, or it keeps being offered every signal for the rest of the session.
 */
public final class SignalWaiter<T> implements AutoCloseable {

  private final Predicate<T> matcher;
  private final Consumer<SignalWaiter<T>> onClose;
  final CompletableFuture<T> future = new CompletableFuture<>();

  SignalWaiter(Predicate<T> matcher, Consumer<SignalWaiter<T>> onClose) {
    this.matcher = matcher;
    this.onClose = onClose;
  }

  void offer(T signal) {
    if (!future.isDone() && matcher.test(signal)) {
      future.complete(signal);
    }
  }

  /**
   * Blocks until a matching signal arrives.
   *
   * @throws TimeoutException if none arrives in time, or the wait was abandoned because the
   *                          session ended
   */
  public T await(Duration timeout) throws InterruptedException, TimeoutException {
    try {
      return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (ExecutionException e) {
      throw new TimeoutException("Wait abandoned: " + e.getCause().getMessage());
    }
  }

  @Override
  public void close() {
    onClose.accept(this);
  }
}
