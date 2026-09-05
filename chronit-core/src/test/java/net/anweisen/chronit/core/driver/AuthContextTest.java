package net.anweisen.chronit.core.driver;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class AuthContextTest {

  /**
   * The offline UUID must be derived exactly as a vanilla server derives it for
   * {@code online-mode=false}, or the bot appears as a different player on every visit and loses
   * its inventory, home and permissions.
   */
  @Test
  void derivesTheOfflineUuidTheSameWayAVanillaServerDoes() {
    AuthContext context = AuthContext.offline("ChronitBot");

    UUID expected = UUID.nameUUIDFromBytes(
        "OfflinePlayer:ChronitBot".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    assertEquals(expected, context.uuid());
    assertEquals(3, context.uuid().version(), "name-based UUIDs are version 3");
  }

  @Test
  void offlineUuidIsStableAcrossCalls() {
    assertEquals(AuthContext.offline("ChronitBot").uuid(), AuthContext.offline("ChronitBot").uuid());
  }

  @Test
  void offlineUuidIsCaseSensitive() {
    assertNotEquals(AuthContext.offline("ChronitBot").uuid(), AuthContext.offline("chronitbot").uuid());
  }

  @Test
  void offlineIdentitiesCarryNoTokenAndCannotSignChat() {
    AuthContext context = AuthContext.offline("ChronitBot");
    assertFalse(context.online());
    assertEquals(null, context.accessToken());
    assertFalse(context.canSignChat());
  }
}
