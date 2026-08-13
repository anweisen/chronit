package net.anweisen.chronit.core.driver;

import java.time.Duration;

/**
 * Details of a completed join, for logs and run history.
 *
 * @param protocolVersion the protocol actually spoken, which differs from the driver's native one
 *                        when translation was used
 */
public record ReadyInfo(
        int entityId,
        String dimension,
        int chunksReceived,
        int protocolVersion,
        boolean translated,
        Duration timeToReady) {
}
