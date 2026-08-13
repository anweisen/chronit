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

    LoginFlows(AccountManager accounts) {
        this.accounts = accounts;
    }

    enum State { STARTING, WAITING, DONE, FAILED }

    record Flow(State state, DeviceCodePrompt prompt, String message, Instant startedAt) {
    }

    Optional<Flow> get(String accountId) {
        return Optional.ofNullable(active.get(accountId));
    }

    /** Starts a login unless one is already running for this account. */
    void start(AccountConfig account) {
        Flow existing = active.get(account.id());
        if (existing != null && (existing.state() == State.STARTING || existing.state() == State.WAITING)) {
            return;
        }
        active.put(account.id(), new Flow(State.STARTING, null, "Requesting a code...", Instant.now()));

        Thread worker = new Thread(() -> {
            try {
                accounts.login(account, prompt -> active.put(account.id(),
                        new Flow(State.WAITING, prompt, "Waiting for authorisation", Instant.now())));
                active.put(account.id(), new Flow(State.DONE, null, "Logged in", Instant.now()));
            } catch (AuthException e) {
                log.warn("Browser-initiated login for '{}' failed: {}", account.id(), e.getMessage());
                active.put(account.id(), new Flow(State.FAILED, null, e.getMessage(), Instant.now()));
            }
        }, "chronit-login-" + account.id());
        worker.setDaemon(true);
        worker.start();
    }

    void clear(String accountId) {
        active.remove(accountId);
    }
}
