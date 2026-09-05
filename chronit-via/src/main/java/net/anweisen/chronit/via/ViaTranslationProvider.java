package net.anweisen.chronit.via;

import net.anweisen.chronit.core.driver.TranslationProvider;

import java.util.Optional;
import java.util.OptionalInt;

/**
 * Advertises ViaVersion's capabilities to the orchestrator.
 *
 * <p>This is the half of the integration that {@code chronit-core} can see, and it deliberately
 * deals only in version numbers and names — no Netty, no protocol library. The actual wiring lives
 * in {@link ViaPipelineCustomizer}, which the driver discovers separately.
 *
 * <p>Version name resolution living here rather than in a table in core is the point: any such
 * table would need an entry for every Minecraft release and would go stale with the next one, while
 * ViaVersion maintains that registry as its core business.
 */
public final class ViaTranslationProvider implements TranslationProvider {

  @Override
  public String id() {
    return "viaversion";
  }

  @Override
  public boolean canTranslate(int nativeProtocol, int targetProtocol) {
    ViaBootstrap.ensureInitialised();
    return ViaVersions.canTranslate(nativeProtocol, targetProtocol);
  }

  @Override
  public OptionalInt resolveVersionName(String versionName) {
    ViaBootstrap.ensureInitialised();
    return ViaVersions.resolveVersionName(versionName);
  }

  @Override
  public Optional<String> versionName(int protocol) {
    ViaBootstrap.ensureInitialised();
    return ViaVersions.versionName(protocol);
  }
}
