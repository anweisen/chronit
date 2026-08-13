package net.anweisen.chronit.app.command;

import net.anweisen.chronit.core.auth.AccountManager;
import net.anweisen.chronit.core.auth.AccountStatus;
import net.anweisen.chronit.core.auth.TokenRefresher;
import net.anweisen.chronit.core.config.ChronitConfig;
import net.anweisen.chronit.core.config.WebConfig;
import net.anweisen.chronit.core.run.Orchestrator;
import net.anweisen.chronit.core.run.Scheduler;
import net.anweisen.chronit.driver.mcpl.McplDriver;
import net.anweisen.chronit.web.WebInterface;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;

/**
 * Runs the scheduler until stopped. The container's default command.
 */
@CommandLine.Command(
        name = "daemon",
        description = "Run the built-in scheduler, and the web interface if enabled.")
public final class DaemonCommand implements Callable<Integer> {

    private static final Logger log = LoggerFactory.getLogger(DaemonCommand.class);

    @CommandLine.Mixin
    ConfigMixin configMixin;

    @Override
    public Integer call() throws Exception {
        ChronitConfig config = configMixin.load();
        McplDriver driver = new McplDriver();
        AccountManager accounts = new AccountManager(config);
        Orchestrator orchestrator = new Orchestrator(config, driver, accounts);
        Scheduler scheduler = new Scheduler(config, orchestrator);
        TokenRefresher tokens = new TokenRefresher(config, accounts, orchestrator.locks()::isBusy);

        log.info("chronit starting — Minecraft {} (protocol {}), {} account(s), {} server(s), {} job(s)",
                McplDriver.NATIVE_VERSION, McplDriver.NATIVE_PROTOCOL,
                config.accountsOrEmpty().size(), config.serversOrEmpty().size(),
                config.jobsOrEmpty().size());

        warnAboutAccountsNeedingLogin(config, accounts);

        WebInterface web = null;
        WebConfig webConfig = config.webOrDisabled();
        if (webConfig.isEnabled()) {
            web = new WebInterface(config, orchestrator, scheduler, accounts,
                    McplDriver.NATIVE_VERSION, McplDriver.NATIVE_PROTOCOL);
            web.start();
        } else {
            log.info("Web interface disabled. Enable it with web.enabled: true to see status and "
                    + "start logins from a browser.");
        }

        // Started before the scheduler so a session that went stale while the process was down is
        // renewed as the daemon comes up, rather than during the first visit that needs it.
        tokens.start();
        scheduler.start();

        CountDownLatch stopped = new CountDownLatch(1);
        WebInterface finalWeb = web;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down");
            if (finalWeb != null) {
                finalWeb.stop();
            }
            tokens.close();
            // Closing the scheduler waits for a visit in progress, so the client leaves cleanly
            // rather than having the connection dropped underneath it.
            scheduler.close();
            driver.shutdown();
            stopped.countDown();
        }, "chronit-shutdown"));

        stopped.await();
        return 0;
    }

    /**
     * Says so at startup when an account will fail later.
     *
     * <p>This reads what is already on disk rather than contacting Microsoft, so it is instant; the
     * refresher's first sweep follows a moment later and gives the authoritative answer. Finding
     * out at startup beats finding out from a failed run at 3am.
     */
    private void warnAboutAccountsNeedingLogin(ChronitConfig config, AccountManager accounts) {
        for (var account : config.accountsOrEmpty()) {
            AccountStatus status = accounts.status(account);
            if (!status.isUsable()) {
                log.error("Account '{}' is not usable: {}", status.id(), status.detail());
            }
        }
    }
}
