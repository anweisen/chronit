package net.anweisen.chronit.web.view;

import net.anweisen.chronit.core.auth.AccountStatus;
import net.anweisen.chronit.core.config.AccountConfig;
import net.anweisen.chronit.core.config.ActionConfig;
import net.anweisen.chronit.core.config.ChronitConfig;
import net.anweisen.chronit.core.config.JobConfig;
import net.anweisen.chronit.core.config.RetryConfig;
import net.anweisen.chronit.core.config.ServerConfig;
import net.anweisen.chronit.core.config.VisitConfig;
import net.anweisen.chronit.core.driver.SessionSettings;
import net.anweisen.chronit.core.run.CronSchedule;
import net.anweisen.chronit.core.run.JobExecution;
import net.anweisen.chronit.core.run.Scheduler;
import net.anweisen.chronit.core.state.RunRecord;
import net.anweisen.chronit.core.util.Durations;
import net.anweisen.chronit.core.util.Redactor;
import net.anweisen.chronit.web.html.Node;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static net.anweisen.chronit.web.html.H.a;
import static net.anweisen.chronit.web.html.H.article;
import static net.anweisen.chronit.web.html.H.attr;
import static net.anweisen.chronit.web.html.H.button;
import static net.anweisen.chronit.web.html.H.cls;
import static net.anweisen.chronit.web.html.H.code;
import static net.anweisen.chronit.web.html.H.details;
import static net.anweisen.chronit.web.html.H.div;
import static net.anweisen.chronit.web.html.H.el;
import static net.anweisen.chronit.web.html.H.h1;
import static net.anweisen.chronit.web.html.H.h2;
import static net.anweisen.chronit.web.html.H.h3;
import static net.anweisen.chronit.web.html.H.href;
import static net.anweisen.chronit.web.html.H.li;
import static net.anweisen.chronit.web.html.H.main;
import static net.anweisen.chronit.web.html.H.p;
import static net.anweisen.chronit.web.html.H.section;
import static net.anweisen.chronit.web.html.H.span;
import static net.anweisen.chronit.web.html.H.summary;
import static net.anweisen.chronit.web.html.H.text;
import static net.anweisen.chronit.web.html.H.time;
import static net.anweisen.chronit.web.html.H.ul;
import static net.anweisen.chronit.web.html.H.urlSegment;

/**
 * The dashboard.
 *
 * <p>Ordered by what an operator wants to know, in that order: is anything waiting on me, what is
 * running, what is coming, what happened.
 *
 * <p>The page is built from rules and rails rather than from boxes. Each region is a band with its
 * name set in the margin; each job, account and run is a row with a two-pixel coloured edge and a
 * hairline under it. Nothing is a card, because a screen of cards is a screen of identical
 * rectangles competing for the same attention, and the thing that actually needs attention — a job
 * that is running, an account that has expired — has nothing left to distinguish it with.
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
                List.of(attr("data-dashboard", "true")),
                main(cls("page"),
                        section(cls("overview"), attr("data-overview", ""), overview(model)),
                        jobs(model),
                        accounts(model),
                        runs(model),
                        information(model)),
                cancelDialog());
    }

    /**
     * The top of the page, re-rendered on the server and pushed whole.
     *
     * <p>It is prose and a number, and both change with the state of the whole daemon rather than
     * with any one row, so patching it field by field from the browser would mean writing the same
     * sentences twice in two languages.
     */
    public static String overviewFragment(Model model) {
        return overview(model).toHtml();
    }

    // ---------------------------------------------------------------- overview

    private static Node overview(Model model) {
        List<Scheduler.Upcoming> running = model.upcoming().stream()
                .filter(Scheduler.Upcoming::running)
                .toList();
        Optional<Scheduler.Upcoming> next = model.upcoming().stream()
                .filter(u -> u.nextRun() != null)
                .findFirst();

        Node headline;
        if (!running.isEmpty()) {
            JobExecution first = model.runningJobs().get(running.getFirst().jobId());
            headline = Node.fragment(
                    p(cls("overview__eyebrow is-live"),
                            span(cls("overview__beacon"), attr("aria-hidden", "true")),
                            text(running.size() == 1 ? "Running now" : running.size() + " running now")),
                    h1(cls("overview__value"),
                            first == null
                                    ? text(running.getFirst().jobId())
                                    : time(attr("datetime", first.startedAt().toString()),
                                    attr("data-relative", ""), attr("data-elapsed", ""),
                                    attr("title", first.startedAt().toString()),
                                    text(Durations.format(first.elapsed())))),
                    p(cls("overview__sub"),
                            text(running.size() == 1
                                    ? "elapsed on " + running.getFirst().jobId()
                                    : "elapsed on " + running.getFirst().jobId() + ", and "
                                    + count(running.size() - 1, "other job"))));
        } else if (next.isPresent()) {
            Scheduler.Upcoming upcoming = next.get();
            headline = Node.fragment(
                    p(cls("overview__eyebrow"), text("Next run")),
                    h1(cls("overview__value"),
                            time(attr("datetime", upcoming.nextRun().toInstant().toString()),
                                    attr("data-relative", ""),
                                    attr("data-bare", ""),
                                    attr("data-hero-next", ""),
                                    attr("title", upcoming.nextRun().toString()),
                                    text(upcoming.inText()))),
                    p(cls("overview__sub"), text("until " + upcoming.jobId())));
        } else {
            headline = Node.fragment(
                    p(cls("overview__eyebrow"), text("Idle")),
                    h1(cls("overview__value overview__value--quiet"), text("Nothing scheduled")),
                    p(cls("overview__sub"), text("No enabled job has a fire time.")));
        }

        long needingLogin = model.accountsNeedingLogin().size();

        return Node.fragment(
                div(cls("overview__lead"), headline),
                div(cls("overview__figures"),
                        Ui.figure(text(String.valueOf(model.config().jobsOrEmpty().size())), "jobs"),
                        Ui.figure(text(String.valueOf(model.config().serversOrEmpty().size())), "servers"),
                        Ui.figure(needingLogin == 0
                                        ? text(String.valueOf(model.accounts().size()))
                                        : span(cls("is-warn"), text(needingLogin + "/" + model.accounts().size())),
                                needingLogin == 0 ? "accounts" : "need signing in")),
                attention(model));
    }

    /** "1 job" but "2 jobs" — the kind of detail whose absence is immediately noticeable. */
    private static String count(int amount, String noun) {
        return amount + " " + noun + (amount == 1 ? "" : "s");
    }

    /**
     * Enum names are shouted constants in the source and ordinary words on the page.
     *
     * <p>{@code ABORT_JOB} shown verbatim is the configuration format leaking into the interface;
     * every value on this page reads as English for the same reason every label does.
     */
    private static String lower(Object value) {
        return String.valueOf(value).toLowerCase(Locale.ENGLISH).replace('_', ' ');
    }

    /**
     * The one thing that might need a person.
     *
     * <p>Rendered only when it applies, which is what earns it this much room: a permanent line
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
                Ui.rail(Ui.Tone.WARN),
                span(cls("alert__icon"), Ui.icon("warn")),
                div(cls("alert__body"),
                        p(cls("alert__title"), text(title)),
                        p(cls("alert__detail"), text(status == null ? "" : status.detail()))),
                first.authOrDefault() == AccountConfig.AuthMode.MICROSOFT
                        ? a(cls("link-action"),
                        href("accounts/" + urlSegment(first.id()) + "/login"),
                        span(text("Sign in")), Ui.icon("arrow"))
                        : Node.empty());
    }

    // ---------------------------------------------------------------- bands

    /**
     * A region of the page: its name in the margin, its contents alongside.
     *
     * <p>This is what replaced the panel. A heading in the left column reads as a label on the
     * whole band rather than as a title inside a container, which is the difference between a page
     * that is organised and a page that is subdivided.
     */
    private static Node band(String anchor, String title, String note, Node body) {
        return section(cls("band"), attr("id", anchor),
                div(cls("band__label"),
                        h2(cls("band__title"), text(title)),
                        note == null ? Node.empty() : p(cls("band__note"), text(note))),
                div(cls("band__body"), body));
    }

    // ---------------------------------------------------------------- jobs

    private static Node jobs(Model model) {
        List<JobConfig> jobs = model.config().jobsOrEmpty();
        return band("jobs", "Jobs",
                model.translationInstalled()
                        ? "Protocol translation is installed, so older servers are reachable."
                        : "Native protocol only. No translation layer is bundled.",
                jobs.isEmpty()
                        ? Ui.empty("No jobs configured.")
                        : div(cls("rows"), Node.each(jobs, job -> jobRow(model, job))));
    }

    private static Node jobRow(Model model, JobConfig job) {
        Optional<Scheduler.Upcoming> upcoming = model.upcomingFor(job.id());
        JobExecution execution = model.runningJobs().get(job.id());
        boolean running = execution != null;
        Optional<RunRecord> lastRun = model.lastRunFor(job.id());
        List<VisitConfig> visits = job.visits() == null ? List.of() : job.visits();

        Ui.Tone tone = running ? Ui.Tone.LIVE
                : !job.isEnabled() ? Ui.Tone.SKIP
                : lastRun.map(run -> Ui.toneOf(run.status())).orElse(Ui.Tone.NEUTRAL);

        return article(cls("row row--job" + (running ? " is-running" : "")
                        + (job.isEnabled() ? "" : " is-disabled")),
                attr("data-job", job.id()),
                span(cls("row__rail rail " + tone.className()), attr("data-job-rail", ""),
                        attr("aria-hidden", "true"), span(cls("rail__node"))),
                div(cls("row__main"),
                        // The head and the live line share one block with no gap between them:
                        // the reveal carries that space inside itself, so a closed one leaves
                        // nothing behind.
                        div(cls("row__lead"),
                        div(cls("row__head"),
                                div(cls("row__identity"),
                                        h3(cls("row__name"), text(job.id())),
                                        div(cls("row__state"), attr("data-job-status", ""),
                                                jobState(job, execution, lastRun))),
                                // One slot, two controls. They cross-fade rather than one vanishing
                                // and the other appearing, so the row keeps its width at the exact
                                // moment the pointer is over the button being replaced.
                                div(cls("row__actions"),
                                        div(cls("swap"),
                                                button(cls("btn btn--primary"
                                                                + (running ? " is-away" : "")),
                                                        attr("type", "button"),
                                                        attr("data-run-job", job.id()),
                                                        attr("data-when", "idle"),
                                                        Ui.icon("play"), span(text("Run now"))),
                                                button(cls("btn btn--stop"
                                                                + (running ? "" : " is-away")),
                                                        attr("type", "button"),
                                                        attr("data-cancel-job", job.id()),
                                                        attr("data-when", "running"),
                                                        Ui.icon("stop"), span(text("Stop")))))),
                                liveLine(execution, visits.size())),
                        Ui.facts(
                                // The one number worth reading first on a job, so it leads and it
                                // is the only value here set heavier than its neighbours.
                                Ui.factStrong(running ? "Running for" : "Next run",
                                        running ? elapsedTime(execution) : nextRunTime(upcoming)),
                                Ui.fact("Schedule", describeCron(job)),
                                Ui.factMono("Timezone", job.zoneOrDefault().getId()),
                                Ui.factMono("Expression", job.cron())),
                        visitDisclosure(job, visits, model)));
    }

    private static Node jobState(JobConfig job, JobExecution execution, Optional<RunRecord> lastRun) {
        if (execution != null) {
            return Ui.liveState(phaseLabel(execution));
        }
        if (!job.isEnabled()) {
            return Ui.state(Ui.Tone.SKIP, "disabled");
        }
        return lastRun.<Node>map(run -> Ui.state(run.status()))
                .orElseGet(() -> Ui.state(Ui.Tone.NEUTRAL, "never run"));
    }

    /**
     * The phase in the words an operator uses.
     *
     * <p>{@code CONFIGURATION} is where a join spends its time when a server pushes a resource
     * pack, and "configuration" says nothing about the wait; "loading resources" does.
     */
    public static String phaseLabel(JobExecution execution) {
        if (execution.isCancelled()) {
            return "stopping";
        }
        return switch (execution.phase()) {
            case CONNECTING -> "connecting";
            case LOGIN -> "authenticating";
            case CONFIGURATION -> "loading resources";
            case JOINING -> "entering the world";
            case IN_WORLD -> "in world";
            case LEAVING -> "leaving";
            case CLOSED -> "between visits";
        };
    }

    /**
     * The status mark for one job, as markup.
     *
     * <p>Handed to the browser inside the live snapshot rather than reconstructed there. The
     * alternative is a second copy of the status vocabulary written in JavaScript, which is how
     * two descriptions of the same thing start to disagree. It is only the status element that
     * gets replaced this way — it contains nothing focusable and nothing that can be open, which
     * is exactly why the rows around it are patched field by field instead.
     */
    public static String jobStatusHtml(JobConfig job, JobExecution execution, RunRecord lastRun) {
        return jobState(job, execution, Optional.ofNullable(lastRun)).toHtml();
    }

    public static String jobRailClass(JobConfig job, JobExecution execution, RunRecord lastRun) {
        if (execution != null) {
            return Ui.Tone.LIVE.className();
        }
        if (!job.isEnabled()) {
            return Ui.Tone.SKIP.className();
        }
        return lastRun == null ? Ui.Tone.NEUTRAL.className() : Ui.toneOf(lastRun.status()).className();
    }

    public static String accountStatusHtml(AccountStatus status) {
        return accountState(status).toHtml();
    }

    public static String accountRailClass(AccountStatus status) {
        return accountTone(status).className();
    }

    private static Ui.Tone accountTone(AccountStatus status) {
        return status == null || status.isUsable() ? Ui.Tone.OK : Ui.Tone.WARN;
    }

    private static Node accountState(AccountStatus status) {
        return Ui.state(accountTone(status), status == null
                ? "unknown"
                : status.state().toString().toLowerCase(Locale.ENGLISH).replace('_', ' '));
    }

    /**
     * What a running job is doing right now.
     *
     * <p>Always in the document, closed when idle, because the script fills it in from the live
     * stream and an element that has to be created before it can be updated is an element that is
     * sometimes not there when the update arrives.
     *
     * <p>Wrapped in a reveal so it opens its own height instead of appearing at full size in one
     * frame. The wrapper is what animates; the inner row is what gets clipped while it does.
     */
    private static Node liveLine(JobExecution execution, int visitCount) {
        boolean running = execution != null;
        int index = running ? execution.visitIndex() : 0;
        int total = running && execution.visitCount() > 0 ? execution.visitCount() : visitCount;

        return div(cls("reveal" + (running ? " is-shown" : "")), attr("data-job-live", ""),
                // The inner box exists only to be the clip: it carries no padding of its own,
                // because padding on it would keep a closed reveal a whole step tall.
                div(cls("reveal__inner"),
                        div(cls("live-line"),
                                Ui.progress(Math.max(index - 1, 0), Math.max(total, 1)),
                                div(cls("live-line__facts"),
                                        span(cls("live-line__step"), attr("data-live-step", ""),
                                                text(total > 0
                                                        ? "visit " + Math.max(index, 1) + " of " + total
                                                        : "")),
                                        span(cls("live-line__where"), attr("data-live-where", ""),
                                                text(running && execution.currentServer() != null
                                                        ? execution.currentServer() : ""))))));
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
                        span(cls("disclosure__chevron"), Ui.icon("chevron")),
                        span(cls("disclosure__label"), text(count(visits.size(), "visit"))),
                        span(cls("disclosure__trail"),
                                text(String.join(" → ", visits.stream()
                                        .map(VisitConfig::server).distinct().toList())))),
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
                        h3(cls("visit__server"), text(visit.server())),
                        Ui.facts(
                                Ui.factMono("Address", server != null ? server.address() : "unknown"),
                                Ui.factMono("Account", visit.account()),
                                Ui.fact("Stay", Durations.format(visit.stayForOrDefault())),
                                Ui.fact("Protocol", server != null && server.protocol() != null
                                        ? server.protocol() : "auto"),
                                Ui.fact("Resource packs", settings == null
                                        ? "—" : lower(settings.resourcePack().mode())),
                                Ui.fact("Ready when", settings != null ? readiness(settings) : "—"),
                                Ui.fact("Secure chat", settings == null
                                        ? "—" : lower(settings.secureChat())),
                                Ui.fact("Retries", (retry.retries() == null ? 0 : retry.retries())
                                        + ", then " + lower(retry.then())),
                                Ui.fact("Gap after", Durations.format(visit.gapAfterOrDefault()))),
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
                    .toLowerCase(Locale.ENGLISH);
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
        return band("accounts", "Accounts",
                "Microsoft sessions refresh themselves in the background. A sign-in is only needed "
                        + "after ninety days with the daemon off.",
                accounts.isEmpty()
                        ? Ui.empty("No accounts configured.")
                        : div(cls("rows"), Node.each(accounts, account -> accountRow(model, account))));
    }

    private static Node accountRow(Model model, AccountConfig account) {
        AccountStatus status = model.accounts().get(account.id());
        boolean usable = status == null || status.isUsable();
        boolean microsoft = account.authOrDefault() == AccountConfig.AuthMode.MICROSOFT;
        Ui.Tone tone = accountTone(status);

        return article(cls("row row--account" + (usable ? "" : " is-attention")),
                attr("data-account", account.id()),
                span(cls("row__rail rail " + tone.className()), attr("data-account-rail", ""),
                        attr("aria-hidden", "true"), span(cls("rail__node"))),
                div(cls("row__main"),
                        div(cls("row__head"),
                                div(cls("row__identity"),
                                        h3(cls("row__name"), text(account.id())),
                                        div(cls("row__state"), attr("data-account-status", ""),
                                                accountState(status))),
                                microsoft
                                        ? div(cls("row__actions"),
                                        a(cls("link-action"),
                                                href("accounts/" + urlSegment(account.id()) + "/login"),
                                                span(text(usable ? "Re-authorise" : "Sign in")),
                                                Ui.icon("arrow")))
                                        : Node.empty()),
                        Ui.facts(
                                Ui.fact("Username", span(cls("fact__value--mono"),
                                        attr("data-account-username", ""),
                                        text(status != null && status.username() != null
                                                ? status.username() : "—"))),
                                Ui.fact("Type", microsoft ? "Microsoft" : "Offline"),
                                status != null && status.tokenExpiry() != null
                                        ? Ui.fact("Token expires",
                                        Ui.relativeTime(status.tokenExpiry(), status.tokenExpiry().toString()))
                                        : Node.empty(),
                                // The one that decides whether anyone has to sit down at a browser.
                                // Every background refresh pushes it back out to ninety days, so on
                                // a running daemon it should never be seen to fall.
                                status != null && status.sessionExpiry() != null
                                        ? Ui.factStrong("Sign-in due",
                                        Ui.relativeTime(status.sessionExpiry(), status.sessionExpiry().toString()))
                                        : Node.empty()),
                        p(cls("row__note"), attr("data-account-detail", ""),
                                text(status == null ? "" : status.detail()))));
    }

    // ---------------------------------------------------------------- runs

    private static Node runs(Model model) {
        return band("runs", "History",
                "Newest first. Every run is kept, including the ones that were stopped.",
                div(attr("data-runs", ""), Node.raw(RunsView.render(model.runs()))));
    }

    // ---------------------------------------------------------------- information

    /** Reference detail: true but rarely urgent, so it starts folded. */
    private static Node information(Model model) {
        long enabled = model.config().jobsOrEmpty().stream().filter(JobConfig::isEnabled).count();
        long problems = model.runs().stream().filter(run -> run.status().isProblem()).count();

        return band("information", "System", null,
                details(cls("disclosure"), attr("data-remember", "info"),
                        summary(cls("disclosure__summary"),
                                span(cls("disclosure__chevron"), Ui.icon("chevron")),
                                span(cls("disclosure__label"), text("Build and paths")),
                                span(cls("disclosure__trail"),
                                        text("Minecraft " + model.clientVersion()))),
                        div(cls("disclosure__body"),
                                Ui.facts(
                                        Ui.fact("Minecraft", model.clientVersion()),
                                        Ui.factMono("Protocol", String.valueOf(model.clientProtocol())),
                                        model.translationInstalled()
                                                ? Ui.fact("Protocol translation", "Installed")
                                                : Ui.factQuiet("Protocol translation", "Not installed"),
                                        Ui.fact("Jobs enabled",
                                                enabled + " of " + model.config().jobsOrEmpty().size()),
                                        Ui.fact("Servers",
                                                String.valueOf(model.config().serversOrEmpty().size())),
                                        Ui.fact("Runs with a problem",
                                                problems + " of " + model.runs().size() + " kept"),
                                        Ui.factMono("State directory",
                                                model.config().stateDirOrDefault().toString()),
                                        Ui.factMono("Pack cache", model.config()
                                                .stateDirOrDefault().resolve("packs").toString())))));
    }

    // ---------------------------------------------------------------- dialog

    /**
     * The stop confirmation.
     *
     * <p>A native {@code <dialog>} rather than a hand-rolled overlay, so focus trapping, the
     * backdrop and dismissing on escape are the browser's job rather than another thing to get
     * subtly wrong. One instance is reused; the script fills in which job it is about.
     */
    private static Node cancelDialog() {
        return el("dialog", cls("sheet"), attr("data-cancel-dialog", ""),
                div(cls("sheet__body"),
                        Ui.rail(Ui.Tone.STOP),
                        h2(cls("sheet__title"), text("Stop this job?")),
                        p(cls("sheet__detail"),
                                text("The client leaves the server it is on, the remaining visits "
                                        + "are recorded as not reached, and the run is kept as "
                                        + "stopped rather than failed.")),
                        p(cls("sheet__subject"), attr("data-cancel-subject", ""))),
                div(cls("sheet__actions"),
                        button(cls("btn"), attr("type", "button"), attr("data-cancel-dismiss", ""),
                                text("Keep running")),
                        button(cls("btn btn--stop"), attr("type", "button"),
                                attr("data-cancel-confirm", ""),
                                Ui.icon("stop"), span(text("Stop job")))));
    }
}
