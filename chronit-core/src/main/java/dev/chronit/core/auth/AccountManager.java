package dev.chronit.core.auth;

import com.google.gson.JsonObject;
import dev.chronit.core.config.AccountConfig;
import dev.chronit.core.config.ChronitConfig;
import dev.chronit.core.driver.AuthContext;
import dev.chronit.core.driver.PlayerCertificates;
import dev.chronit.core.util.Redactor;
import net.lenni0451.commons.httpclient.HttpClient;
import net.raphimc.minecraftauth.MinecraftAuth;
import net.raphimc.minecraftauth.java.JavaAuthManager;
import net.raphimc.minecraftauth.java.model.MinecraftPlayerCertificates;
import net.raphimc.minecraftauth.java.model.MinecraftProfile;
import net.raphimc.minecraftauth.java.model.MinecraftToken;
import net.raphimc.minecraftauth.msa.data.MsaConstants;
import net.raphimc.minecraftauth.msa.model.MsaApplicationConfig;
import net.raphimc.minecraftauth.msa.model.MsaDeviceCode;
import net.raphimc.minecraftauth.msa.service.impl.DeviceCodeMsaAuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * Resolves configured accounts into identities the driver can join with.
 *
 * <p>Microsoft sessions are cached on disk and refreshed automatically. A Minecraft access token
 * lasts about a day and the underlying refresh token about ninety, so an unattended deployment
 * needs an interactive login roughly once a quarter — which is why a failed refresh is reported
 * loudly rather than retried.
 */
public final class AccountManager {

    private static final Logger log = LoggerFactory.getLogger(AccountManager.class);

    private final Path stateDir;
    private final TokenStore tokenStore;
    private final HttpClient httpClient;

    /** Cached per account so repeated visits in one run reuse the same refreshed session. */
    private final Map<String, JavaAuthManager> sessions = new ConcurrentHashMap<>();

    public AccountManager(ChronitConfig config) {
        this(config.stateDirOrDefault(), new TokenStore());
    }

    public AccountManager(Path stateDir, TokenStore tokenStore) {
        this.stateDir = stateDir;
        this.tokenStore = tokenStore;
        this.httpClient = MinecraftAuth.createHttpClient();
    }

    /**
     * Produces a ready-to-use identity, refreshing tokens if needed.
     *
     * @throws AuthException with {@link AuthException#needsLogin()} set when only an interactive
     *                       login can fix it
     */
    public AuthContext resolve(AccountConfig account) throws AuthException {
        if (account.authOrDefault() == AccountConfig.AuthMode.OFFLINE) {
            return AuthContext.offline(account.username());
        }

        JavaAuthManager manager = loadSession(account)
                .orElseThrow(() -> new AuthException(
                        "Account '" + account.id() + "' has no stored session. "
                                + "Run: chronit login " + account.id(), true));

        try {
            MinecraftToken token = manager.getMinecraftToken().getUpToDate();
            MinecraftProfile profile = manager.getMinecraftProfile().getUpToDate();
            Redactor.register(token.getToken());

            PlayerCertificates certificates = fetchCertificates(manager, account.id());

            persist(account, manager);
            log.debug("Resolved account '{}' as {} ({})", account.id(), profile.getName(), profile.getId());

            return new AuthContext(profile.getName(), profile.getId(), true, token.getToken(), certificates);
        } catch (IOException e) {
            // A refresh token that Microsoft no longer accepts is the common case here, and it is
            // not something a retry will fix.
            throw new AuthException("Could not refresh the session for account '" + account.id()
                    + "': " + e.getMessage() + ". Run: chronit login " + account.id(), e, true);
        }
    }

    /**
     * Fetches the chat signing certificate.
     *
     * <p>Non-fatal: without it plain chat is rejected by servers that enforce secure profiles, but
     * commands are unaffected, so a failure here must not stop a visit whose actions are all
     * commands.
     */
    private PlayerCertificates fetchCertificates(JavaAuthManager manager, String accountId) {
        try {
            MinecraftPlayerCertificates certificates = manager.getMinecraftPlayerCertificates().getUpToDate();
            KeyPair keyPair = certificates.getKeyPair();
            return new PlayerCertificates(
                    keyPair.getPublic(),
                    keyPair.getPrivate(),
                    certificates.getPublicKeySignature(),
                    Instant.ofEpochMilli(certificates.getExpireTimeMs()));
        } catch (IOException | RuntimeException e) {
            log.warn("Could not fetch chat certificates for account '{}' ({}); plain chat will be "
                    + "unsigned. Commands are unaffected.", accountId, e.toString());
            return null;
        }
    }

    /**
     * Runs an interactive device code login and stores the resulting session.
     *
     * <p>Blocks until the user completes it or the code expires.
     *
     * @param onPrompt receives the code and link to show the user
     */
    public AuthContext login(AccountConfig account, Consumer<DeviceCodePrompt> onPrompt) throws AuthException {
        if (account.authOrDefault() == AccountConfig.AuthMode.OFFLINE) {
            throw new AuthException("Account '" + account.id() + "' is offline mode; nothing to log in to", false);
        }

        Consumer<MsaDeviceCode> callback = code -> onPrompt.accept(new DeviceCodePrompt(
                code.getUserCode(),
                code.getVerificationUri(),
                code.getDirectVerificationUri(),
                Instant.ofEpochMilli(code.getExpireTimeMs())));

        try {
            JavaAuthManager manager = JavaAuthManager.create(httpClient)
                    .msaApplicationConfig(applicationConfig(account))
                    .login(DeviceCodeMsaAuthService::new, callback);

            sessions.put(account.id(), manager);
            persist(account, manager);

            MinecraftProfile profile = manager.getMinecraftProfile().getUpToDate();
            log.info("Account '{}' logged in as {}", account.id(), profile.getName());
            return resolve(account);
        } catch (TimeoutException e) {
            throw new AuthException("Login for account '" + account.id()
                    + "' timed out — the code expired before it was entered", e, true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AuthException("Login for account '" + account.id() + "' was interrupted", e, true);
        } catch (IOException e) {
            throw new AuthException("Login for account '" + account.id() + "' failed: " + e.getMessage(), e, true);
        }
    }

    /** Reports the state of an account without contacting Microsoft. */
    public AccountStatus status(AccountConfig account) {
        if (account.authOrDefault() == AccountConfig.AuthMode.OFFLINE) {
            return new AccountStatus(account.id(), AccountStatus.State.OFFLINE,
                    account.username(), null, "offline mode");
        }

        Optional<JavaAuthManager> session = loadSession(account);
        if (session.isEmpty()) {
            return new AccountStatus(account.id(), AccountStatus.State.NEEDS_LOGIN, null, null,
                    "no stored session — run: chronit login " + account.id());
        }

        JavaAuthManager manager = session.get();
        String username = manager.getMinecraftProfile().hasValue()
                ? manager.getMinecraftProfile().getCached().getName() : null;
        Instant expiry = manager.getMinecraftToken().hasValue()
                ? Instant.ofEpochMilli(manager.getMinecraftToken().getCached().getExpireTimeMs()) : null;

        boolean refreshExpired = manager.getMsaToken().isExpired();
        if (refreshExpired) {
            return new AccountStatus(account.id(), AccountStatus.State.NEEDS_LOGIN, username, expiry,
                    "the Microsoft refresh token has expired — run: chronit login " + account.id());
        }
        return new AccountStatus(account.id(), AccountStatus.State.READY, username, expiry,
                manager.getMinecraftToken().isExpired() ? "token will be refreshed on next use" : "ready");
    }

    private Optional<JavaAuthManager> loadSession(AccountConfig account) {
        JavaAuthManager cached = sessions.get(account.id());
        if (cached != null) {
            return Optional.of(cached);
        }
        return tokenStore.read(tokenFile(account)).map(json -> {
            JavaAuthManager manager = JavaAuthManager.fromJson(httpClient, json);
            sessions.put(account.id(), manager);
            return manager;
        });
    }

    private void persist(AccountConfig account, JavaAuthManager manager) {
        try {
            JsonObject json = JavaAuthManager.toJson(manager);
            tokenStore.write(tokenFile(account), json);
        } catch (IOException e) {
            // Not fatal for the run in progress: the session is live in memory. It does mean the
            // next process start will need another login, so it is worth a warning.
            log.warn("Could not save the session for account '{}': {}", account.id(), e.toString());
        }
    }

    public Path tokenFile(AccountConfig account) {
        return account.tokenStore() != null
                ? account.tokenStore()
                : stateDir.resolve("tokens").resolve(account.id() + ".json");
    }

    private MsaApplicationConfig applicationConfig(AccountConfig account) {
        if (account.clientId() == null || account.clientId().isBlank()) {
            // The library's built-in official client id. Registering your own Azure application and
            // setting clientId is the better practice for anything long-lived.
            return new MsaApplicationConfig(MsaConstants.JAVA_TITLE_ID, MsaConstants.SCOPE_TITLE_AUTH);
        }
        return new MsaApplicationConfig(account.clientId(), MsaConstants.SCOPE_OFFLINE_ACCESS);
    }
}
