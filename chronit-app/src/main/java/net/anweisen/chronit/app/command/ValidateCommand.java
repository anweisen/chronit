package net.anweisen.chronit.app.command;

import net.anweisen.chronit.core.config.ChronitConfig;
import net.anweisen.chronit.core.config.JobConfig;
import net.anweisen.chronit.core.config.ProtocolSpec;
import net.anweisen.chronit.core.config.ServerConfig;
import net.anweisen.chronit.core.config.VisitConfig;
import net.anweisen.chronit.core.driver.SessionSettings;
import net.anweisen.chronit.core.run.CronSchedule;
import net.anweisen.chronit.core.util.Durations;
import net.anweisen.chronit.driver.mcpl.McplDriver;
import picocli.CommandLine;

import java.util.concurrent.Callable;

/**
 * Checks the configuration and shows what it would do.
 *
 * <p>More useful than a bare "valid" or "invalid": printing the resolved schedule and the merged
 * per-server settings is how a typo in an inherited default gets noticed before the job runs
 * unattended at three in the morning.
 */
@CommandLine.Command(
        name = "validate",
        description = "Check the configuration file and print the resulting schedule.")
public final class ValidateCommand implements Callable<Integer> {

    @CommandLine.Mixin
    ConfigMixin configMixin;

    @Override
    public Integer call() {
        ChronitConfig config = configMixin.load();
        System.out.println("Configuration " + configMixin.resolveConfigFile() + " is valid.");
        System.out.println();

        System.out.println("Accounts");
        config.accountsOrEmpty().forEach(account -> System.out.printf("  %-14s %s%s%n",
                account.id(),
                account.authOrDefault(),
                account.authOrDefault() == net.anweisen.chronit.core.config.AccountConfig.AuthMode.OFFLINE
                        ? " as " + account.username() : ""));

        System.out.println();
        System.out.println("Servers");
        for (ServerConfig server : config.serversOrEmpty()) {
            SessionSettings settings = SessionSettings.resolve(config, server);
            System.out.printf("  %-14s %-30s %s%n", server.id(), server.address(),
                    describeProtocol(server));
            System.out.printf("  %-14s   resource packs: %s, code of conduct: %s, ready: %s%n", "",
                    settings.resourcePack().mode(),
                    settings.acceptCodeOfConduct() ? "accept" : "REFUSE",
                    describeReadiness(settings));
        }

        System.out.println();
        System.out.println("Jobs");
        for (JobConfig job : config.jobsOrEmpty()) {
            CronSchedule schedule = CronSchedule.parse(job.cron(), job.zoneOrDefault());
            System.out.printf("  %-14s %s%s%n", job.id(), schedule,
                    job.isEnabled() ? "" : "  (disabled)");
            schedule.next().ifPresent(next -> System.out.printf("  %-14s   next run: %s (in %s)%n", "",
                    next, schedule.timeUntilNext().map(Durations::format).orElse("?")));
            for (VisitConfig visit : job.visits()) {
                System.out.printf("  %-14s   -> %s as %s, stay %s, %d action(s)%n", "",
                        visit.server(), visit.account(),
                        Durations.format(visit.stayForOrDefault()), visit.onReadyOrEmpty().size());
            }
        }

        System.out.println();
        System.out.printf("Client speaks Minecraft %s (protocol %d).%n",
                McplDriver.NATIVE_VERSION, McplDriver.NATIVE_PROTOCOL);
        return 0;
    }

    private String describeProtocol(ServerConfig server) {
        ProtocolSpec spec = ProtocolSpec.parse(server.protocol());
        return switch (spec) {
            case ProtocolSpec.Auto ignored ->
                    "protocol auto (try " + McplDriver.NATIVE_PROTOCOL + " first)";
            case ProtocolSpec.Exact exact -> exact.protocol() == McplDriver.NATIVE_PROTOCOL
                    ? "protocol " + exact.protocol() + " (native)"
                    : "protocol " + exact.protocol() + " (needs translation)";
            case ProtocolSpec.Named named -> "Minecraft " + named.version()
                    + (named.version().equalsIgnoreCase(McplDriver.NATIVE_VERSION)
                    ? " (native)" : " (needs translation)");
        };
    }

    private String describeReadiness(SessionSettings settings) {
        StringBuilder text = new StringBuilder();
        if (Boolean.TRUE.equals(settings.readyWhen().spawn())) {
            text.append("spawn");
        }
        if (settings.readyWhen().minChunks() != null && settings.readyWhen().minChunks() > 0) {
            text.append(text.isEmpty() ? "" : " + ").append(settings.readyWhen().minChunks()).append(" chunks");
        }
        if (settings.readyWhen().chat() != null) {
            text.append(text.isEmpty() ? "" : " + ").append("chat /").append(settings.readyWhen().chat()).append('/');
        }
        text.append(" + ").append(Durations.format(settings.readyWhen().settle()));
        return text.toString();
    }
}
