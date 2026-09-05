package net.anweisen.chronit.core.auth;

import com.google.gson.JsonObject;
import net.anweisen.chronit.core.config.AccountConfig;
import net.anweisen.chronit.core.config.AuthConfig;
import net.anweisen.chronit.core.driver.AuthContext;
import net.anweisen.chronit.core.util.Redactor;
import net.raphimc.minecraftauth.MinecraftAuth;
import net.raphimc.minecraftauth.java.JavaAuthManager;
import net.raphimc.minecraftauth.msa.data.MsaConstants;
import net.raphimc.minecraftauth.msa.model.MsaApplicationConfig;
import net.raphimc.minecraftauth.msa.model.MsaToken;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers what can be asserted without contacting Microsoft: how a stored session is read, and what
 * is reported about it. The refresh itself needs the real service and is left to manual testing.
 */
class AccountManagerTest {

  private static final String ACCESS_TOKEN = "EwAoA-a-fake-microsoft-access-token";
  private static final String REFRESH_TOKEN = "M.C123-a-fake-microsoft-refresh-token";

  @TempDir
  Path stateDir;

  @AfterEach
  void clearSecrets() {
    Redactor.clear();
  }

  @Test
  void resolvesOfflineAccountsWithoutASession() throws AuthException {
    AccountConfig account = new AccountConfig("local", AccountConfig.AuthMode.OFFLINE,
        "TestBot", null, null);

    AuthContext identity = manager().resolve(account);

    assertEquals("TestBot", identity.username());
    assertFalse(identity.online());
    assertEquals(AccountStatus.State.OFFLINE, manager().status(account).state());
  }

  @Test
  void doesNotTryToRefreshAnOfflineAccount() throws AuthException {
    AccountConfig account = new AccountConfig("local", AccountConfig.AuthMode.OFFLINE,
        "TestBot", null, null);

    assertFalse(manager().refresh(account, AccountManager.FORCE));
  }

  @Test
  void reportsAMissingSessionAsNeedingLogin() {
    AccountStatus status = manager().status(microsoftAccount(null));

    assertEquals(AccountStatus.State.NEEDS_LOGIN, status.state());
    assertTrue(status.detail().contains("chronit login main"), status.detail());
  }

  /**
   * A file that cannot be decrypted is an error, not a missing login. Suggesting a login here
   * would have the operator replace a session that was intact all along, and lose it.
   */
  @Test
  void reportsAnUnreadableSessionAsAnErrorRatherThanAMissingLogin() throws IOException {
    AccountConfig account = microsoftAccount(null);
    new TokenStore("the-original-key").write(tokenFile(account), storedSession(SessionMeta.loggedInNow()));

    AccountManager wrongKey = new AccountManager(stateDir, new TokenStore("a-different-key"));
    AccountStatus status = wrongKey.status(account);

    assertEquals(AccountStatus.State.ERROR, status.state());
    assertFalse(status.detail().contains("chronit login"), status.detail());
  }

  @Test
  void reportsAStoredSessionAsReady() throws IOException {
    AccountConfig account = microsoftAccount(null);
    Instant refreshedAt = Instant.now().minus(2, ChronoUnit.DAYS);
    store(account, SessionMeta.empty().refreshedAt(refreshedAt));

    AccountStatus status = manager().status(account);

    assertEquals(AccountStatus.State.READY, status.state());
    assertEquals(refreshedAt, status.lastRefresh());
    assertEquals(refreshedAt.plus(AuthConfig.SESSION_LIFETIME), status.sessionExpiry());
  }

  /**
   * The bug this replaced: the Microsoft <em>access</em> token expires after an hour, and it was
   * being read as the refresh token expiring. Every account therefore reported NEEDS_LOGIN an
   * hour after logging in, which made {@code chronit accounts} useless as a monitoring check.
   */
  @Test
  void staysReadyWhenTheHourLongMicrosoftTokenHasExpired() throws IOException {
    AccountConfig account = microsoftAccount(null);
    Instant hourAgo = Instant.now().minus(1, ChronoUnit.HOURS);
    store(account, SessionMeta.empty().refreshedAt(hourAgo),
        new MsaToken(hourAgo.toEpochMilli(), ACCESS_TOKEN, REFRESH_TOKEN));

    AccountStatus status = manager().status(account);

    assertEquals(AccountStatus.State.READY, status.state());
  }

  @Test
  void noticesASessionLeftAloneBeyondTheNinetyDayWindow() throws IOException {
    AccountConfig account = microsoftAccount(null);
    store(account, SessionMeta.empty().refreshedAt(
        Instant.now().minus(AuthConfig.SESSION_LIFETIME).minus(1, ChronoUnit.DAYS)));

    AccountStatus status = manager().status(account);

    assertEquals(AccountStatus.State.NEEDS_LOGIN, status.state());
    assertTrue(status.detail().contains("ninety-day"), status.detail());
  }

  /**
   * A refresh token only works for the application that issued it. This used to pass silently:
   * the stored application config won, so someone who configured their own Azure registration
   * after their first login went on using the built-in one with no way to tell.
   */
  @Test
  void refusesASessionIssuedToADifferentAzureApplication() throws IOException {
    AccountConfig account = microsoftAccount("2b1a4e5c-0000-4000-8000-0d6f9c2b7a11");
    store(account, SessionMeta.loggedInNow());

    AccountStatus status = manager().status(account);

    assertEquals(AccountStatus.State.NEEDS_LOGIN, status.state());
    assertTrue(status.detail().contains("clientId"), status.detail());
  }

  @Test
  void reportsNoSessionAgeForAFileWrittenBeforeItWasRecorded() throws IOException {
    AccountConfig account = microsoftAccount(null);
    store(account, SessionMeta.empty());

    AccountStatus status = manager().status(account);

    assertEquals(AccountStatus.State.READY, status.state());
    assertNull(status.sessionExpiry());
    assertTrue(status.detail().contains("unknown"), status.detail());
  }

  /** Tokens must never reach a log line, including the ones only ever held in memory. */
  @Test
  void registersStoredTokensWithTheRedactorAsSoonAsTheyAreLoaded() throws IOException {
    AccountConfig account = microsoftAccount(null);
    store(account, SessionMeta.loggedInNow());

    manager().status(account);

    assertEquals("using ***", Redactor.redact("using " + ACCESS_TOKEN));
    assertEquals("using ***", Redactor.redact("using " + REFRESH_TOKEN));
  }

  @Test
  void putsTokensUnderTheStateDirectoryByDefault() {
    AccountConfig account = microsoftAccount(null);

    assertEquals(stateDir.resolve("tokens").resolve("main.json"), manager().tokenFile(account));
  }

  @Test
  void honoursAnExplicitTokenStorePath() {
    Path elsewhere = stateDir.resolve("somewhere/else.json");
    AccountConfig account = new AccountConfig("main", AccountConfig.AuthMode.MICROSOFT,
        null, elsewhere, null);

    assertEquals(elsewhere, manager().tokenFile(account));
  }

  @Test
  void looksFurtherAheadForABackgroundSweepThanForASingleVisit() {
    AuthConfig auth = new AuthConfig(true, Duration.ofHours(6), Duration.ofMinutes(30));

    // A sweep every six hours has to renew anything expiring in the next six, or a token would
    // lapse in between two sweeps that each considered it fine.
    assertEquals(Duration.ofHours(6).plusMinutes(30), auth.sweepHorizon());
    assertEquals(Duration.ofMinutes(30), auth.refreshMarginOrDefault());
  }

  private AccountManager manager() {
    return new AccountManager(stateDir, new TokenStore(null));
  }

  private AccountConfig microsoftAccount(String clientId) {
    return new AccountConfig("main", AccountConfig.AuthMode.MICROSOFT, null, null, clientId);
  }

  private Path tokenFile(AccountConfig account) {
    return manager().tokenFile(account);
  }

  private void store(AccountConfig account, SessionMeta meta) throws IOException {
    store(account, meta, new MsaToken(
        System.currentTimeMillis() + Duration.ofHours(1).toMillis(), ACCESS_TOKEN, REFRESH_TOKEN));
  }

  private void store(AccountConfig account, SessionMeta meta, MsaToken msaToken) throws IOException {
    new TokenStore(null).write(tokenFile(account), storedSession(meta, msaToken));
  }

  private JsonObject storedSession(SessionMeta meta) {
    return storedSession(meta, new MsaToken(
        System.currentTimeMillis() + Duration.ofHours(1).toMillis(), ACCESS_TOKEN, REFRESH_TOKEN));
  }

  /**
   * A token file as the library would have written it. Built through the library rather than by
   * hand so that the device key material is real and the shape cannot drift from what it reads.
   */
  private JsonObject storedSession(SessionMeta meta, MsaToken msaToken) {
    JavaAuthManager manager = JavaAuthManager.create(MinecraftAuth.createHttpClient("chronit-test"))
        .msaApplicationConfig(new MsaApplicationConfig(
            MsaConstants.JAVA_TITLE_ID, MsaConstants.SCOPE_TITLE_AUTH))
        .login(msaToken);

    JsonObject json = JavaAuthManager.toJson(manager);
    assertNotNull(json.get("msaToken"));
    meta.writeInto(json);
    return json;
  }
}
