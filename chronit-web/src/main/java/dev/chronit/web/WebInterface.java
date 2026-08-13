package dev.chronit.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.chronit.core.auth.AccountManager;
import dev.chronit.core.auth.AccountStatus;
import dev.chronit.core.config.AccountConfig;
import dev.chronit.core.config.ChronitConfig;
import dev.chronit.core.config.JobConfig;
import dev.chronit.core.config.WebConfig;
import dev.chronit.core.run.Orchestrator;
import dev.chronit.core.run.Scheduler;
import dev.chronit.core.state.RunRecord;
import dev.chronit.web.view.DashboardView;
import dev.chronit.web.view.LoginView;
import dev.chronit.web.view.RunsView;
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

/**
 * A small status and sign-in interface.
 *
 * <p>It exists mainly for one awkward moment: the Microsoft refresh token expires roughly every
 * ninety days, and re-authorising means reading a short-lived code out of a container's logs and
 * typing it before it expires. A page with a button is a better answer to that than
 * {@code docker logs -f}.
 *
 * <p>Built on the JDK's own HTTP server — the whole interface is a few server-rendered pages and a
 * small JSON endpoint, and an embedded servlet container would add megabytes to the image for
 * nothing. Pages are rendered through a typed HTML builder rather than string concatenation,
 * because everything shown here (server names, kick reasons, menu titles) comes from outside the
 * process and a forgotten escape would be an injection hole.
 */
public final class WebInterface {

    private static final Logger log = LoggerFactory.getLogger(WebInterface.class);

    private static final int RECENT_RUNS = 25;

    /**
     * Account status reads parse a token file from disk. The dashboard polls, so without a short
     * cache every poll would hit the filesystem once per account for information that changes a few
     * times a year.
     */
    private static final Duration STATUS_CACHE_TTL = Duration.ofSeconds(5);

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
    private volatile Map<String, AccountStatus> cachedStatuses = Map.of();
    private volatile Instant statusesFetchedAt = Instant.EPOCH;

    private HttpServer server;

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
        this.logins = new LoginFlows(accounts);
    }

    public void start() throws IOException {
        server = HttpServer.create(
                new InetSocketAddress(webConfig.bindOrDefault(), webConfig.portOrDefault()), 0);
        server.setExecutor(Executors.newFixedThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "chronit-web");
            thread.setDaemon(true);
            return thread;
        }));

        // Unauthenticated and reachable from anywhere, so it must never reveal anything.
        server.createContext("/healthz", exchange -> {
            respond(exchange, 200, "text/plain", "ok".getBytes(StandardCharsets.UTF_8), null);
            exchange.close();
        });

        server.createContext("/", this::route);
        server.start();

        log.info("Web interface on http://{}:{}{}",
                webConfig.bindOrDefault(), webConfig.portOrDefault(),
                requiresToken() ? " (token required)" : "");
    }

    public void stop() {
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
     * browser gets it once through a form post and keeps it in an HttpOnly cookie afterwards.
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
        boolean wantsJson = exchange.getRequestURI().getPath().startsWith("/api/")
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
     * A counter that changes when the history does, so the browser can tell whether it needs to
     * refetch the run list without downloading it every poll.
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

        ArrayNode jobs = root.putArray("jobs");
        Map<String, dev.chronit.core.run.JobExecution> active = orchestrator.runningJobs();
        for (Scheduler.Upcoming upcoming : scheduler.upcoming()) {
            ObjectNode job = jobs.addObject();
            job.put("id", upcoming.jobId());
            job.put("nextRun", upcoming.nextRun() == null ? null : upcoming.nextRun().toInstant().toString());

            dev.chronit.core.run.JobExecution execution = active.get(upcoming.jobId());
            job.put("running", execution != null);
            job.put("startedAt", execution == null ? null : execution.startedAt().toString());
            job.put("currentServer", execution == null ? null : execution.currentServer());
            job.put("cancelling", execution != null && execution.isCancelled());
        }

        ArrayNode accountsNode = root.putArray("accounts");
        statuses.forEach((id, status) -> {
            ObjectNode account = accountsNode.addObject();
            account.put("id", id);
            account.put("state", status.state().toString());
            account.put("usable", status.isUsable());
            account.put("detail", status.detail());
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

        ObjectNode response = json.createObjectNode();
        Optional<LoginFlows.Flow> flow = logins.get(accountId);
        if (flow.isEmpty()) {
            response.put("state", "IDLE");
        } else {
            LoginFlows.Flow current = flow.get();
            response.put("state", current.state().toString());
            response.put("message", current.message());
            if (current.prompt() != null) {
                response.put("userCode", current.prompt().userCode());
                response.put("verificationUri", current.prompt().verificationUri());
                response.put("directVerificationUri", current.prompt().directVerificationUri());
                response.put("expiresAt", current.prompt().expiresAt().toString());
            }
            if (current.state() == LoginFlows.State.DONE || current.state() == LoginFlows.State.FAILED) {
                logins.clear(accountId);
                statusesFetchedAt = Instant.EPOCH;
            }
        }
        json(exchange, 200, response.toString());
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
        // Safe to cache hard because the URL carries a content hash.
        exchange.getResponseHeaders().set("Cache-Control", "public, max-age=31536000, immutable");
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
        exchange.getResponseHeaders().set("Content-Type", contentType + "; charset=utf-8");
        if (cacheControl != null) {
            exchange.getResponseHeaders().set("Cache-Control", cacheControl);
        }
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        // Everything is served from this origin; nothing external is ever loaded.
        exchange.getResponseHeaders().set("Content-Security-Policy",
                "default-src 'none'; style-src 'self'; script-src 'self' 'unsafe-inline'; "
                        + "img-src 'self' data:; connect-src 'self'; form-action 'self'; base-uri 'none'");

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
