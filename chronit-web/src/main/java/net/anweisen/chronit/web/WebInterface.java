package net.anweisen.chronit.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.anweisen.chronit.core.auth.AccountManager;
import net.anweisen.chronit.core.auth.AccountStatus;
import net.anweisen.chronit.core.config.AccountConfig;
import net.anweisen.chronit.core.config.ChronitConfig;
import net.anweisen.chronit.core.config.JobConfig;
import net.anweisen.chronit.core.config.WebConfig;
import net.anweisen.chronit.core.run.JobExecution;
import net.anweisen.chronit.core.run.Orchestrator;
import net.anweisen.chronit.core.run.Scheduler;
import net.anweisen.chronit.core.state.RunRecord;
import net.anweisen.chronit.web.view.DashboardView;
import net.anweisen.chronit.web.view.LoginView;
import net.anweisen.chronit.web.view.RunsView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * A small status and sign-in interface.
 *
 * <p>It exists mainly for one awkward moment: the Microsoft refresh token expires roughly every
 * ninety days, and re-authorising means reading a short-lived code out of a container's logs and
 * typing it before it expires. A page with a button is a better answer to that than
 * {@code docker logs -f}.
 *
 * <p>Built on the JDK's own HTTP server — the whole interface is a few server-rendered pages, a
 * small JSON endpoint and one event stream, and an embedded servlet container would add megabytes
 * to the image for nothing. Pages are rendered through a typed HTML builder rather than string
 * concatenation, because everything shown here (server names, kick reasons, menu titles) comes from
 * outside the process and a forgotten escape would be an injection hole.
 *
 * <p>The page does not poll. {@link LiveFeed} pushes over server-sent events, driven by the
 * orchestrator itself, so a job reaching the world or being stopped shows up in the moment it
 * happens rather than up to six seconds later.
 */
public final class WebInterface {

    private static final Logger log = LoggerFactory.getLogger(WebInterface.class);

    private static final int RECENT_RUNS = 25;

    /**
     * Account status reads parse a token file from disk, so a short cache keeps the sweep below
     * from touching the filesystem once per account per second.
     */
    private static final Duration STATUS_CACHE_TTL = Duration.ofSeconds(5);

    /**
     * Everything a run does announces itself, so this exists only for the things that change
     * without anyone telling us: a token refreshed in the background, a fire time passing. It runs
     * only while someone is actually watching, and publishes only when the snapshot differs.
     */
    private static final Duration SWEEP = Duration.ofSeconds(5);

    private static final String SESSION_COOKIE = "chronit_session";

    private final ChronitConfig config;
    private final WebConfig webConfig;
    /** Supplied by the app module, which is the one that knows the driver. */
    private final String clientVersion;
    private final int clientProtocol;
    private final Orchestrator orchestrator;
    private final Scheduler scheduler;
    private final AccountManager accounts;
    private final LoginFlows logins;
    private final ObjectMapper json = new ObjectMapper();

    private final Assets assets = new Assets();
    private final LiveFeed live = new LiveFeed();
    private volatile Map<String, AccountStatus> cachedStatuses = Map.of();
    private volatile Instant statusesFetchedAt = Instant.EPOCH;
    /** The last thing published, so the sweep can stay quiet when nothing moved. */
    private volatile String lastPublishedState = "";
    private volatile long lastPublishedRunsVersion = Long.MIN_VALUE;

    private HttpServer server;
    private ScheduledExecutorService sweeper;
    private AutoCloseable orchestratorWatch;

    public WebInterface(ChronitConfig config,
                        Orchestrator orchestrator,
                        Scheduler scheduler,
                        AccountManager accounts,
                        String clientVersion,
                        int clientProtocol) {
        this.config = config;
        this.webConfig = config.webOrDisabled();
        this.clientVersion = clientVersion;
        this.clientProtocol = clientProtocol;
        this.orchestrator = orchestrator;
        this.scheduler = scheduler;
        this.accounts = accounts;
        this.logins = new LoginFlows(accounts, this::publishLogin);
    }

    public void start() throws IOException {
        server = HttpServer.create(
                new InetSocketAddress(webConfig.bindOrDefault(), webConfig.portOrDefault()), 0);

        // Each live stream occupies a thread for as long as the tab stays open, so a fixed pool of
        // four would be exhausted by four browsers and stop serving pages entirely. The pool grows
        // instead, and the feed itself caps how many streams it will accept.
        ThreadPoolExecutor pool = new ThreadPoolExecutor(4, 64, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(), runnable -> {
            Thread thread = new Thread(runnable, "chronit-web");
            thread.setDaemon(true);
            return thread;
        });
        pool.allowCoreThreadTimeOut(true);
        server.setExecutor(pool);

        // Unauthenticated and reachable from anywhere, so it must never reveal anything.
        server.createContext("/healthz", exchange -> {
            respond(exchange, 200, "text/plain", "ok".getBytes(StandardCharsets.UTF_8), null);
            exchange.close();
        });

        server.createContext("/", this::route);
        server.start();

        orchestratorWatch = orchestrator.watch(this::publishState);
        sweeper = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "chronit-web-sweep");
            thread.setDaemon(true);
            return thread;
        });
        sweeper.scheduleWithFixedDelay(this::sweep, SWEEP.toSeconds(), SWEEP.toSeconds(), TimeUnit.SECONDS);

        log.info("Web interface on http://{}:{}{}",
                webConfig.bindOrDefault(), webConfig.portOrDefault(),
                requiresToken() ? " (token required)" : "");
    }

    public void stop() {
        if (sweeper != null) {
            sweeper.shutdownNow();
        }
        if (orchestratorWatch != null) {
            try {
                orchestratorWatch.close();
            } catch (Exception e) {
                log.debug("Could not detach the run listener: {}", e.toString());
            }
        }
        // Ends the open streams before the server waits on them.
        live.close();
        if (server != null) {
            server.stop(1);
        }
    }

    // ------------------------------------------------------------------ routing

    private void route(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        try {
            // Assets carry no information and are needed by the sign-in page itself.
            if (path.startsWith("/assets/")) {
                serveAsset(exchange, path.substring("/assets/".length()));
                return;
            }
            if (path.equals("/session") && method.equals("POST")) {
                exchangeTokenForCookie(exchange);
                return;
            }
            if (!isAuthorised(exchange)) {
                denyOrPrompt(exchange);
                return;
            }

            if (path.equals("/") || path.isEmpty()) {
                html(exchange, 200, DashboardView.render(dashboardModel(), assets.version()));
                return;
            }
            // The live channel. Authorised by the same cookie every other request carries, checked
            // above before the stream is opened rather than trusted for its lifetime.
            if (path.equals("/events")) {
                serveEvents(exchange);
                return;
            }
            if (path.equals("/fragments/runs")) {
                html(exchange, 200, RunsView.render(recentRuns()));
                return;
            }
            if (path.equals("/api/state")) {
                json(exchange, 200, stateJson());
                return;
            }
            if (path.startsWith("/api/jobs/") && path.endsWith("/run") && method.equals("POST")) {
                runJob(exchange, decode(path.substring("/api/jobs/".length(), path.length() - "/run".length())));
                return;
            }
            if (path.startsWith("/api/jobs/") && path.endsWith("/cancel") && method.equals("POST")) {
                cancelJob(exchange,
                        decode(path.substring("/api/jobs/".length(), path.length() - "/cancel".length())));
                return;
            }
            if (path.startsWith("/accounts/") && path.endsWith("/login")) {
                String accountId = decode(path.substring("/accounts/".length(), path.length() - "/login".length()));
                if (method.equals("GET")) {
                    loginPage(exchange, accountId);
                } else {
                    respond(exchange, 405, "text/plain", "Use the API endpoint".getBytes(StandardCharsets.UTF_8), null);
                }
                return;
            }
            if (path.startsWith("/api/accounts/") && path.endsWith("/login")) {
                String accountId = decode(
                        path.substring("/api/accounts/".length(), path.length() - "/login".length()));
                loginApi(exchange, accountId, method);
                return;
            }
            respond(exchange, 404, "text/plain", "Not found".getBytes(StandardCharsets.UTF_8), null);
        } catch (RuntimeException e) {
            log.warn("Request {} failed: {}", exchange.getRequestURI(), e.toString(), e);
            respond(exchange, 500, "text/plain", "Internal error".getBytes(StandardCharsets.UTF_8), null);
        } finally {
            exchange.close();
        }
    }

    // ------------------------------------------------------------------ auth

    private boolean requiresToken() {
        return webConfig.token() != null && !webConfig.token().isBlank();
    }

    /**
     * Accepts a bearer header for tooling, or the session cookie for a browser.
     *
     * <p>Compared in constant time. The token is deliberately not accepted as a query parameter:
     * that puts it in browser history, in any referrer the page emits, and in access logs. A
     * browser gets it once through a form post and keeps it in an HttpOnly cookie afterwards —
     * which is also what authenticates the event stream, since {@code EventSource} cannot set
     * headers but does send cookies on a same-origin request.
     */
    private boolean isAuthorised(HttpExchange exchange) {
        if (!requiresToken()) {
            return true;
        }
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        if (header != null && header.startsWith("Bearer ") && matchesToken(header.substring(7))) {
            return true;
        }
        return cookie(exchange, SESSION_COOKIE).filter(this::matchesToken).isPresent();
    }

    private boolean matchesToken(String presented) {
        return MessageDigest.isEqual(
                presented.getBytes(StandardCharsets.UTF_8),
                webConfig.token().getBytes(StandardCharsets.UTF_8));
    }

    private void denyOrPrompt(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        boolean wantsJson = path.startsWith("/api/") || path.equals("/events")
                || "application/json".equals(exchange.getRequestHeaders().getFirst("Accept"));
        if (wantsJson) {
            json(exchange, 401, "{\"error\":\"unauthorised\"}");
            return;
        }
        html(exchange, 401, LoginView.tokenGate(assets.version(), false));
    }

    private void exchangeTokenForCookie(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String presented = formField(body, "token").orElse("");

        if (!requiresToken() || matchesToken(presented)) {
            // Strict same-site and HttpOnly: the dashboard has action endpoints, so the cookie must
            // not ride along with a cross-site request or be readable by script.
            exchange.getResponseHeaders().add("Set-Cookie",
                    SESSION_COOKIE + "=" + presented + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=604800");
            redirect(exchange, "/");
            return;
        }
        html(exchange, 401, LoginView.tokenGate(assets.version(), true));
    }

    // ------------------------------------------------------------------ live

    /**
     * Opens a stream and hands the caller's thread to it.
     *
     * <p>The first frames carry the current picture, so a page that has just reconnected is
     * correct immediately rather than waiting for the next thing to happen.
     */
    private void serveEvents(HttpExchange exchange) throws IOException {
        Map<String, String> initial = new LinkedHashMap<>();
        initial.put("state", stateJson());
        initial.put("overview", DashboardView.overviewFragment(dashboardModel()));
        initial.put("runs", RunsView.render(recentRuns()));
        String account = queryParam(exchange, "login");
        if (account != null) {
            initial.put("login", loginJson(account));
        }
        live.serve(exchange, initial);
    }

    /** Called by the orchestrator on every observable change, and by the sweep. */
    private void publishState() {
        try {
            String state = stateJson();
            // The snapshot carries a timestamp, so compare what actually matters instead.
            if (!sameExceptTime(state, lastPublishedState)) {
                lastPublishedState = state;
                live.publish("state", state);
                // The summary at the top is prose about the whole daemon, so it is rendered here
                // rather than reassembled in the browser from the snapshot above.
                live.publish("overview", DashboardView.overviewFragment(dashboardModel()));
            }
            long runsVersion = runsVersion();
            if (runsVersion != lastPublishedRunsVersion) {
                lastPublishedRunsVersion = runsVersion;
                live.publish("runs", RunsView.render(recentRuns()));
            }
        } catch (RuntimeException e) {
            log.warn("Could not publish a live update: {}", e.toString());
        }
    }

    private void publishLogin(String accountId) {
        statusesFetchedAt = Instant.EPOCH;
        live.publish("login", loginJson(accountId));
        publishState();
    }

    private void sweep() {
        if (live.subscriberCount() == 0) {
            return;
        }
        publishState();
    }

    /**
     * Compares two snapshots ignoring the {@code now} field.
     *
     * <p>Without this the sweep would publish every five seconds forever, because the timestamp
     * always differs — which is polling again, just with the roles reversed.
     */
    private static boolean sameExceptTime(String a, String b) {
        return stripNow(a).equals(stripNow(b));
    }

    private static String stripNow(String snapshot) {
        int start = snapshot.indexOf("\"now\":\"");
        if (start < 0) {
            return snapshot;
        }
        int end = snapshot.indexOf('"', start + 7);
        return end < 0 ? snapshot : snapshot.substring(0, start) + snapshot.substring(end + 1);
    }

    // ------------------------------------------------------------------ pages

    private DashboardView.Model dashboardModel() {
        return new DashboardView.Model(
                config,
                scheduler.upcoming(),
                accountStatuses(),
                orchestrator.runningJobs(),
                recentRuns(),
                runsVersion(),
                clientVersion,
                clientProtocol,
                orchestrator.protocols().hasTranslation());
    }

    /** A parenthetical, which is ordinary English, rather than two facts glued with a divider. */
    private String headerMeta() {
        return "Minecraft " + clientVersion + " (protocol " + clientProtocol + ")";
    }

    private List<RunRecord> recentRuns() {
        return orchestrator.history().recent(RECENT_RUNS);
    }

    /**
     * A counter that changes when the history does, so a run list is only re-rendered and pushed
     * when a run has actually been added.
     */
    private long runsVersion() {
        List<RunRecord> runs = recentRuns();
        long newest = runs.isEmpty() ? 0L : runs.getFirst().startedAt().toEpochMilli();
        return runs.size() * 31L + newest;
    }

    private Map<String, AccountStatus> accountStatuses() {
        if (Duration.between(statusesFetchedAt, Instant.now()).compareTo(STATUS_CACHE_TTL) < 0) {
            return cachedStatuses;
        }
        Map<String, AccountStatus> fresh = new LinkedHashMap<>();
        for (AccountConfig account : config.accountsOrEmpty()) {
            fresh.put(account.id(), accounts.status(account));
        }
        cachedStatuses = Map.copyOf(fresh);
        statusesFetchedAt = Instant.now();
        return cachedStatuses;
    }

    private void loginPage(HttpExchange exchange, String accountId) throws IOException {
        Optional<AccountConfig> account = config.account(accountId);
        if (account.isEmpty() || account.get().authOrDefault() != AccountConfig.AuthMode.MICROSOFT) {
            respond(exchange, 404, "text/plain",
                    "No such Microsoft account".getBytes(StandardCharsets.UTF_8), null);
            return;
        }
        // A finished flow from an earlier visit would otherwise greet whoever opens this page with
        // the result of a sign-in they did not just perform.
        logins.get(accountId)
                .filter(flow -> flow.state() == LoginFlows.State.DONE
                        || flow.state() == LoginFlows.State.FAILED)
                .ifPresent(flow -> logins.clear(accountId));
        html(exchange, 200, LoginView.render(accountId, headerMeta(), assets.version()));
    }

    // ------------------------------------------------------------------ api

    private String stateJson() {
        ObjectNode root = json.createObjectNode();
        root.put("now", Instant.now().toString());
        root.put("runsVersion", runsVersion());

        Map<String, AccountStatus> statuses = accountStatuses();
        root.put("accountsNeedingLogin",
                statuses.values().stream().filter(status -> !status.isUsable()).count());

        Map<String, JobExecution> active = orchestrator.runningJobs();
        root.put("running", active.size());

        List<RunRecord> history = recentRuns();
        ArrayNode jobs = root.putArray("jobs");
        for (Scheduler.Upcoming upcoming : scheduler.upcoming()) {
            ObjectNode job = jobs.addObject();
            job.put("id", upcoming.jobId());
            job.put("nextRun", upcoming.nextRun() == null ? null : upcoming.nextRun().toInstant().toString());

            JobExecution execution = active.get(upcoming.jobId());
            job.put("running", execution != null);
            job.put("startedAt", execution == null ? null : execution.startedAt().toString());
            job.put("cancelling", execution != null && execution.isCancelled());
            // The live detail: which visit, on which server, and how far the join has got. This is
            // the difference between "running" and knowing whether it is stuck on a resource pack.
            job.put("currentServer", execution == null ? null : execution.currentServer());
            job.put("currentAccount", execution == null ? null : execution.currentAccount());
            job.put("visitIndex", execution == null ? 0 : execution.visitIndex());
            job.put("visitCount", execution == null ? 0 : execution.visitCount());
            job.put("attempt", execution == null ? 0 : execution.attempt());
            job.put("phase", execution == null ? null : execution.phase().name());
            job.put("phaseLabel", execution == null ? null : DashboardView.phaseLabel(execution));

            RunRecord last = history.stream()
                    .filter(run -> run.jobId().equals(upcoming.jobId()))
                    .findFirst().orElse(null);
            job.put("lastStatus", last == null ? null : last.status().name());

            // The status mark, rendered here rather than rebuilt in the browser. See
            // DashboardView.jobStatusHtml for why this one element travels as markup.
            JobConfig configured = config.job(upcoming.jobId()).orElse(null);
            job.put("statusHtml", configured == null
                    ? null : DashboardView.jobStatusHtml(configured, execution, last));
            job.put("railClass", configured == null
                    ? null : DashboardView.jobRailClass(configured, execution, last));
        }

        ArrayNode accountsNode = root.putArray("accounts");
        statuses.forEach((id, status) -> {
            ObjectNode account = accountsNode.addObject();
            account.put("id", id);
            account.put("state", status.state().toString());
            account.put("usable", status.isUsable());
            account.put("detail", status.detail());
            account.put("username", status.username());
            account.put("sessionExpiry",
                    status.sessionExpiry() == null ? null : status.sessionExpiry().toString());
            account.put("tokenExpiry",
                    status.tokenExpiry() == null ? null : status.tokenExpiry().toString());
            account.put("statusHtml", DashboardView.accountStatusHtml(status));
            account.put("railClass", DashboardView.accountRailClass(status));
        });

        return root.toString();
    }

    private void runJob(HttpExchange exchange, String jobId) throws IOException {
        Optional<JobConfig> job = config.job(jobId);
        if (job.isEmpty()) {
            json(exchange, 404, "{\"ok\":false,\"message\":\"No such job\"}");
            return;
        }
        if (orchestrator.isRunning(jobId)) {
            json(exchange, 409, "{\"ok\":false,\"message\":\"" + jobId + " is already running\"}");
            return;
        }

        // A job takes minutes to hours, far beyond any request timeout, so the response only
        // confirms it started.
        Thread worker = new Thread(() -> {
            try {
                orchestrator.runJob(job.get(), "web");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "chronit-web-run-" + jobId);
        worker.setDaemon(true);
        worker.start();

        ObjectNode response = json.createObjectNode();
        response.put("ok", true);
        response.put("message", "Started " + jobId);
        json(exchange, 202, response.toString());
    }

    private void cancelJob(HttpExchange exchange, String jobId) throws IOException {
        if (config.job(jobId).isEmpty()) {
            json(exchange, 404, "{\"ok\":false,\"message\":\"No such job\"}");
            return;
        }
        boolean cancelled = orchestrator.cancel(jobId);

        ObjectNode response = json.createObjectNode();
        response.put("ok", cancelled);
        response.put("message", cancelled
                ? "Stopping " + jobId
                : jobId + " is not running");
        // Not running is a perfectly ordinary answer — someone pressed the button just as the job
        // finished — so it is not an error, only a different outcome.
        json(exchange, cancelled ? 202 : 409, response.toString());
    }

    private void loginApi(HttpExchange exchange, String accountId, String method) throws IOException {
        Optional<AccountConfig> account = config.account(accountId);
        if (account.isEmpty() || account.get().authOrDefault() != AccountConfig.AuthMode.MICROSOFT) {
            json(exchange, 404, "{\"error\":\"No such Microsoft account\"}");
            return;
        }
        if (method.equals("POST")) {
            logins.start(account.get());
            // The status read is now stale by definition.
            statusesFetchedAt = Instant.EPOCH;
        }
        json(exchange, 200, loginJson(accountId));
    }

    /**
     * The state of a device code login.
     *
     * <p>Unlike the old polling endpoint this does not clear a finished flow as a side effect of
     * being read: with several listeners on the stream, whichever one read first would take the
     * result and the others would be told the login had never happened. The flow is cleared when a
     * new one starts instead.
     */
    private String loginJson(String accountId) {
        ObjectNode response = json.createObjectNode();
        response.put("account", accountId);
        Optional<LoginFlows.Flow> flow = logins.get(accountId);
        if (flow.isEmpty()) {
            response.put("state", "IDLE");
            return response.toString();
        }
        LoginFlows.Flow current = flow.get();
        response.put("state", current.state().toString());
        response.put("message", current.message());
        if (current.prompt() != null) {
            response.put("userCode", current.prompt().userCode());
            response.put("verificationUri", current.prompt().verificationUri());
            response.put("directVerificationUri", current.prompt().directVerificationUri());
            response.put("expiresAt", current.prompt().expiresAt().toString());
        }
        return response.toString();
    }

    // ------------------------------------------------------------------ assets

    private void serveAsset(HttpExchange exchange, String name) throws IOException {
        Assets.Asset asset = assets.get(name);
        if (asset == null) {
            respond(exchange, 404, "text/plain", "Not found".getBytes(StandardCharsets.UTF_8), null);
            return;
        }
        String requestETag = exchange.getRequestHeaders().getFirst("If-None-Match");
        if (asset.etag().equals(requestETag)) {
            exchange.getResponseHeaders().set("ETag", asset.etag());
            exchange.sendResponseHeaders(304, -1);
            return;
        }
        exchange.getResponseHeaders().set("ETag", asset.etag());
        // The stylesheet and script are requested with a content hash in the query, so they can be
        // cached hard. The fonts are named from inside the stylesheet and carry no hash, so they
        // get a month and an ETag instead — a revalidation once a month costs a 304, and it means
        // replacing a font eventually reaches everyone rather than never.
        exchange.getResponseHeaders().set("Cache-Control",
                exchange.getRequestURI().getQuery() != null
                        ? "public, max-age=31536000, immutable"
                        : "public, max-age=2592000");
        respond(exchange, 200, asset.contentType(), asset.bytes(), null);
    }

    // ------------------------------------------------------------------ plumbing

    private void html(HttpExchange exchange, int status, String body) throws IOException {
        respond(exchange, status, "text/html", body.getBytes(StandardCharsets.UTF_8), "no-store");
    }

    private void json(HttpExchange exchange, int status, String body) throws IOException {
        respond(exchange, status, "application/json", body.getBytes(StandardCharsets.UTF_8), "no-store");
    }

    private void redirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().set("Location", location);
        exchange.sendResponseHeaders(303, -1);
    }

    private static void respond(HttpExchange exchange, int status, String contentType,
                                byte[] body, String cacheControl) throws IOException {
        // A charset on a font is meaningless — the bytes are not text — so it is only added to
        // the types where it says something.
        boolean textual = contentType.startsWith("text/") || contentType.endsWith("/json")
                || contentType.endsWith("/javascript");
        exchange.getResponseHeaders().set("Content-Type",
                textual ? contentType + "; charset=utf-8" : contentType);
        if (cacheControl != null) {
            exchange.getResponseHeaders().set("Cache-Control", cacheControl);
        }
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        // Everything is served from this origin; nothing external is ever loaded. connect-src
        // covers the event stream as well as fetch.
        exchange.getResponseHeaders().set("Content-Security-Policy",
                "default-src 'none'; style-src 'self'; script-src 'self' 'unsafe-inline'; "
                        + "img-src 'self' data:; font-src 'self'; connect-src 'self'; "
                        + "form-action 'self'; base-uri 'none'");

        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
    }

    private static Optional<String> cookie(HttpExchange exchange, String name) {
        List<String> headers = exchange.getRequestHeaders().get("Cookie");
        if (headers == null) {
            return Optional.empty();
        }
        for (String header : headers) {
            for (String pair : header.split(";")) {
                String trimmed = pair.trim();
                if (trimmed.startsWith(name + "=")) {
                    return Optional.of(trimmed.substring(name.length() + 1));
                }
            }
        }
        return Optional.empty();
    }

    private static String queryParam(HttpExchange exchange, String name) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null) {
            return null;
        }
        return formField(query, name).orElse(null);
    }

    private static Optional<String> formField(String body, String field) {
        for (String pair : body.split("&")) {
            int equals = pair.indexOf('=');
            if (equals > 0 && pair.substring(0, equals).equals(field)) {
                return Optional.of(URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8));
            }
        }
        return Optional.empty();
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    /** Static files read once from the jar, with a content hash for cache busting. */
    private static final class Assets {

        record Asset(byte[] bytes, String contentType, String etag) {
        }

        private final Map<String, Asset> files = new LinkedHashMap<>();
        private final String version;

        Assets() {
            load("app.css", "text/css");
            load("app.js", "text/javascript");
            // IBM Plex, self-hosted. A webfont, but still no external request: the point of the
            // rule was never "no webfont", it was that this page must not phone anywhere.
            // Seventy-five kilobytes for the whole interface, and the licence travels with them
            // because the OFL requires it to.
            load("plex-sans-var.woff2", "font/woff2");
            load("plex-mono-400.woff2", "font/woff2");
            load("plex-mono-500.woff2", "font/woff2");
            load("PLEX-LICENSE.txt", "text/plain");
            // Hashed over every asset rather than taken from the front of their concatenated
            // etags: the first asset alphabetically would otherwise be the only one that could
            // move the token, so a change to app.js alone would keep the same URL — and these are
            // served immutable for a year, so browsers would never ask for it again.
            this.version = digestOf(files.values().stream()
                    .map(Asset::etag)
                    .reduce("", String::concat));
        }

        private static String digestOf(String input) {
            if (input.isEmpty()) {
                return "dev";
            }
            try {
                byte[] hash = MessageDigest.getInstance("SHA-256")
                        .digest(input.getBytes(StandardCharsets.UTF_8));
                return HexFormat.of().formatHex(hash).substring(0, 10);
            } catch (Exception e) {
                return "dev";
            }
        }

        private void load(String name, String contentType) {
            try (InputStream in = Assets.class.getResourceAsStream("/assets/" + name)) {
                if (in == null) {
                    log.error("Bundled asset /assets/{} is missing; the interface will look broken", name);
                    return;
                }
                byte[] bytes = in.readAllBytes();
                String etag = "\"" + HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(bytes)).substring(0, 16) + "\"";
                files.put(name, new Asset(bytes, contentType, etag));
            } catch (Exception e) {
                log.error("Could not read bundled asset {}: {}", name, e.toString());
            }
        }

        Asset get(String name) {
            return files.get(name);
        }

        String version() {
            return version;
        }
    }
}
