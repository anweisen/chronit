package dev.chronit.web.view;

import dev.chronit.core.auth.AccountStatus;
import dev.chronit.core.config.AccountConfig;
import dev.chronit.core.config.ActionConfig;
import dev.chronit.core.config.ChronitConfig;
import dev.chronit.core.config.JobConfig;
import dev.chronit.core.config.RetryConfig;
import dev.chronit.core.config.ServerConfig;
import dev.chronit.core.config.VisitConfig;
import dev.chronit.core.driver.SessionSettings;
import dev.chronit.core.run.CronSchedule;
import dev.chronit.core.run.JobExecution;
import dev.chronit.core.run.Scheduler;
import dev.chronit.core.state.RunRecord;
import dev.chronit.core.util.Durations;
import dev.chronit.core.util.Redactor;
import dev.chronit.web.html.Node;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static dev.chronit.web.html.H.a;
import static dev.chronit.web.html.H.article;
import static dev.chronit.web.html.H.attr;
import static dev.chronit.web.html.H.button;
import static dev.chronit.web.html.H.cls;
import static dev.chronit.web.html.H.code;
import static dev.chronit.web.html.H.details;
import static dev.chronit.web.html.H.div;
import static dev.chronit.web.html.H.h1;
import static dev.chronit.web.html.H.h2;
import static dev.chronit.web.html.H.h3;
import static dev.chronit.web.html.H.href;
import static dev.chronit.web.html.H.li;
import static dev.chronit.web.html.H.main;
import static dev.chronit.web.html.H.p;
import static dev.chronit.web.html.H.section;
import static dev.chronit.web.html.H.span;
import static dev.chronit.web.html.H.summary;
import static dev.chronit.web.html.H.text;
import static dev.chronit.web.html.H.time;
import static dev.chronit.web.html.H.ul;
import static dev.chronit.web.html.H.urlSegment;

/**
 * The dashboard.
 *
 * <p>Ordered by what an operator wants to know, in that order: is anything waiting on me, what is
 * running, what is coming, what happened. The summary reads as a sentence rather than a row of
 * tiles or a string of fragments separated by dots — everything below it is built from the two
 * shared shapes in {@link Ui}, a labelled value and a chip, so the same kind of information looks
 * the same wherever it appears.
 */
public final class DashboardView {

    private DashboardView() {
    }

    /** Everything the page needs, gathered once per request. */
    public record Model(ChronitConfig config,
                        List<Scheduler.Upcoming> upcoming,
                        Map<String, AccountStatus> accounts,
                        Map<String, JobExecution> runningJobs,
                        List<RunRecord> runs,
                        long runsVersion,
                        String clientVersion,
                        int clientProtocol,
                        boolean translationInstalled) {

        String headerMeta() {
            return "Minecraft " + clientVersion + " (protocol " + clientProtocol + ")";
        }

        Optional<Scheduler.Upcoming> upcomingFor(String jobId) {
            return upcoming.stream().filter(u -> u.jobId().equals(jobId)).findFirst();
        }

        Optional<RunRecord> lastRunFor(String jobId) {
            return runs.stream().filter(run -> run.jobId().equals(jobId)).findFirst();
        }

        List<AccountConfig> accountsNeedingLogin() {
            return config.accountsOrEmpty().stream()
                    .filter(account -> {
                        AccountStatus status = accounts.get(account.id());
                        return status != null && !status.isUsable();
                    })
                    .toList();
        }
    }

    public static String render(Model model, String assetVersion) {
        return Doc.page("chronit", model.headerMeta(), assetVersion, "./",
                List.of(attr("data-dashboard", "true"),
                        attr("data-runs-version", String.valueOf(model.runsVersion()))),
                main(cls("page"),
                        hero(model),
                        attention(model),
                        jobs(model),
                        accounts(model),
                        runs(model),
                        information(model)),
                cancelDialog());
    }

    // ---------------------------------------------------------------- hero

    /**
     * A sentence, not a set of fragments.
     *
     * <p>The next fire time is the one number worth reading at a glance, so it is the only thing
     * emphasised; the rest is ordinary prose around it.
     */
    private static Node hero(Model model) {
        Optional<Scheduler.Upcoming> next = model.upcoming().stream()
                .filter(u -> u.nextRun() != null)
                .findFirst();
        long running = model.upcoming().stream().filter(Scheduler.Upcoming::running).count();
        int jobCount = model.config().jobsOrEmpty().size();
        int accountCount = model.accounts().size();

        Node sentence;
        if (running > 0) {
            sentence = p(cls("hero__lead"),
                    span(cls("hero__strong"),
                            text(running == 1 ? "One job is running" : running + " jobs are running")),
                    text(" right now."));
        } else if (next.isPresent()) {
            Scheduler.Upcoming upcoming = next.get();
            sentence = p(cls("hero__lead"),
                    text("Next run "),
                    time(cls("hero__strong"),
                            attr("datetime", upcoming.nextRun().toInstant().toString()),
                            attr("data-relative", ""),
                            attr("data-hero-next", ""),
                            attr("title", upcoming.nextRun().toString()),
                            text("in " + upcoming.inText())),
                    text(", for " + upcoming.jobId() + "."));
        } else {
            sentence = p(cls("hero__lead"), text("Nothing is scheduled."));
        }

        return section(cls("hero"),
                h1(cls("hero__title"), text("Overview")),
                sentence,
                Ui.tags(
                        Ui.tag(count(jobCount, "job")),
                        Ui.tag(count(accountCount, "account")),
                        Ui.tag(count(model.config().serversOrEmpty().size(), "server"))));
    }

    /** "1 job" but "2 jobs" — the kind of detail whose absence is immediately noticeable. */
    private static String count(int amount, String noun) {
        return amount + " " + noun + (amount == 1 ? "" : "s");
    }

    /**
     * The one thing that might need a person.
     *
     * <p>Rendered only when it applies, which is what earns it this much room: a permanent tile
     * reading "0 need login" is noise, whereas an account whose refresh token has expired stops
     * every scheduled run until someone signs in.
     */
    private static Node attention(Model model) {
        List<AccountConfig> needing = model.accountsNeedingLogin();
        if (needing.isEmpty()) {
            return Node.empty();
        }
        AccountConfig first = needing.getFirst();
        AccountStatus status = model.accounts().get(first.id());

        String title = needing.size() == 1
                ? "Account “" + first.id() + "” needs signing in"
                : needing.size() + " accounts need signing in";

        return section(cls("notice notice--warn"), attr("role", "status"),
                span(cls("notice__icon"), Ui.icon("warn")),
                div(cls("notice__body"),
                        h2(cls("notice__title"), text(title)),
                        p(cls("notice__detail"), text(status == null ? "" : status.detail()))),
                first.authOrDefault() == AccountConfig.AuthMode.MICROSOFT
                        ? a(cls("btn btn--primary notice__action"),
                        href("accounts/" + urlSegment(first.id()) + "/login"),
                        text("Sign in"))
                        : Node.empty());
    }

    // ---------------------------------------------------------------- jobs

    private static Node jobs(Model model) {
        List<JobConfig> jobs = model.config().jobsOrEmpty();
        return section(cls("panel"),
                div(cls("panel__head"),
                        h2(cls("panel__title"), text("Jobs")),
                        span(cls("panel__note"),
                                text(model.translationInstalled()
                                        ? "protocol translation installed"
                                        : "native protocol only"))),
                jobs.isEmpty()
                        ? Ui.empty("No jobs configured.")
                        : div(cls("stack"), Node.each(jobs, job -> jobCard(model, job))));
    }

    private static Node jobCard(Model model, JobConfig job) {
        Optional<Scheduler.Upcoming> upcoming = model.upcomingFor(job.id());
        JobExecution execution = model.runningJobs().get(job.id());
        boolean running = execution != null;
        Optional<RunRecord> lastRun = model.lastRunFor(job.id());
        List<VisitConfig> visits = job.visits() == null ? List.of() : job.visits();

        Node runningChip = running
                ? Ui.runningChip()
                : span(attr("data-job-status", ""), attr("hidden", null), Ui.runningChip());
        Node disabledChip = job.isEnabled() ? Node.empty() : Ui.chip(Ui.Tone.WARN, "disabled");
        Node outcomeChip = lastRun.isEmpty()
                ? Node.empty()
                : lastRun.get().succeeded()
                ? Ui.chip(Ui.Tone.OK, "last run ok")
                : Ui.chip(Ui.Tone.BAD, "last run failed");

        String classes = "job"
                + (running ? " job--running" : "")
                + (job.isEnabled() ? "" : " job--disabled");

        return article(cls(classes), attr("data-job", job.id()),
                div(cls("job__head"),
                        div(cls("job__identity"),
                                h3(cls("job__name"), text(job.id())),
                                div(cls("job__chips"), runningChip, disabledChip, outcomeChip)),
                        Ui.data(
                                Ui.datum("Schedule", text(describeCron(job))),
                                Ui.datum("Timezone", code(text(job.zoneOrDefault().getId()))),
                                Ui.datum("Expression", code(text(job.cron()))),
                                Ui.datum(running ? "Running for" : "Next run",
                                        running ? elapsedTime(execution) : nextRunTime(upcoming))),
                        div(cls("job__actions"),
                                button(cls("btn btn--primary"), attr("type", "button"),
                                        attr("data-run-job", job.id()),
                                        attr("hidden", running ? "" : null),
                                        attr("data-when", "idle"),
                                        Ui.icon("play"), span(text("Run now"))),
                                button(cls("btn btn--danger"), attr("type", "button"),
                                        attr("data-cancel-job", job.id()),
                                        attr("hidden", running ? null : ""),
                                        attr("data-when", "running"),
                                        Ui.icon("stop"), span(text("Cancel"))))),
                visitDisclosure(job, visits, model));
    }

    /** The elapsed clock shown while a job is running, counting up from when it started. */
    private static Node elapsedTime(JobExecution execution) {
        return time(attr("datetime", execution.startedAt().toString()),
                attr("data-relative", ""),
                attr("data-elapsed", ""),
                attr("data-job-elapsed", ""),
                attr("title", execution.startedAt().toString()),
                text(Durations.format(execution.elapsed())));
    }

    private static Node nextRunTime(Optional<Scheduler.Upcoming> upcoming) {
        if (upcoming.isEmpty() || upcoming.get().nextRun() == null) {
            return span(cls("faint"), text("never"));
        }
        Scheduler.Upcoming next = upcoming.get();
        return time(attr("datetime", next.nextRun().toInstant().toString()),
                attr("data-relative", ""),
                attr("data-job-next", ""),
                attr("title", next.nextRun().toString()),
                text("in " + next.inText()));
    }

    private static String describeCron(JobConfig job) {
        try {
            return CronSchedule.parse(job.cron(), job.zoneOrDefault()).description();
        } catch (RuntimeException e) {
            return "unparseable schedule";
        }
    }

    /** The visit chain, folded away, with open state remembered per job. */
    private static Node visitDisclosure(JobConfig job, List<VisitConfig> visits, Model model) {
        if (visits.isEmpty()) {
            return Node.empty();
        }
        List<Node> rows = new ArrayList<>(visits.size());
        for (int i = 0; i < visits.size(); i++) {
            rows.add(visitRow(model, i + 1, visits.get(i)));
        }

        return details(cls("disclosure"), attr("data-remember", "job:" + job.id()),
                summary(cls("disclosure__summary"),
                        span(cls("disclosure__label"),
                                text(count(visits.size(), "visit"))),
                        Ui.tags(Node.each(visits.stream().map(VisitConfig::server).distinct().toList(),
                                Ui::tagMono)),
                        span(cls("disclosure__chevron"))),
                ul(cls("visits"), Node.fragment(rows.toArray(Node[]::new))));
    }

    private static Node visitRow(Model model, int index, VisitConfig visit) {
        ServerConfig server = model.config().server(visit.server()).orElse(null);
        SessionSettings settings = server == null ? null : SessionSettings.resolve(model.config(), server);

        RetryConfig retry = visit.onFail() != null
                ? visit.onFail().withFallback(model.config().effectiveDefaults().onFail())
                : model.config().effectiveDefaults().onFail();

        return li(cls("visit"),
                span(cls("visit__index"), text(String.valueOf(index))),
                div(cls("visit__body"),
                        div(cls("visit__identity"),
                                h3(cls("visit__server"), text(visit.server())),
                                Ui.tags(
                                        Ui.tagMono(server != null ? server.address() : "unknown"),
                                        Ui.tag("as " + visit.account()))),
                        Ui.data(
                                Ui.datum("Stay", Durations.format(visit.stayForOrDefault())),
                                Ui.datum("Protocol", server != null && server.protocol() != null
                                        ? server.protocol() : "auto"),
                                Ui.datum("Resource packs", settings != null
                                        ? String.valueOf(settings.resourcePack().mode()) : "—"),
                                Ui.datum("Ready when", settings != null ? readiness(settings) : "—"),
                                Ui.datum("Secure chat", settings != null
                                        ? String.valueOf(settings.secureChat()) : "—"),
                                Ui.datum("Retries", (retry.retries() == null ? 0 : retry.retries())
                                        + ", then " + String.valueOf(retry.then()).toLowerCase(
                                        java.util.Locale.ENGLISH).replace('_', ' ')),
                                Ui.datum("Gap after", Durations.format(visit.gapAfterOrDefault()))),
                        steps("On ready", visit.onReadyOrEmpty()),
                        steps("On leave", visit.onLeaveOrEmpty())));
    }

    private static String readiness(SessionSettings settings) {
        StringBuilder text = new StringBuilder();
        if (Boolean.TRUE.equals(settings.readyWhen().spawn())) {
            text.append("spawn");
        }
        if (settings.readyWhen().minChunks() != null && settings.readyWhen().minChunks() > 0) {
            text.append(text.isEmpty() ? "" : " + ").append(settings.readyWhen().minChunks()).append(" chunks");
        }
        if (settings.readyWhen().chat() != null) {
            text.append(text.isEmpty() ? "" : " + ").append("chat match");
        }
        Duration settle = settings.readyWhen().settle();
        if (settle != null && !settle.isZero()) {
            text.append(" + ").append(Durations.format(settle));
        }
        return text.toString();
    }

    private static Node steps(String label, List<ActionConfig> actions) {
        if (actions.isEmpty()) {
            return Node.empty();
        }
        return div(cls("sequence"),
                p(cls("sequence__label"), text(label)),
                ul(cls("steps"), Node.each(actions, DashboardView::step)));
    }

    /**
     * One configured step.
     *
     * <p>Payloads go through the redactor: a {@code login <password>} command is the single most
     * likely thing to be in one of these lists, and this page is the last place it should appear.
     */
    private static Node step(ActionConfig action) {
        String kind = switch (action.kind()) {
            case COMMAND -> "cmd";
            case CHAT -> "chat";
            case WAIT -> "wait";
            case CLICK -> "click";
            case CLOSE_SCREEN -> "close";
        };
        String body = switch (action.kind()) {
            case COMMAND -> "/" + Redactor.redact(action.command());
            case CHAT -> Redactor.redact(action.chat());
            case WAIT -> Durations.format(action.pause());
            case CLICK -> action.click().toSlotClick().describe();
            case CLOSE_SCREEN -> "the open menu";
        };

        String note = null;
        if (action.waitFor() != null) {
            note = "waits for " + action.waitFor().describe()
                    + ", up to " + Durations.format(action.waitFor().timeoutOrDefault())
                    + ", then " + String.valueOf(action.waitFor().onTimeoutOrDefault())
                    .toLowerCase(java.util.Locale.ENGLISH);
        } else if (action.delayAfter() != null && !action.delayAfter().isZero()) {
            note = "then waits " + Durations.format(action.delayAfter());
        }

        return li(cls("step"),
                span(cls("step__kind"), text(kind)),
                span(cls("step__body"), text(body)),
                note == null ? Node.empty() : span(cls("step__note"), text(note)));
    }

    // ---------------------------------------------------------------- accounts

    private static Node accounts(Model model) {
        List<AccountConfig> accounts = model.config().accountsOrEmpty();
        return section(cls("panel"),
                div(cls("panel__head"),
                        h2(cls("panel__title"), text("Accounts")),
                        span(cls("panel__note"),
                                text("Microsoft sessions refresh themselves in the background; a "
                                        + "sign-in is only needed after 90 days with the daemon off"))),
                accounts.isEmpty()
                        ? Ui.empty("No accounts configured.")
                        : div(cls("accounts"), Node.each(accounts, account -> accountCard(model, account))));
    }

    private static Node accountCard(Model model, AccountConfig account) {
        AccountStatus status = model.accounts().get(account.id());
        boolean usable = status == null || status.isUsable();
        boolean microsoft = account.authOrDefault() == AccountConfig.AuthMode.MICROSOFT;

        return article(cls("account" + (usable ? "" : " account--attention")),
                attr("data-account", account.id()),
                div(cls("account__head"),
                        h3(cls("account__name"), text(account.id())),
                        span(cls("chip " + (usable ? "chip--ok" : "chip--warn")),
                                attr("data-account-state", ""),
                                text(status == null ? "unknown" : status.state().toString()))),
                Ui.dataCompact(
                        Ui.datum("Username", code(text(status != null && status.username() != null
                                ? status.username() : "—"))),
                        Ui.datum("Type", microsoft ? "Microsoft" : "Offline"),
                        status != null && status.tokenExpiry() != null
                                ? Ui.datum("Token expires",
                                Ui.relativeTime(status.tokenExpiry(), status.tokenExpiry().toString()))
                                : Node.empty(),
                        // The one that decides whether anyone has to sit down at a browser. Every
                        // background refresh pushes it back out to ninety days, so on a running
                        // daemon it should never be seen to fall.
                        status != null && status.sessionExpiry() != null
                                ? Ui.datum("Sign-in due",
                                Ui.relativeTime(status.sessionExpiry(), status.sessionExpiry().toString()))
                                : Node.empty()),
                p(cls("account__note"), attr("data-account-detail", ""),
                        text(status == null ? "" : status.detail())),
                microsoft
                        ? a(cls("btn account__action"),
                        href("accounts/" + urlSegment(account.id()) + "/login"),
                        Ui.icon("key"), span(text(usable ? "Re-authorise" : "Sign in")))
                        : Node.empty());
    }

    // ---------------------------------------------------------------- runs

    private static Node runs(Model model) {
        return section(cls("panel"),
                div(cls("panel__head"),
                        h2(cls("panel__title"), text("Recent runs")),
                        span(cls("panel__note"), text("newest first"))),
                div(attr("data-runs", ""), Node.raw(RunsView.render(model.runs()))));
    }

    // ---------------------------------------------------------------- information

    /** Reference detail: true but rarely urgent, so it starts folded. */
    private static Node information(Model model) {
        return section(cls("panel"),
                details(cls("disclosure disclosure--standalone"), attr("data-remember", "info"),
                        summary(cls("disclosure__summary"),
                                span(cls("disclosure__label"), text("Information")),
                                span(cls("disclosure__chevron"))),
                        div(cls("disclosure__body"),
                                Ui.data(
                                        Ui.datum("Minecraft", text(model.clientVersion())),
                                        Ui.datum("Protocol", String.valueOf(model.clientProtocol())),
                                        Ui.datum("Protocol translation", model.translationInstalled()
                                                ? "Installed" : "Not installed"),
                                        Ui.datum("Jobs enabled", model.config().jobsOrEmpty().stream()
                                                .filter(JobConfig::isEnabled).count() + " of "
                                                + model.config().jobsOrEmpty().size()),
                                        Ui.datum("Servers",
                                                String.valueOf(model.config().serversOrEmpty().size())),
                                        Ui.datum("State directory",
                                                code(text(model.config().stateDirOrDefault().toString()))),
                                        Ui.datum("Pack cache", code(text(model.config()
                                                .stateDirOrDefault().resolve("packs").toString())))))));
    }

    // ---------------------------------------------------------------- dialog

    /**
     * The cancel confirmation.
     *
     * <p>A native {@code <dialog>} rather than a hand-rolled overlay, so focus trapping, the
     * backdrop and dismissing on escape are the browser's job rather than another thing to get
     * subtly wrong. One instance is reused; the script fills in which job it is about.
     */
    private static Node cancelDialog() {
        return dev.chronit.web.html.H.el("dialog", cls("sheet"), attr("data-cancel-dialog", ""),
                div(cls("sheet__body"),
                        span(cls("sheet__icon"), Ui.icon("stop")),
                        h2(cls("sheet__title"), text("Stop this job?")),
                        p(cls("sheet__detail"),
                                text("The client leaves the server it is on and the remaining "
                                        + "visits are skipped. The run is still recorded.")),
                        p(cls("sheet__subject"), attr("data-cancel-subject", ""))),
                div(cls("sheet__actions"),
                        button(cls("btn"), attr("type", "button"), attr("data-cancel-dismiss", ""),
                                text("Keep running")),
                        button(cls("btn btn--danger"), attr("type", "button"),
                                attr("data-cancel-confirm", ""),
                                Ui.icon("stop"), span(text("Stop job")))));
    }
}
