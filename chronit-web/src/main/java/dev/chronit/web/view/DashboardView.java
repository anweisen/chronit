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
import dev.chronit.core.run.Scheduler;
import dev.chronit.core.state.RunRecord;
import dev.chronit.core.util.Durations;
import dev.chronit.core.util.Redactor;
import dev.chronit.web.html.Element;
import dev.chronit.web.html.Node;

import java.time.Duration;
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
import static dev.chronit.web.html.H.div;
import static dev.chronit.web.html.H.dl;
import static dev.chronit.web.html.H.dt;
import static dev.chronit.web.html.H.h2;
import static dev.chronit.web.html.H.h3;
import static dev.chronit.web.html.H.href;
import static dev.chronit.web.html.H.li;
import static dev.chronit.web.html.H.main;
import static dev.chronit.web.html.H.p;
import static dev.chronit.web.html.H.section;
import static dev.chronit.web.html.H.span;
import static dev.chronit.web.html.H.text;
import static dev.chronit.web.html.H.time;
import static dev.chronit.web.html.H.ul;
import static dev.chronit.web.html.H.urlSegment;

/**
 * The dashboard.
 *
 * <p>Ordered by what an operator actually wants to know: is anything wrong, what is running, what
 * is coming, and what happened. The job cards deliberately show the whole visit chain — server,
 * account, how long it stays, the retry policy and every configured step — because that is the part
 * of the configuration you cannot check by looking at the running process.
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

        long accountsNeedingLogin() {
            return accounts.values().stream().filter(status -> !status.isUsable()).count();
        }
    }

    public static String render(Model model, String assetVersion) {
        return Doc.page("chronit", model.clientSummary(), assetVersion, "./",
                List.of(attr("data-dashboard", "true"),
                        attr("data-runs-version", String.valueOf(model.runsVersion()))),
                main(cls("page"),
                        stats(model),
                        jobs(model),
                        accounts(model),
                        runs(model)));
    }

    // ---------------------------------------------------------------- stats

    private static Node stats(Model model) {
        Optional<Scheduler.Upcoming> next = model.upcoming().stream()
                .filter(u -> u.nextRun() != null)
                .findFirst();

        long running = model.upcoming().stream().filter(Scheduler.Upcoming::running).count();
        long needingLogin = model.accountsNeedingLogin();
        Optional<RunRecord> lastRun = model.runs().stream().findFirst();

        // Rendered as a live timestamp so the headline figure agrees with the job card and stays
        // true between polls instead of freezing at whatever it was when the page was built.
        Element nextTile = next
                .map(upcoming -> Ui.stat("Next run",
                        Ui.relativeTime(upcoming.nextRun(), "in " + upcoming.inText()),
                        upcoming.jobId(), Ui.Tone.NEUTRAL))
                .orElseGet(() -> Ui.stat("Next run", "—", "nothing scheduled", Ui.Tone.NEUTRAL));

        return section(cls("stats"),
                nextTile,
                Ui.stat("Jobs",
                        String.valueOf(model.config().jobsOrEmpty().size()),
                        running > 0 ? running + " running now" : "idle",
                        running > 0 ? Ui.Tone.OK : Ui.Tone.NEUTRAL),
                div(cls(needingLogin > 0 ? "stat stat--warn" : "stat"),
                        p(cls("stat__label"), text("Accounts")),
                        p(cls("stat__value"),
                                span(attr("data-stat-attention", ""), text(String.valueOf(needingLogin))),
                                text(needingLogin == 1 ? " needs login" : " need login")),
                        p(cls("stat__sub"), text(model.accounts().size() + " configured"))),
                lastRun
                        .map(run -> Ui.stat("Last run",
                                run.succeeded() ? "Succeeded" : "Failed",
                                run.jobId() + " · " + Durations.format(run.duration()),
                                run.succeeded() ? Ui.Tone.OK : Ui.Tone.BAD))
                        .orElseGet(() -> Ui.stat("Last run", "—", "no history yet", Ui.Tone.NEUTRAL)));
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

        String classes = "job"
                + (running ? " job--running" : "")
                + (job.isEnabled() ? "" : " job--disabled");

        // The running chip is always in the markup, hidden when idle, so the poll can reveal it
        // without having to build an element client-side.
        Node runningChip = running
                ? Ui.runningChip()
                : span(attr("data-job-status", ""), attr("hidden", null), Ui.runningChip());
        Node disabledChip = job.isEnabled() ? Node.empty() : Ui.chip(Ui.Tone.WARN, "disabled");
        Node outcomeChip = lastRun.isEmpty()
                ? Node.empty()
                : lastRun.get().succeeded()
                ? Ui.chip(Ui.Tone.OK, "last run ok")
                : Ui.chip(Ui.Tone.BAD, "last run failed");

        return article(cls(classes), attr("data-job", job.id()),
                div(cls("job__head"),
                        div(
                                h3(cls("job__title"),
                                        text(job.id()),
                                        runningChip,
                                        disabledChip,
                                        outcomeChip),
                                p(cls("job__schedule"),
                                        code(text(job.cron())),
                                        span(cls("faint"), text("·")),
                                        span(text(describeCron(job))),
                                        span(cls("faint"), text("·")),
                                        span(cls("mono faint"), text(job.zoneOrDefault().getId())),
                                        span(cls("faint"), text("·")),
                                        span(cls("faint"),
                                                text("overlap " + job.overlapOrDefault()
                                                        + " · misfire " + job.misfireOrDefault())))),
                        div(cls("job__next"),
                                span(text("Next")),
                                upcoming.<Node>map(DashboardView::nextRunTime)
                                        .orElseGet(() -> span(cls("faint"), text("—")))),
                        div(cls("job__actions"),
                                button(cls("btn btn--primary"), attr("type", "button"),
                                        attr("data-run-job", job.id()),
                                        Ui.icon("play"),
                                        text("Run now")))),
                visits(model, job));
    }

    /**
     * The next fire time, tagged so the script can retarget it after a run without a page reload,
     * and rendered relative so it stays current between polls.
     */
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
            return dev.chronit.core.run.CronSchedule.parse(job.cron(), job.zoneOrDefault()).description();
        } catch (RuntimeException e) {
            return "unparseable schedule";
        }
    }

    private static Node visits(Model model, JobConfig job) {
        List<VisitConfig> visits = job.visits() == null ? List.of() : job.visits();
        if (visits.isEmpty()) {
            return Node.empty();
        }
        java.util.List<Node> rows = new java.util.ArrayList<>(visits.size());
        for (int i = 0; i < visits.size(); i++) {
            rows.add(visitRow(model, i + 1, visits.get(i)));
        }
        return ul(cls("visits"), Node.fragment(rows.toArray(Node[]::new)));
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
                                metaItem("Ready", settings != null ? readiness(settings) : "—"),
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
                p(cls("steps__label"), attr("style", "margin-top:.7rem"), text(label)),
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
                                text("Microsoft sessions refresh automatically; a login is needed "
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
                p(cls("account__detail"),
                        span(cls("mono"), text(status != null && status.username() != null
                                ? status.username() : "—")),
                        text(" · "),
                        span(attr("data-account-detail", ""),
                                text(status == null ? "" : status.detail()))),
                status != null && status.tokenExpiry() != null
                        ? p(cls("account__detail faint"),
                        text("token expires "),
                        Ui.relativeTime(status.tokenExpiry(), status.tokenExpiry().toString()))
                        : Node.empty(),
                microsoft
                        ? a(cls("btn"), href("accounts/" + urlSegment(account.id()) + "/login"),
                        text(usable ? "Re-authorise" : "Sign in"))
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
}
