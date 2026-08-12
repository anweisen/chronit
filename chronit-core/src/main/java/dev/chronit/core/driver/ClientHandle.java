package dev.chronit.core.driver;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** A live session. Closing it disconnects. */
public interface ClientHandle extends AutoCloseable {

    ServerTarget target();

    /**
     * Completes when the readiness conditions are met, or completes exceptionally on timeout or
     * if the session ends first.
     */
    CompletableFuture<ReadyInfo> whenReady();

    /** Completes when the session ends, for any reason. Never completes exceptionally. */
    CompletableFuture<DisconnectInfo> whenClosed();

    /**
     * Sends a command.
     *
     * @param command without the leading slash
     */
    void sendCommand(String command);

    /** Sends a plain chat message, signed when a chat session was established. */
    void sendChat(String message);

    /** The container the server currently has open for us, if any. */
    Optional<ContainerInfo> openContainer();

    /**
     * Clicks a slot in the open container.
     *
     * @throws IllegalStateException if no container is open, or the slot cannot be addressed
     */
    void clickSlot(SlotClick click);

    /** Closes the open container, as a player pressing escape would. No-op if none is open. */
    void closeScreen();

    boolean isConnected();

    Phase phase();

    /** Disconnects, recording {@code reason} in the run history. */
    void disconnect(String reason);

    @Override
    void close();
}
