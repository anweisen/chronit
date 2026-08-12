package dev.chronit.app.command;

import dev.chronit.core.auth.AccountManager;
import dev.chronit.core.config.ChronitConfig;
import dev.chronit.core.config.JobConfig;
import dev.chronit.core.config.VisitConfig;
import dev.chronit.core.run.Orchestrator;
import dev.chronit.core.state.RunRecord;
import dev.chronit.core.util.Durations;
import dev.chronit.driver.mcpl.McplDriver;
import picocli.CommandLine;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * Runs one job, or a single ad-hoc visit, and exits.
 *
 * <p>This is the entry point for external schedulers — system cron, a systemd timer, a Kubernetes
 * CronJob. The exit code reflects whether every visit succeeded, so the surrounding scheduler can
 * alert on failure.
 */
@CommandLine.Command(
        name = "run",
        description = "Run a job once and exit. Suitable for external schedulers.")
public final class RunCommand implements Callable<Integer> {

    @CommandLine.Mixin
    ConfigMixin configMixin;

    @CommandLine.Parameters(index = "0", arity = "0..1",
            description = "Job id to run. Omit when using --server.")
    String jobId;

    @CommandLine.Option(names = "--server", description = "Visit one server instead of running a job.")
    String serverId;

    @CommandLine.Option(names = "--account", description = "Account to use with --server.")
    String accountId;

    @CommandLine.Option(names = "--stay",
            description = "How long to stay with --server, e.g. 10m. Default: 1m.")
    String stay = "1m";

    @Override
    public Integer call() throws Exception {
        ChronitConfig config = configMixin.load();
        McplDriver driver = new McplDriver();
        AccountManager accounts = new AccountManager(config);
        Orchestrator orchestrator = new Orchestrator(config, driver, accounts);

        try {
            RunRecord record = serverId != null
                    ? runAdHoc(config, orchestrator)
                    : runConfiguredJob(config, orchestrator);

            printSummary(record);
            return record.succeeded() ? 0 : 1;
        } finally {
            driver.shutdown();
        }
    }

    private RunRecord runAdHoc(ChronitConfig config, Orchestrator orchestrator) throws InterruptedException {
        if (config.server(serverId).isEmpty()) {
            throw new IllegalArgumentException("No server with id '" + serverId + "'");
        }
        String account = accountId != null ? accountId
                : config.accountsOrEmpty().stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No accounts configured")).id();
        if (config.account(account).isEmpty()) {
            throw new IllegalArgumentException("No account with id '" + account + "'");
        }

        // Reuse the job's visit definition when one exists, so an ad-hoc run of a configured server
        // executes the same command sequence a scheduled run would.
        VisitConfig fromJob = config.jobsOrEmpty().stream()
                .flatMap(job -> job.visits().stream())
                .filter(visit -> visit.server().equals(serverId))
                .findFirst()
                .orElse(null);

        VisitConfig visit = new VisitConfig(
                serverId,
                account,
                Durations.parse(stay),
                fromJob != null ? fromJob.onReady() : List.of(),
                fromJob != null ? fromJob.onLeave() : List.of(),
                fromJob != null ? fromJob.onFail() : null,
                java.time.Duration.ZERO);

        if (fromJob != null) {
            System.out.println("Using the command sequence configured for '" + serverId + "'.");
        }
        return orchestrator.runVisit(visit, "cli");
    }

    private RunRecord runConfiguredJob(ChronitConfig config, Orchestrator orchestrator)
            throws InterruptedException {
        if (jobId == null) {
            throw new IllegalArgumentException("Give a job id, or use --server for a one-off visit. "
                    + "Configured jobs: " + config.jobsOrEmpty().stream().map(JobConfig::id).toList());
        }
        JobConfig job = config.job(jobId).orElseThrow(() ->
                new IllegalArgumentException("No job with id '" + jobId + "'. Configured jobs: "
                        + config.jobsOrEmpty().stream().map(JobConfig::id).toList()));

        Optional<RunRecord> record = orchestrator.runJob(job, "cli");
        return record.orElseThrow(() ->
                new IllegalStateException("Job '" + jobId + "' did not run — another run is in progress"));
    }

    private void printSummary(RunRecord record) {
        System.out.println();
        System.out.printf("Run %s finished in %s%n", record.runId(), Durations.format(record.duration()));
        for (RunRecord.VisitRecord visit : record.visits()) {
            System.out.printf("  %-8s %-14s %s (%s)%n",
                    visit.success() ? "ok" : "FAILED",
                    visit.serverId(),
                    visit.detail(),
                    Durations.format(visit.duration()));
        }
    }
}
