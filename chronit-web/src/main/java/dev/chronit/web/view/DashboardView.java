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
import static dev.chronit.web.html.H.dd;
import static dev.chronit.web.html.H.details;
import static dev.chronit.web.html.H.div;
import static dev.chronit.web.html.H.dl;
import static dev.chronit.web.html.H.dt;
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
 * <p>Ordered by what an operator actually wants to know, in that order: is anything waiting on me,
 * what is running, what is coming, what happened. The routine numbers read as one sentence rather
 * than a row of tiles — four equally-sized boxes give a next-run time the same weight as an account
 * that has stopped working, when only one of those needs a person.
 *
 * <p>Detail is present but folded away. A job's visit chain and the system information both sit
 * behind a disclosure, so the page opens as a short summary and expands to the whole configuration.
 */
public final class DashboardView {

    private DashboardView() {
    }

    /** Everything the page needs, gathered once per request. */
    public record Model(ChronitConfig config,
                        List<Scheduler.Upcoming> upcoming,
                        Map<String, AccountStatus> accounts,
                        List<RunRecord> runs,
                        long runsVersion,
                        String clientSummary,
                        boolean translationInstalled) {

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
        return Doc.page("chronit", model.clientSummary(), assetVersion, "./",
                List.of(attr("data-dashboard", "true"),
                        attr("data-runs-version", String.valueOf(model.runsVersion()))),
                main(cls("page"),
                        hero(model),
                        attention(model),
                        jobs(model),
                        accounts(model),
                        runs(model),
                        information(model)));
    }

    // ---------------------------------------------------------------- hero

    private static Node hero(Model model) {
        Optional<Scheduler.Upcoming> next = model.upcoming().stream()
                .filter(u -> u.nextRun() != null)
                .findFirst();
        long running = model.upcoming().stream().filter(Scheduler.Upcoming::running).count();
        int jobCount = model.config().jobsOrEmpty().size();

        List<Node> status = new ArrayList<>();
        if (running > 0) {
            status.add(span(text(running == 1 ? "1 job running" : running + " jobs running")));
        } else {
            next.ifPresentOrElse(
                    upcoming -> {
                        status.add(span(text("Next run")));
                        status.add(time(attr("datetime", upcoming.nextRun().toInstant().toString()),
                                attr("data-relative", ""),
                                attr("data-hero-next", ""),
                                attr("title", upcoming.nextRun().toString()),
                                text("in " + upcoming.inText())));
                        status.add(span(cls("hero__sep"), text("·")));
                        status.add(span(text(upcoming.jobId())));
                    },
                    () -> status.add(span(text("Nothing scheduled"))));
        }
        status.add(span(cls("hero__sep"), text("·")));
        status.add(span(text(jobCount == 1 ? "1 job" : jobCount + " jobs")));
        status.add(span(cls("hero__sep"), text("·")));
        status.add(span(text(model.accounts().size() == 1
                ? "1 account" : model.accounts().size() + " accounts")));

        // Emphasis lands on the time itself; everything around it is supporting text.
        List<Node> emphasised = status.stream()
                .map(node -> node)
                .toList();

        return section(cls("hero"),
                h1(cls("hero__title"), text("Overview")),
                p(cls("hero__status"), Node.fragment(emphasised.toArray(Node[]::new))));
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

        return div(cls("alert"), attr("role", "status"),
                div(cls("alert__icon"), Ui.icon("warn")),
                div(cls("alert__body"),
                        p(cls("alert__title"), text(title)),
                        p(cls("alert__detail"),
                                text(status == null ? "" : status.detail()))),
                first.authOrDefault() == AccountConfig.AuthMode.MICROSOFT
                        ? a(cls("btn btn--primary"),
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
        boolean running = upcoming.map(Scheduler.Upcoming::running).orElse(false);
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
                        div(
                                h3(cls("job__title"), text(job.id()),
                                        runningChip, disabledChip, outcomeChip),
                                p(cls("job__schedule"),
                                        code(text(job.cron())),
                                        span(cls("hero__sep"), text("·")),
                                        span(text(describeCron(job))),
                                        span(cls("hero__sep"), text("·")),
                                        span(cls("mono faint"), text(job.zoneOrDefault().getId())))),
                        div(cls("job__next"),
                                span(cls("faint"), text("Next")),
                                upcoming.<Node>map(DashboardView::nextRunTime)
                                        .orElseGet(() -> span(cls("faint"), text("—")))),
                        div(cls("job__actions"),
                                button(cls("btn btn--primary"), attr("type", "button"),
                                        attr("data-run-job", job.id()),
                                        Ui.icon("play"),
                                        text("Run now")))),
                visitDisclosure(model, job, visits));
    }

    /**
     * The visit chain, folded away.
     *
     * <p>Open state is remembered per job, so someone watching a particular job does not have to
     * re-open it after every poll that swaps content.
     */
    private static Node visitDisclosure(Model model, JobConfig job, List<VisitConfig> visits) {
        if (visits.isEmpty()) {
            return Node.empty();
        }
        String servers = visits.stream().map(VisitConfig::server).distinct()
                .reduce((a, b) -> a + ", " + b).orElse("");
        String summaryText = (visits.size() == 1 ? "1 visit" : visits.size() + " visits")
                + " · " + servers;

        List<Node> rows = new ArrayList<>(visits.size());
        for (int i = 0; i < visits.size(); i++) {
            rows.add(visitRow(model, i + 1, visits.get(i)));
        }

        return details(cls("disclosure"), attr("data-remember", "job:" + job.id()),
                summary(cls("disclosure__summary"),
                        span(text(summaryText)),
                        span(cls("disclosure__chevron"))),
                ul(cls("visits"), Node.fragment(rows.toArray(Node[]::new))));
    }

    private static Node nextRunTime(Scheduler.Upcoming upcoming) {
        if (upcoming.nextRun() == null) {
            return span(cls("faint"), text("never"));
        }
        return time(attr("datetime", upcoming.nextRun().toInstant().toString()),
                attr("data-relative", ""),
                attr("data-job-next", ""),
                attr("title", upcoming.nextRun().toString()),
                text("in " + upcoming.inText()));
    }

    private static String describeCron(JobConfig job) {
        try {
            return CronSchedule.parse(job.cron(), job.zoneOrDefault()).description();
        } catch (RuntimeException e) {
            return "unparseable schedule";
        }
    }

    private static Node visitRow(Model model, int index, VisitConfig visit) {
        ServerConfig server = model.config().server(visit.server()).orElse(null);
        SessionSettings settings = server == null ? null : SessionSettings.resolve(model.config(), server);

        RetryConfig retry = visit.onFail() != null
                ? visit.onFail().withFallback(model.config().effectiveDefaults().onFail())
                : model.config().effectiveDefaults().onFail();

        return li(cls("visit"),
                div(cls("visit__marker"), text(String.valueOf(index))),
                div(cls("visit__body"),
                        div(cls("visit__title"),
                                span(cls("visit__server"), text(visit.server())),
                                span(cls("mono faint"),
                                        text(server != null ? server.address() : "unknown server")),
                                Ui.chip(Ui.Tone.NEUTRAL, "as " + visit.account())),
                        dl(cls("visit__meta"),
                                metaItem("Stay", Durations.format(visit.stayForOrDefault())),
                                metaItem("Protocol", server != null && server.protocol() != null
                                        ? server.protocol() : "auto"),
                                metaItem("Packs", settings != null
                                        ? String.valueOf(settings.resourcePack().mode()) : "—"),
                                metaItem("Ready when", settings != null ? readiness(settings) : "—"),
                                metaItem("Secure chat", settings != null
                                        ? String.valueOf(settings.secureChat()) : "—"),
                                metaItem("Retries", (retry.retries() == null ? 0 : retry.retries())
                                        + " · then " + retry.then()),
                                metaItem("Gap after", Durations.format(visit.gapAfterOrDefault()))),
                        steps("On ready", visit.onReadyOrEmpty()),
                        steps("On leave", visit.onLeaveOrEmpty())));
    }

    private static Node metaItem(String label, String value) {
        return div(dt(text(label)), dd(text(value)));
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
        return div(
                p(cls("steps__label"), text(label)),
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
                    + " (" + Durations.format(action.waitFor().timeoutOrDefault())
                    + ", " + action.waitFor().onTimeoutOrDefault() + ")";
        } else if (action.delayAfter() != null && !action.delayAfter().isZero()) {
            note = "then wait " + Durations.format(action.delayAfter());
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
                                text("Microsoft sessions refresh themselves; a sign-in is needed "
                                        + "about every 90 days"))),
                accounts.isEmpty()
                        ? Ui.empty("No accounts configured.")
                        : div(cls("accounts"), Node.each(accounts, account -> accountCard(model, account))));
    }

    private static Node accountCard(Model model, AccountConfig account) {
        AccountStatus status = model.accounts().get(account.id());
        boolean usable = status == null || status.isUsable();
        boolean microsoft = account.authOrDefault() == AccountConfig.AuthMode.MICROSOFT;

        return div(cls("account" + (usable ? "" : " account--attention")),
                attr("data-account", account.id()),
                div(cls("account__head"),
                        p(cls("account__id"), text(account.id())),
                        span(cls("chip " + (usable ? "chip--ok" : "chip--warn")),
                                attr("data-account-state", ""),
                                text(status == null ? "unknown" : status.state().toString()))),
                p(cls("account__line"),
                        span(cls("mono"), text(status != null && status.username() != null
                                ? status.username() : "—")),
                        text(" · "),
                        span(text(microsoft ? "Microsoft" : "offline"))),
                p(cls("account__line faint"), attr("data-account-detail", ""),
                        text(status == null ? "" : status.detail())),
                status != null && status.tokenExpiry() != null
                        ? p(cls("account__line faint"),
                        text("token expires "),
                        Ui.relativeTime(status.tokenExpiry(), status.tokenExpiry().toString()))
                        : Node.empty(),
                microsoft
                        ? div(cls("account__actions"),
                        a(cls("btn"), href("accounts/" + urlSegment(account.id()) + "/login"),
                                text(usable ? "Re-authorise" : "Sign in")))
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
                details(cls("info"), attr("data-remember", "info"),
                        summary(cls("disclosure__summary"),
                                span(text("Information")),
                                span(cls("disclosure__chevron"))),
                        dl(cls("info__body"),
                                infoItem("Client", model.clientSummary()),
                                infoItem("Protocol translation", model.translationInstalled()
                                        ? "installed — older servers reachable"
                                        : "not installed — native protocol only"),
                                infoItem("State directory", model.config().stateDirOrDefault().toString()),
                                infoItem("Servers", String.valueOf(model.config().serversOrEmpty().size())),
                                infoItem("Scheduler", model.config().jobsOrEmpty().stream()
                                        .filter(JobConfig::isEnabled).count() + " enabled of "
                                        + model.config().jobsOrEmpty().size()),
                                infoItem("Resource pack cache",
                                        model.config().stateDirOrDefault().resolve("packs").toString()))));
    }

    private static Node infoItem(String label, String value) {
        return div(cls("info__item"), dt(text(label)), dd(text(value)));
    }
}
