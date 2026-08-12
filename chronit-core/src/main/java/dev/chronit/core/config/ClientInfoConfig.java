package dev.chronit.core.config;

import dev.chronit.core.driver.ClientInformation;

import java.util.List;

/**
 * The settings a real client reports to the server just after connecting.
 *
 * <p>These end up in the client information packet. Some plugins read them (view distance and
 * locale in particular), and a client that never sends the packet at all is unusual enough to be
 * worth avoiding. Every field is nullable: null means "inherit from defaults".
 */
public record ClientInfoConfig(
        String locale,
        Integer viewDistance,
        ClientInformation.ChatVisibility chatVisibility,
        Boolean chatColors,
        List<ClientInformation.SkinPart> skinParts,
        ClientInformation.MainHand mainHand,
        Boolean textFiltering,
        Boolean allowServerListings,
        ClientInformation.ParticleStatus particleStatus) {

    public static final ClientInfoConfig DEFAULTS = new ClientInfoConfig(
            "en_us",
            8,
            ClientInformation.ChatVisibility.FULL,
            Boolean.TRUE,
            List.of(ClientInformation.SkinPart.values()),
            ClientInformation.MainHand.RIGHT,
            Boolean.FALSE,
            Boolean.TRUE,
            ClientInformation.ParticleStatus.ALL);

    /** Returns a copy with every unset field taken from {@code base}. */
    public ClientInfoConfig withFallback(ClientInfoConfig base) {
        if (base == null) {
            return this;
        }
        return new ClientInfoConfig(
                locale != null ? locale : base.locale,
                viewDistance != null ? viewDistance : base.viewDistance,
                chatVisibility != null ? chatVisibility : base.chatVisibility,
                chatColors != null ? chatColors : base.chatColors,
                skinParts != null ? skinParts : base.skinParts,
                mainHand != null ? mainHand : base.mainHand,
                textFiltering != null ? textFiltering : base.textFiltering,
                allowServerListings != null ? allowServerListings : base.allowServerListings,
                particleStatus != null ? particleStatus : base.particleStatus);
    }

    /** Converts to the driver-facing form, filling anything still unset from the defaults. */
    public ClientInformation toClientInformation() {
        ClientInfoConfig c = this.withFallback(DEFAULTS);
        return new ClientInformation(
                c.locale(),
                c.viewDistance(),
                c.chatVisibility(),
                c.chatColors(),
                c.skinParts(),
                c.mainHand(),
                c.textFiltering(),
                c.allowServerListings(),
                c.particleStatus());
    }
}
