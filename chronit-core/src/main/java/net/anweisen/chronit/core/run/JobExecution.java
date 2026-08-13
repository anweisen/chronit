package net.anweisen.chronit.core.run;

import net.anweisen.chronit.core.driver.ClientHandle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A job that is currently running, and the handle used to stop it.
 *
 * <p>Stopping needs two things to happen, and doing only one leaves the job half-dead. The worker
 * thread spends most of its life blocked — sleeping between actions, waiting out a {@code stayFor},
 * waiting on a join — so it has to be interrupted. And the server has a session open for us, which
 * should be closed the way a client closes it rather than left for the read timeout to notice; an
 * account that vanishes without disconnecting is the one that gets "already logged in" on its next
 * visit.
 *
 * <p>So cancelling disconnects first, then interrupts.
 */
public final class JobExecution {

    private static final Logger log = LoggerFactory.getLogger(JobExecution.class);

    private final String jobId;
    private final String trigger;
    private final Instant startedAt;
    private final Thread worker;

    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicReference<ClientHandle> activeClient = new AtomicReference<>();
    private volatile String currentServer;

    JobExecution(String jobId, String trigger, Thread worker) {
        this.jobId = jobId;
        this.trigger = trigger;
        this.startedAt = Instant.now();
        this.worker = worker;
    }

    public String jobId() {
        return jobId;
    }

    public String trigger() {
        return trigger;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Duration elapsed() {
        return Duration.between(startedAt, Instant.now());
    }

    /** Which server the job is on right now, if any. */
    public String currentServer() {
        return currentServer;
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    /**
     * Asks the job to stop.
     *
     * @return false if it was already stopping
     */
    public boolean cancel() {
        if (!cancelled.compareAndSet(false, true)) {
            return false;
        }
        log.info("Cancelling job '{}' after {}", jobId, net.anweisen.chronit.core.util.Durations.format(elapsed()));

        ClientHandle client = activeClient.get();
        if (client != null) {
            try {
                // Leave properly, so the server tears the session down now rather than when its
                // read timeout eventually fires.
                client.disconnect("Cancelled");
            } catch (RuntimeException e) {
                log.debug("Client did not disconnect cleanly on cancel: {}", e.toString());
            }
        }
        worker.interrupt();
        return true;
    }

    /** Registers the session currently in use, so cancelling can close it. */
    void attach(ClientHandle client, String serverId) {
        activeClient.set(client);
        currentServer = serverId;
    }

    void detach() {
        activeClient.set(null);
        currentServer = null;
    }

    /**
     * Turns a cancellation into the exception the run loop unwinds on.
     *
     * <p>Called at the points between blocking operations, so a cancel that arrives while the
     * thread happens to be running rather than sleeping still takes effect promptly.
     */
    void throwIfCancelled() throws InterruptedException {
        if (cancelled.get()) {
            throw new InterruptedException("Job '" + jobId + "' was cancelled");
        }
    }
}
