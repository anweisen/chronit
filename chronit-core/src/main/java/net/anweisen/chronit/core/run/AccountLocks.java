package net.anweisen.chronit.core.run;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Serialises visits per account.
 *
 * <p>A Minecraft account can only be online in one place: logging in a second time invalidates the
 * first session, and the earlier connection is dropped with "you logged in from another location".
 * Two jobs that happen to share an account would otherwise kick each other and both look flaky for
 * no obvious reason.
 */
public final class AccountLocks {

  private static final Logger log = LoggerFactory.getLogger(AccountLocks.class);

  private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

  /** Blocks until the account is free. Close the lease to release it. */
  public Lease acquire(String accountId) throws InterruptedException {
    ReentrantLock lock = locks.computeIfAbsent(accountId, ignored -> new ReentrantLock(true));
    if (!lock.tryLock()) {
      log.info("Waiting for account '{}' to finish its current visit", accountId);
      lock.lockInterruptibly();
    }
    return () -> lock.unlock();
  }

  public boolean isBusy(String accountId) {
    ReentrantLock lock = locks.get(accountId);
    return lock != null && lock.isLocked();
  }

  /** Released with try-with-resources; never throws. */
  @FunctionalInterface
  public interface Lease extends AutoCloseable {
    @Override
    void close();
  }
}
