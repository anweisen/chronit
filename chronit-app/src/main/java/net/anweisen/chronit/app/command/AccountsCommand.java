package net.anweisen.chronit.app.command;

import net.anweisen.chronit.core.auth.AccountManager;
import net.anweisen.chronit.core.auth.AccountStatus;
import net.anweisen.chronit.core.auth.AuthException;
import net.anweisen.chronit.core.config.AccountConfig;
import net.anweisen.chronit.core.config.ChronitConfig;
import picocli.CommandLine;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Callable;

/** Shows which accounts are ready and which need a login. */
@CommandLine.Command(
    name = "accounts",
    description = "List configured accounts and whether they can be used right now.")
public final class AccountsCommand implements Callable<Integer> {

  @CommandLine.Mixin
  ConfigMixin configMixin;

  @CommandLine.Option(
      names = "--refresh",
      description = "Renew every Microsoft session before reporting. Without this the report "
          + "is read from disk and no request is made, so a revoked session still looks "
          + "fine until something tries to use it.")
  boolean refresh;

  @Override
  public Integer call() {
    ChronitConfig config = configMixin.load();
    AccountManager accounts = new AccountManager(config);

    if (refresh) {
      for (AccountConfig account : config.accountsOrEmpty()) {
        if (account.authOrDefault() != AccountConfig.AuthMode.MICROSOFT) {
          continue;
        }
        try {
          // Forced rather than "if due": a check that makes no request proves nothing.
          accounts.refresh(account, AccountManager.FORCE);
        } catch (AuthException e) {
          // Recorded against the account, so it comes back out in the table below.
          System.err.println(e.getMessage());
        }
      }
      System.out.println();
    }

    System.out.printf("%-14s %-12s %-18s %-26s %-14s %s%n",
        "ID", "STATE", "USERNAME", "TOKEN EXPIRES", "SESSION", "DETAIL");

    boolean allUsable = true;
    for (AccountConfig account : config.accountsOrEmpty()) {
      AccountStatus status = accounts.status(account);
      allUsable &= status.isUsable();
      System.out.printf("%-14s %-12s %-18s %-26s %-14s %s%n",
          status.id(),
          status.state(),
          status.username() != null ? status.username() : "-",
          status.tokenExpiry() != null ? status.tokenExpiry().toString() : "-",
          sessionColumn(status),
          status.detail());
    }

    // A non-zero exit lets a monitoring check notice that a login is due before a scheduled
    // run fails because of it.
    return allUsable ? 0 : 1;
  }

  /**
   * How long the account would survive being left alone.
   *
   * <p>The number that decides whether anyone has to sit down at a browser, which is why it gets
   * a column of its own rather than being buried in the detail text. With the daemon running it
   * should never fall: every sweep pushes it back out to ninety days.
   */
  private static String sessionColumn(AccountStatus status) {
    if (status.state() == AccountStatus.State.OFFLINE) {
      return "-";
    }
    if (status.sessionExpiry() == null) {
      return "unknown";
    }
    long days = Duration.between(Instant.now(), status.sessionExpiry()).toDays();
    return days < 0 ? "lapsed" : days + "d left";
  }
}
