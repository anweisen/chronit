package net.anweisen.chronit.core.driver;

/**
 * Callbacks a driver invokes as a session progresses.
 *
 * <p>All methods have no-op defaults so an implementation only overrides what it cares about.
 * Called from network threads: implementations must not block.
 */
public interface ClientEvents {

    ClientEvents NONE = new ClientEvents() {
    };

    default void onPhase(Phase phase) {
    }

    default void onChat(ChatLine line) {
    }

    /** Reports each resource pack status the driver sent on our behalf. */
    default void onResourcePack(ResourcePackEvent event) {
    }

    /**
     * The server presented a code of conduct, which since Minecraft 26.x must be accepted during
     * configuration or the connection is dropped. The driver has already replied by the time this
     * is called; the text is surfaced because agreeing to something unread is worth logging.
     */
    default void onCodeOfConduct(String text) {
    }

    /**
     * A container was opened, or the contents of an open one changed.
     *
     * <p>Fired on both because a window arrives empty and is populated a moment later, and a wait
     * that cannot tell the difference would let a click land on a menu that is not there yet.
     */
    default void onScreen(ContainerInfo info) {
    }

    default void onScreenClose(int containerId) {
    }

    default void onReady(ReadyInfo info) {
    }

    default void onDisconnect(DisconnectInfo info) {
    }
}
