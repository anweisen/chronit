package net.anweisen.chronit.core.driver;

import java.util.List;

/**
 * Client settings reported to the server, in a protocol-independent form.
 *
 * <p>These enums deliberately mirror the wire values without being the protocol library's own
 * types, so that {@code chronit-core} carries no dependency on any particular Minecraft version.
 * Drivers translate them.
 */
public record ClientInformation(
    String locale,
    int viewDistance,
    ChatVisibility chatVisibility,
    boolean chatColors,
    List<SkinPart> skinParts,
    MainHand mainHand,
    boolean textFiltering,
    boolean allowServerListings,
    ParticleStatus particleStatus) {

  public enum ChatVisibility { FULL, SYSTEM, HIDDEN }

  public enum MainHand { LEFT, RIGHT }

  public enum ParticleStatus { ALL, DECREASED, MINIMAL }

  public enum SkinPart { CAPE, JACKET, LEFT_SLEEVE, RIGHT_SLEEVE, LEFT_PANTS_LEG, RIGHT_PANTS_LEG, HAT }
}
