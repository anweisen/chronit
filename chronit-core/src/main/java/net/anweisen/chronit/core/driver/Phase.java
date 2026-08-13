package net.anweisen.chronit.core.driver;

/**
 * How far a session has got.
 *
 * <p>Mirrors the protocol's own progression, with the important distinction that reaching the play
 * state is not the same as being in the world: {@link #JOINING} covers the gap between the two,
 * during which commands would be silently dropped.
 */
public enum Phase {
    CONNECTING,
    LOGIN,
    /** Registries, resource packs, code of conduct — everything the server demands before play. */
    CONFIGURATION,
    /** In the play state, waiting for the join packet, the first teleport and initial chunks. */
    JOINING,
    /** Fully spawned; commands will be processed. */
    IN_WORLD,
    LEAVING,
    CLOSED
}
