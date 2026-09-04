package net.anweisen.chronit.web;

import net.anweisen.chronit.core.auth.AccountManager;
import net.anweisen.chronit.core.auth.AuthException;
import net.anweisen.chronit.core.auth.DeviceCodePrompt;
import net.anweisen.chronit.core.config.AccountConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Tracks in-progress device code logins started from the browser.
 *
 * <p>The flow is inherently long-running: the code is displayed, then the process polls Microsoft
 * until the person finishes, which can take minutes. That cannot happen inside a request, so each
 * login runs on its own thread and the page polls this state.
 */
final class LoginFlows {

    private static final Logger log = LoggerFactory.getLogger(LoginFlows.class);

    private final AccountManager accounts;
    private final Map<String, Flow> active = new ConcurrentHashMap<>();
    /** Told on every transition, so the sign-in page is pushed to rather than polling. */
    private final Consumer<String> onChange;

    LoginFlows(AccountManager accounts, Consumer<String> onChange) {
        this.accounts = accounts;
        this.onChange = onChange;
    }

    enum State { STARTING, WAITING, DONE, FAILED }

    record Flow(State state, DeviceCodePrompt prompt, String message, Instant startedAt) {
    }

    Optional<Flow> get(String accountId) {
        return Optional.ofNullable(active.get(accountId));
    }

    /**
     * Starts a login unless one is already running for this account.
     *
     * <p>Claimed in one step. Two clicks on the sign-in button arrive as two requests on two server
     * threads, and looking before registering let both through: the page would show one device code
     * while a second flow polled Microsoft with another, and whichever finished last would overwrite
     * the session the other had just stored.
     */
    void start(AccountConfig account) {
        Flow starting = new Flow(State.STARTING, null, "Requesting a code...", Instant.now());
        Flow claimed = active.compute(account.id(), (id, existing) ->
                existing != null && (existing.state() == State.STARTING || existing.state() == State.WAITING)
                        ? existing
                        : starting);
        // Identity, not equality: only the caller whose own object went into the map starts a
        // thread. Two callers arriving together both see STARTING, and comparing by value cannot
        // tell which of them put it there.
        if (claimed != starting) {
            return;
        }
        announce(account.id());

        Thread worker = new Thread(() -> {
            try {
                accounts.login(account, prompt -> set(account.id(),
                        new Flow(State.WAITING, prompt, "Waiting for authorisation", Instant.now())));
                set(account.id(), new Flow(State.DONE, null, "Logged in", Instant.now()));
            } catch (AuthException e) {
                log.warn("Browser-initiated login for '{}' failed: {}", account.id(), e.getMessage());
                set(account.id(), new Flow(State.FAILED, null, e.getMessage(), Instant.now()));
            }
        }, "chronit-login-" + account.id());
        worker.setDaemon(true);
        worker.start();
    }

    void clear(String accountId) {
        active.remove(accountId);
    }

    private void set(String accountId, Flow flow) {
        active.put(accountId, flow);
        announce(accountId);
    }

    private void announce(String accountId) {
        if (onChange != null) {
            onChange.accept(accountId);
        }
    }
}
