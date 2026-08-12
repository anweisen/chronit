package dev.chronit.core.run;

import dev.chronit.core.driver.ContainerInfo;

import java.util.regex.Pattern;

/**
 * Containers the server opens on our behalf.
 *
 * <p>Republished whenever the contents change, not only when the window first appears, so a wait
 * can insist on a menu that has actually been filled in. A plugin opens the window and populates it
 * a moment later; clicking in the gap does nothing.
 */
public final class ScreenBus extends SignalBus<ContainerInfo> {

    /**
     * Starts listening for a container whose title matches {@code pattern} and whose contents have
     * arrived.
     *
     * <p>Register this before sending the command that opens the menu — servers often open it
     * within a millisecond or two.
     */
    public SignalWaiter<ContainerInfo> expect(Pattern pattern) {
        return expect(container ->
                container.contentsReceived() && pattern.matcher(container.title()).find());
    }
}
