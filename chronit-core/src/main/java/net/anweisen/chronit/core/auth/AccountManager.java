package net.anweisen.chronit.core.auth;

import com.google.gson.JsonObject;
import net.anweisen.chronit.core.config.AccountConfig;
import net.anweisen.chronit.core.config.AuthConfig;
import net.anweisen.chronit.core.config.ChronitConfig;
import net.anweisen.chronit.core.driver.AuthContext;
import net.anweisen.chronit.core.driver.PlayerCertificates;
import net.anweisen.chronit.core.util.Redactor;
import net.lenni0451.commons.httpclient.HttpClient;
import net.raphimc.minecraftauth.MinecraftAuth;
import net.raphimc.minecraftauth.java.JavaAuthManager;
import net.raphimc.minecraftauth.java.model.MinecraftPlayerCertificates;
import net.raphimc.minecraftauth.java.model.MinecraftProfile;
import net.raphimc.minecraftauth.java.model.MinecraftToken;
import net.raphimc.minecraftauth.msa.data.MsaConstants;
import net.raphimc.minecraftauth.msa.model.MsaApplicationConfig;
import net.raphimc.minecraftauth.msa.model.MsaDeviceCode;
import net.raphimc.minecraftauth.msa.model.MsaToken;
import net.raphimc.minecraftauth.msa.service.impl.DeviceCodeMsaAuthService;
import net.raphimc.minecraftauth.util.Expirable;
import net.raphimc.minecraftauth.util.holder.Holder;
import net.raphimc.minecraftauth.util.holder.listener.BasicChangeListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * Resolves configured accounts into identities the driver can join with, and keeps the Microsoft
 * sessions behind them alive.
 *
 * <p>Three token lifetimes matter, and conflating them is the usual source of surprise logins. The
 * Minecraft access token the server checks lasts about a day. The Microsoft access token that mints
 * it lasts an hour. The refresh token behind <em>both</em> lasts ninety days — but that ninety days
 * restarts every time it is used, because Microsoft issues a replacement on each refresh. So the
 * ninety days is a limit on being left alone, not a countdown to an unavoidable login, and a
 * deployment that refreshes on a schedule never reaches it. That is what {@link TokenRefresher}
 * does; this class does the work when asked.
 *
 * <p>Refreshing is proactive rather than reactive: a token is renewed once it is within a margin of
 * expiring, not once it has already expired. A token with a minute left on it is no use — the
 * server revalidates it against Mojang during the join, and a join is not instant.
 */
public final class AccountManager {

  private static final Logger log = LoggerFactory.getLogger(AccountManager.class);

  /**
   * A horizon long enough that every token counts as due, for an explicit "check this account
   * now" that has to make a real request to prove anything.
   */
  public static final Duration FORCE = Duration.ofDays(365);

  /**
   * How long to keep polling during an interactive login.
   *
   * <p>The library defaults to five minutes, which is shorter than the fifteen the device code
   * itself is good for — so the prompt would tell someone their code was valid until 20:15 and
   * then stop listening at 20:05. Polling stops on its own when the code expires, so this only
   * has to be long enough not to be the thing that gives up first.
   */
  private static final Duration LOGIN_TIMEOUT = Duration.ofMinutes(20);

  private final Path stateDir;
  private final TokenStore tokenStore;
  private final AuthConfig authConfig;
  private final HttpClient httpClient;

  /** Cached per account so repeated visits in one run reuse the same refreshed session. */
  private final Map<String, Session> sessions = new ConcurrentHashMap<>();

  /**
   * Held only while a session is being read off disk. Loading twice concurrently would leave two
   * managers for one account, each refreshing and rotating the refresh token independently —
   * and each rotation would leave the other holding a token Microsoft has already replaced.
   */
  private final Object loadLock = new Object();

  public AccountManager(ChronitConfig config) {
    this(config.stateDirOrDefault(), new TokenStore(), config.authOrDefaults());
  }

  public AccountManager(Path stateDir, TokenStore tokenStore) {
    this(stateDir, tokenStore, AuthConfig.DEFAULTS);
  }

  public AccountManager(Path stateDir, TokenStore tokenStore, AuthConfig authConfig) {
    this.stateDir = stateDir;
    this.tokenStore = tokenStore;
    this.authConfig = authConfig;
    this.httpClient = createHttpClient();
  }

  /**
   * Produces a ready-to-use identity, refreshing anything close to expiring first.
   *
   * @throws AuthException whose {@link AuthException#kind()} says whether this is worth retrying
   */
  public AuthContext resolve(AccountConfig account) throws AuthException {
    if (account.authOrDefault() == AccountConfig.AuthMode.OFFLINE) {
      return AuthContext.offline(account.username());
    }

    Session session = session(account);
    session.lock.lock();
    try {
      refreshWhatIsDue(session, authConfig.refreshMarginOrDefault());

      MinecraftToken token = session.manager.getMinecraftToken().getUpToDate();
      MinecraftProfile profile = session.manager.getMinecraftProfile().getUpToDate();
      registerSecrets(session);

      PlayerCertificates certificates = certificates(session);
      session.saveQuietly();
      session.clearFailure();

      log.debug("Resolved account '{}' as {} ({}); token valid until {}",
          account.id(), profile.getName(), profile.getId(),
          Instant.ofEpochMilli(token.getExpireTimeMs()));
      return new AuthContext(profile.getName(), profile.getId(), true, token.getToken(), certificates);
    } catch (IOException | IllegalStateException | TokenStoreException e) {
      throw noteFailure(session, account, e);
    } finally {
      session.lock.unlock();
    }
  }

  /**
   * Renews anything that would expire within {@code horizon}.
   *
   * <p>Pass {@link #FORCE} to renew regardless, which is the only way to actually find out
   * whether a session still works.
   *
   * @return true when something was renewed, so a caller can say so rather than logging noise
   */
  public boolean refresh(AccountConfig account, Duration horizon) throws AuthException {
    if (account.authOrDefault() == AccountConfig.AuthMode.OFFLINE) {
      return false;
    }
    Session session = session(account);
    session.lock.lock();
    try {
      boolean refreshed = refreshWhatIsDue(session, horizon);
      session.clearFailure();
      return refreshed;
    } catch (IOException | IllegalStateException | TokenStoreException e) {
      throw noteFailure(session, account, e);
    } finally {
      session.lock.unlock();
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
      throw new AuthException("Account '" + account.id() + "' is offline mode; nothing to log in to",
          AuthException.Kind.PERMANENT);
    }

    Consumer<MsaDeviceCode> callback = code -> onPrompt.accept(new DeviceCodePrompt(
        code.getUserCode(),
        code.getVerificationUri(),
        code.getDirectVerificationUri(),
        Instant.ofEpochMilli(code.getExpireTimeMs())));

    try {
      JavaAuthManager manager = JavaAuthManager.create(httpClient)
          .msaApplicationConfig(applicationConfig(account))
          .login((client, appConfig, prompt) -> new DeviceCodeMsaAuthService(
              client, appConfig, prompt, (int) LOGIN_TIMEOUT.toMillis()), callback);

      Session session = new Session(account.id(), tokenFile(account), manager, SessionMeta.loggedInNow());
      session.listen();
      session.saveQuietly();
      // Replaces whatever was cached: the old session's refresh token is now redundant.
      sessions.put(account.id(), session);

      AuthContext identity = resolve(account);
      log.info("Account '{}' logged in as {}", account.id(), identity.username());
      return identity;
    } catch (TimeoutException e) {
      throw new AuthException("Login for account '" + account.id()
          + "' timed out — the code expired before it was entered", e,
          AuthException.Kind.NEEDS_LOGIN);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AuthException("Login for account '" + account.id() + "' was interrupted", e,
          AuthException.Kind.TRANSIENT);
    } catch (IOException e) {
      throw new AuthException("Login for account '" + account.id() + "' failed: "
          + AuthFailures.describe(e), e, AuthFailures.classify(e));
    }
  }

  /** Reports the state of an account without contacting Microsoft. */
  public AccountStatus status(AccountConfig account) {
    if (account.authOrDefault() == AccountConfig.AuthMode.OFFLINE) {
      return AccountStatus.offline(account.id(), account.username());
    }

    Session session;
    try {
      session = session(account);
    } catch (AuthException e) {
      return e.needsLogin()
          ? AccountStatus.needsLogin(account.id(), e.getMessage())
          : AccountStatus.error(account.id(), e.getMessage());
    }

    JavaAuthManager manager = session.manager;
    String username = manager.getMinecraftProfile().hasValue()
        ? manager.getMinecraftProfile().getCached().getName() : null;
    Instant tokenExpiry = manager.getMinecraftToken().hasValue()
        ? Instant.ofEpochMilli(manager.getMinecraftToken().getCached().getExpireTimeMs()) : null;
    SessionMeta meta = session.meta;

    MsaToken msaToken = manager.getMsaToken().getCached();
    if (msaToken == null || msaToken.getRefreshToken() == null) {
      // Nothing to renew with. Only ever happens for a session stored without offline access.
      return describe(account, AccountStatus.State.NEEDS_LOGIN, username, tokenExpiry, meta,
          "the stored session has no refresh token — run: chronit login " + account.id());
    }
    if (meta.hasLapsed()) {
      return describe(account, AccountStatus.State.NEEDS_LOGIN, username, tokenExpiry, meta,
          "not refreshed since " + meta.lastRefreshAt() + ", which is past the ninety-day "
              + "window — run: chronit login " + account.id());
    }

    Session.Failure failure = session.failure;
    if (failure != null) {
      AccountStatus.State state = switch (failure.kind()) {
        case NEEDS_LOGIN -> AccountStatus.State.NEEDS_LOGIN;
        case PERMANENT -> AccountStatus.State.ERROR;
        // Microsoft being unreachable says nothing about the session, so an account whose
        // token is still good stays ready. One whose token has already run out is a
        // different matter: it cannot be used until a refresh gets through.
        case TRANSIENT -> manager.getMinecraftToken().isExpired()
            ? AccountStatus.State.ERROR
            : AccountStatus.State.READY;
      };
      return describe(account, state, username, tokenExpiry, meta,
          "last refresh failed at " + failure.at() + ": " + failure.message());
    }

    String detail = meta.lastRefreshAt() == null
        ? "ready; the session age is unknown until the next refresh"
        : manager.getMinecraftToken().isExpired()
            ? "ready; the token will be refreshed on next use"
            : "ready";
    return describe(account, AccountStatus.State.READY, username, tokenExpiry, meta, detail);
  }

  public Path tokenFile(AccountConfig account) {
    return account.tokenStore() != null
        ? account.tokenStore()
        : stateDir.resolve("tokens").resolve(account.id() + ".json");
  }

  public AuthConfig authConfig() {
    return authConfig;
  }

  // ---------------------------------------------------------------- refreshing

  /**
   * Renews the tokens that would expire within {@code horizon}, in dependency order.
   *
   * <p>Only two are driven from here. The Xbox tokens in between are refreshed by the library as
   * a side effect of minting a Minecraft token, and refreshing them on their own would be a
   * request spent on something no one is about to use.
   */
  private boolean refreshWhatIsDue(Session session, Duration horizon) throws IOException {
    JavaAuthManager manager = session.manager;
    boolean refreshed = false;

    if (isDue(manager.getMsaToken(), horizon)) {
      refreshMsaToken(session);
      refreshed = true;
    }
    if (isDue(manager.getMinecraftToken(), horizon)) {
      manager.getMinecraftToken().refresh();
      // The profile carries no expiry, so this is the only moment it is ever re-read. Without
      // it a rename would stay invisible for the life of the session.
      manager.getMinecraftProfile().refresh();
      refreshed = true;
    }

    if (refreshed) {
      registerSecrets(session);
      session.save();
      log.info("Refreshed the Microsoft session for account '{}'; Minecraft token now valid until {}",
          session.accountId,
          Instant.ofEpochMilli(manager.getMinecraftToken().getCached().getExpireTimeMs()));
    }
    return refreshed;
  }

  /**
   * Exchanges the refresh token for a new pair.
   *
   * <p>Microsoft normally returns a replacement refresh token and leaves the old one working. If
   * a response ever omits one, carrying the previous token forward is the difference between a
   * session that keeps going and one that can never be renewed again — the library would store
   * the null and every later refresh would fail outright.
   */
  private void refreshMsaToken(Session session) throws IOException {
    Holder<MsaToken> holder = session.manager.getMsaToken();
    MsaToken before = holder.getCached();
    String previousRefreshToken = before != null ? before.getRefreshToken() : null;

    MsaToken after = holder.refresh();

    if (after.getRefreshToken() == null && previousRefreshToken != null) {
      log.warn("Microsoft returned no replacement refresh token for account '{}'; "
          + "keeping the previous one", session.accountId);
      holder.set(new MsaToken(after.getExpireTimeMs(), after.getAccessToken(), previousRefreshToken));
    }
  }

  /**
   * Fetches the chat signing certificate.
   *
   * <p>Non-fatal: without it plain chat is rejected by servers that enforce secure profiles, but
   * commands are unaffected, so a failure here must not stop a visit whose actions are all
   * commands. Certificates last about two days and are renewed on the same margin as everything
   * else, since one that expires halfway through a stay is no better than one that never arrived.
   */
  private PlayerCertificates certificates(Session session) {
    Holder<MinecraftPlayerCertificates> holder = session.manager.getMinecraftPlayerCertificates();
    try {
      if (isDue(holder, authConfig.refreshMarginOrDefault())) {
        holder.refresh();
      }
      MinecraftPlayerCertificates certificates = holder.getCached();
      KeyPair keyPair = certificates.getKeyPair();
      return new PlayerCertificates(
          keyPair.getPublic(),
          keyPair.getPrivate(),
          certificates.getPublicKeySignature(),
          Instant.ofEpochMilli(certificates.getExpireTimeMs()));
    } catch (IOException | RuntimeException e) {
      log.warn("Could not fetch chat certificates for account '{}' ({}); plain chat will be "
          + "unsigned. Commands are unaffected.", session.accountId, AuthFailures.describe(e));
      return null;
    }
  }

  /** True when the held value is missing, expired, or will be before the horizon is out. */
  private static boolean isDue(Holder<? extends Expirable> holder, Duration horizon) {
    Expirable value = holder.getCached();
    return value == null
        || value.getExpireTimeMs() <= System.currentTimeMillis() + horizon.toMillis();
  }

  // ---------------------------------------------------------------- sessions

  private Session session(AccountConfig account) throws AuthException {
    Session cached = sessions.get(account.id());
    if (cached != null) {
      return cached;
    }
    synchronized (loadLock) {
      cached = sessions.get(account.id());
      if (cached != null) {
        return cached;
      }
      Session loaded = load(account);
      sessions.put(account.id(), loaded);
      return loaded;
    }
  }

  private Session load(AccountConfig account) throws AuthException {
    Path file = tokenFile(account);

    Optional<JsonObject> stored;
    try {
      stored = tokenStore.read(file);
    } catch (TokenStoreException e) {
      // Deliberately not "needs login": logging in would overwrite a session that is very
      // probably intact and only unreadable because the key is wrong.
      throw new AuthException("Could not read the stored session for account '" + account.id()
          + "': " + e.getMessage(), e, AuthException.Kind.PERMANENT);
    }
    if (stored.isEmpty()) {
      throw new AuthException("Account '" + account.id() + "' has no stored session. "
          + "Run: chronit login " + account.id(), AuthException.Kind.NEEDS_LOGIN);
    }

    JsonObject json = stored.get();
    JavaAuthManager manager;
    try {
      manager = JavaAuthManager.fromJson(httpClient, json);
    } catch (RuntimeException e) {
      throw new AuthException("The stored session for account '" + account.id()
          + "' could not be read (" + e + "). Run: chronit login " + account.id(),
          e, AuthException.Kind.NEEDS_LOGIN);
    }

    String configured = applicationConfig(account).getClientId();
    String issuedTo = manager.getMsaApplicationConfig().getClientId();
    if (!configured.equals(issuedTo)) {
      // Previously the stored value simply won, so someone who set clientId after their first
      // login went on using the built-in one and had no way to tell.
      throw new AuthException("Account '" + account.id() + "' is configured with clientId '"
          + configured + "' but its stored session was issued to '" + issuedTo
          + "'. A refresh token only works for the application that issued it. "
          + "Run: chronit login " + account.id(), AuthException.Kind.NEEDS_LOGIN);
    }

    Session session = new Session(account.id(), file, manager, SessionMeta.read(json));
    session.listen();
    registerSecrets(session);
    return session;
  }

  private void registerSecrets(Session session) {
    // Rotating rather than plain registration: a daemon left running for months refreshes
    // hundreds of times, and every stale token kept in the redactor is scanned for on every
    // line logged.
    MsaToken msaToken = session.manager.getMsaToken().getCached();
    if (msaToken != null) {
      Redactor.registerRotating("msa-access:" + session.accountId, msaToken.getAccessToken());
      Redactor.registerRotating("msa-refresh:" + session.accountId, msaToken.getRefreshToken());
    }
    MinecraftToken minecraftToken = session.manager.getMinecraftToken().getCached();
    if (minecraftToken != null) {
      Redactor.registerRotating("minecraft:" + session.accountId, minecraftToken.getToken());
    }
  }

  private AuthException noteFailure(Session session, AccountConfig account, Exception error) {
    AuthException.Kind kind = AuthFailures.classify(error);
    String reason = AuthFailures.describe(error);
    session.failure = new Session.Failure(kind, reason, Instant.now());

    String message = switch (kind) {
      case NEEDS_LOGIN -> "The session for account '" + account.id() + "' can no longer be "
          + "renewed (" + reason + "). Run: chronit login " + account.id();
      case PERMANENT -> "Account '" + account.id() + "' cannot be used: " + reason;
      case TRANSIENT -> "Could not reach Microsoft to refresh account '" + account.id()
          + "': " + reason;
    };
    return new AuthException(message, error, kind);
  }

  private AccountStatus describe(AccountConfig account, AccountStatus.State state, String username,
                                 Instant tokenExpiry, SessionMeta meta, String detail) {
    return new AccountStatus(account.id(), state, username, tokenExpiry,
        meta.sessionExpiry(), meta.lastRefreshAt(), detail);
  }

  private MsaApplicationConfig applicationConfig(AccountConfig account) {
    if (account.clientId() == null || account.clientId().isBlank()) {
      // The library's built-in official client id. Registering your own Azure application and
      // setting clientId is the better practice for anything long-lived.
      return new MsaApplicationConfig(MsaConstants.JAVA_TITLE_ID, MsaConstants.SCOPE_TITLE_AUTH);
    }
    return new MsaApplicationConfig(account.clientId(), MsaConstants.SCOPE_OFFLINE_ACCESS);
  }

  /**
   * The library's own factory warns against its default user agent, since requests get blocked
   * when too many applications share one. Connect retries are raised from zero because a daemon
   * that gives up on the first dropped packet reports a failed account for no reason.
   */
  private static HttpClient createHttpClient() {
    String version = AccountManager.class.getPackage().getImplementationVersion();
    HttpClient client = MinecraftAuth.createHttpClient(
        "chronit/" + (version != null ? version : "dev"));
    client.getRetryHandler().setMaxConnectRetries(2);
    return client;
  }

  /**
   * One account's live session, plus what chronit knows about it that the library does not.
   *
   * <p>Persistence hangs off the library's change listeners rather than being done at the end of
   * each operation. Refreshes cascade — asking for a Minecraft token can renew the Microsoft
   * token underneath it — so any explicit "save when finished" leaves a window where Microsoft
   * has rotated the refresh token but the file still holds the old one. If the process died
   * there, the session would be gone.
   */
  private final class Session {

    private final String accountId;
    private final Path file;
    private final JavaAuthManager manager;

    /** Serialises refreshes for this account, so two callers cannot both drive the chain. */
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * A leaf lock, never held while acquiring anything else. Writes are triggered from inside
     * the library's holder locks, so anything else here could invert a lock order.
     */
    private final Object writeLock = new Object();

    private volatile SessionMeta meta;
    private volatile Failure failure;

    /** Last content written, so a cascade of listener callbacks is not a cascade of writes. */
    private String lastWritten;

    private Session(String accountId, Path file, JavaAuthManager manager, SessionMeta meta) {
      this.accountId = accountId;
      this.file = file;
      this.manager = manager;
      this.meta = meta;
    }

    /** @param kind what would fix it, so status can say something useful about a stale session */
    private record Failure(AuthException.Kind kind, String message, Instant at) {
    }

    private void listen() {
      manager.getChangeListeners().add((BasicChangeListener) this::saveQuietly);
      // A Microsoft refresh is the only event that restarts the ninety-day window, and it
      // can be triggered from deep inside the library, so it is stamped here rather than at
      // the call sites that happen to know they asked for one.
      manager.getMsaToken().getChangeListeners().add((BasicChangeListener) () -> {
        meta = meta.refreshedAt(Instant.now());
        saveQuietly();
      });
    }

    private void save() throws IOException {
      synchronized (writeLock) {
        JsonObject json = JavaAuthManager.toJson(manager);
        meta.writeInto(json);

        // Compared before writing rather than after encrypting: every encryption uses a
        // fresh IV, so identical sessions never produce identical files.
        String text = json.toString();
        if (text.equals(lastWritten)) {
          return;
        }
        tokenStore.write(file, json);
        lastWritten = text;
      }
    }

    private void saveQuietly() {
      try {
        save();
      } catch (IOException | RuntimeException e) {
        // Not fatal for the run in progress: the session is live in memory. It does mean a
        // rotated refresh token is only in memory, so the next process start may need a
        // login — worth saying loudly.
        log.error("Could not save the session for account '{}' to {}: {}",
            accountId, file, e.toString());
      }
    }

    private void clearFailure() {
      failure = null;
    }
  }
}
