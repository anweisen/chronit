package net.anweisen.chronit.core.auth;

import net.anweisen.chronit.core.config.AccountConfig;
import net.anweisen.chronit.core.config.AuthConfig;
import net.anweisen.chronit.core.config.ChronitConfig;
import net.anweisen.chronit.core.util.Durations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

/**
 * Keeps stored Microsoft sessions warm in the background, so a scheduled visit finds a token
 * already valid and a person is never sent to a browser for an account that was working fine.
 *
 * <p>The idea is borrowed from how desktop launchers behave. Prism Launcher, for instance, walks
 * its account list on a timer and renews anything inside half of its remaining life rather than
 * waiting for the game to be started. The motivation there is that a stale token turns into a
 * confusing failure at the worst moment; here that moment is unattended and at 3am, which makes the
 * argument stronger rather than weaker.
 *
 * <p>What makes it work at all is that Microsoft replaces the refresh token every time it is used
 * and starts a new ninety-day clock. Sweeping every few hours means the clock never gets anywhere
 * near running out, so the only things that can end a session are a password change or an explicit
 * revocation — neither of which any amount of refreshing could have prevented.
 */
public final class TokenRefresher implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(TokenRefresher.class);

    /**
     * Left between accounts so a deployment with a dozen of them does not arrive at Microsoft as a
     * dozen simultaneous requests, which is the shape of traffic that gets throttled.
     */
    private static final Duration SPACING = Duration.ofSeconds(5);

    private final List<AccountConfig> accounts;
    private final AccountManager manager;
    private final AuthConfig authConfig;

    /**
     * Whether an account is mid-visit. Refreshing during one is harmless — the server validated the
     * session at join time and does not look again — but there is no reason to add requests to the
     * one moment the account is doing something that matters.
     */
    private final Predicate<String> busy;

    private final ScheduledExecutorService ticker =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "chronit-token-refresh");
                thread.setDaemon(true);
                return thread;
            });

    private final AtomicBoolean started = new AtomicBoolean();

    public TokenRefresher(ChronitConfig config, AccountManager manager, Predicate<String> busy) {
        this.accounts = config.accountsOrEmpty().stream()
                .filter(account -> account.authOrDefault() == AccountConfig.AuthMode.MICROSOFT)
                .toList();
        this.manager = manager;
        this.authConfig = config.authOrDefaults();
        this.busy = busy;
    }

    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        if (accounts.isEmpty()) {
            log.debug("No Microsoft accounts configured; nothing to refresh");
            return;
        }
        if (!authConfig.isBackgroundRefreshEnabled()) {
            log.info("Background token refresh is off (auth.refreshInterval: 0). Sessions will be "
                    + "refreshed when a visit needs one, and an account left unused for ninety days "
                    + "will need logging in again.");
            return;
        }

        Duration interval = authConfig.refreshIntervalOrDefault();
        long initialDelay = authConfig.refreshOnStartOrDefault() ? 0 : interval.toSeconds();

        log.info("Refreshing {} Microsoft session(s) every {}, renewing anything due within {}",
                accounts.size(), Durations.format(interval),
                Durations.format(authConfig.sweepHorizon()));

        // Fixed delay rather than fixed rate: a sweep that runs long because Microsoft is slow
        // should push the next one out, not have it start the moment this one finishes.
        ticker.scheduleWithFixedDelay(this::sweepQuietly, initialDelay, interval.toSeconds(), TimeUnit.SECONDS);
    }

    /**
     * The scheduled entry point.
     *
     * <p>A scheduled task that throws is cancelled and never runs again, so anything the per-account
     * guard does not cover has to be caught here. {@link #sweep()} stays unguarded: a caller that
     * ran one on purpose wants to be told it failed.
     */
    private void sweepQuietly() {
        try {
            sweep();
        } catch (RuntimeException e) {
            log.error("Token refresh sweep failed: {}", e.toString(), e);
        }
    }

    /**
     * Renews every account that needs it.
     *
     * <p>Public so a startup check or a command can run one synchronously.
     */
    public void sweep() {
        Duration horizon = authConfig.sweepHorizon();
        for (int i = 0; i < accounts.size(); i++) {
            AccountConfig account = accounts.get(i);
            if (busy.test(account.id())) {
                log.debug("Account '{}' is mid-visit; leaving its session alone until the next sweep",
                        account.id());
                continue;
            }
            refreshOne(account, horizon);

            if (i < accounts.size() - 1) {
                try {
                    Thread.sleep(SPACING.toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void refreshOne(AccountConfig account, Duration horizon) {
        try {
            if (!manager.refresh(account, horizon)) {
                log.debug("Account '{}' needs no refresh yet", account.id());
            }
        } catch (AuthException e) {
            switch (e.kind()) {
                // Loud, and repeated on every sweep: this is the one message that needs a person,
                // and an unattended deployment has no other way to ask for one.
                case NEEDS_LOGIN, PERMANENT -> log.error("{}", e.getMessage());
                case TRANSIENT -> log.warn("{} — trying again in {}", e.getMessage(),
                        Durations.format(authConfig.refreshIntervalOrDefault()));
            }
        } catch (RuntimeException e) {
            // The ticker must survive anything, or sessions quietly stop being refreshed and the
            // first sign of it is a failed run months later.
            log.error("Refreshing account '{}' failed unexpectedly: {}", account.id(), e.toString(), e);
        }
    }

    @Override
    public void close() {
        ticker.shutdownNow();
    }
}
