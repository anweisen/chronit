package dev.chronit.web;

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
import dev.chronit.core.util.Durations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;

/**
 * A small status and login interface.
 *
 * <p>It exists mainly for one awkward moment: the Microsoft refresh token expires roughly every
 * ninety days, and re-authorising means reading a short-lived code out of a container's logs and
 * typing it before it expires. A page with a button is a better answer to that than
 * {@code docker logs -f}.
 *
 * <p>Built on the JDK's own HTTP server. The whole interface is a handful of server-rendered pages;
 * an embedded servlet container would add several megabytes to the image for no benefit.
 */
public final class WebInterface {

    private static final Logger log = LoggerFactory.getLogger(WebInterface.class);

    private static final int RECENT_RUNS = 25;

    private final ChronitConfig config;
    private final WebConfig webConfig;
    private final Orchestrator orchestrator;
    private final Scheduler scheduler;
    private final AccountManager accounts;
    private final LoginFlows logins;

    private HttpServer server;

    public WebInterface(ChronitConfig config,
                        Orchestrator orchestrator,
                        Scheduler scheduler,
                        AccountManager accounts) {
        this.config = config;
        this.webConfig = config.webOrDisabled();
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
        server.createContext("/healthz", exchange -> respond(exchange, 200, "text/plain", "ok"));

        server.createContext("/", this::route);
        server.start();

        log.info("Web interface on http://{}:{}{}",
                webConfig.bindOrDefault(), webConfig.portOrDefault(),
                webConfig.token() != null ? " (token required)" : "");
    }

    public void stop() {
        if (server != null) {
            server.stop(1);
        }
    }

    private void route(HttpExchange exchange) throws IOException {
        try {
            if (!isAuthorised(exchange)) {
                respond(exchange, 401, "text/plain", "Unauthorised. Supply the configured token as "
                        + "an Authorization: Bearer header or a ?token= parameter.");
                return;
            }

            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();

            if (path.equals("/") || path.isEmpty()) {
                respond(exchange, 200, "text/html", dashboard());
                return;
            }
            if (path.startsWith("/jobs/") && path.endsWith("/run") && method.equals("POST")) {
                runJob(exchange, path.substring("/jobs/".length(), path.length() - "/run".length()));
                return;
            }
            if (path.startsWith("/accounts/") && path.endsWith("/login")) {
                handleLogin(exchange, path.substring("/accounts/".length(), path.length() - "/login".length()));
                return;
            }
            respond(exchange, 404, "text/plain", "Not found");
        } catch (RuntimeException e) {
            log.warn("Request {} failed: {}", exchange.getRequestURI(), e.toString(), e);
            respond(exchange, 500, "text/plain", "Internal error");
        } finally {
            exchange.close();
        }
    }

    /**
     * Constant-time token comparison.
     *
     * <p>Configuration validation already refuses a non-loopback bind without a token, so the
     * unauthenticated path only applies to a loopback listener.
     */
    private boolean isAuthorised(HttpExchange exchange) {
        String expected = webConfig.token();
        if (expected == null || expected.isBlank()) {
            return true;
        }
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        String presented = header != null && header.startsWith("Bearer ")
                ? header.substring("Bearer ".length())
                : queryParam(exchange, "token").orElse("");

        return MessageDigest.isEqual(
                presented.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ pages

    private String dashboard() {
        StringBuilder body = new StringBuilder();

        body.append("<h2>Schedule</h2><table><tr><th>Job</th><th>Cron</th><th>Next run</th>")
                .append("<th>In</th><th></th></tr>");
        for (Scheduler.Upcoming upcoming : scheduler.upcoming()) {
            body.append("<tr><td><strong>").append(Html.escape(upcoming.jobId())).append("</strong>")
                    .append(upcoming.running() ? " " + Html.badge(true, "running") : "")
                    .append("</td><td class=\"mono\">").append(Html.escape(upcoming.cron()))
                    .append(" <span class=\"muted\">").append(Html.escape(upcoming.timezone())).append("</span>")
                    .append("</td><td>").append(Html.escape(format(upcoming.nextRun())))
                    .append("</td><td>").append(Html.escape(upcoming.inText()))
                    .append("</td><td><form method=\"post\" action=\"jobs/")
                    .append(Html.escape(upcoming.jobId())).append("/run")
                    .append(tokenQuery()).append("\"><button>Run now</button></form></td></tr>");
        }
        body.append("</table>");

        body.append("<h2>Accounts</h2><table><tr><th>Account</th><th>State</th><th>Username</th>")
                .append("<th>Detail</th><th></th></tr>");
        for (AccountConfig account : config.accountsOrEmpty()) {
            AccountStatus status = accounts.status(account);
            body.append("<tr><td><strong>").append(Html.escape(status.id())).append("</strong></td><td>")
                    .append(Html.badge(status.isUsable(), status.state().toString()))
                    .append("</td><td>").append(Html.escape(status.username() == null ? "-" : status.username()))
                    .append("</td><td class=\"wrap muted\">").append(Html.escape(status.detail()))
                    .append("</td><td>");
            if (account.authOrDefault() == AccountConfig.AuthMode.MICROSOFT) {
                body.append("<a href=\"accounts/").append(Html.escape(account.id())).append("/login")
                        .append(tokenQuery()).append("\">Log in</a>");
            }
            body.append("</td></tr>");
        }
        body.append("</table>");

        body.append("<h2>Recent runs</h2>");
        List<RunRecord> runs = orchestrator.history().recent(RECENT_RUNS);
        if (runs.isEmpty()) {
            body.append("<p class=\"muted\">Nothing has run yet.</p>");
        } else {
            body.append("<table><tr><th>When</th><th>Job</th><th>Trigger</th><th>Took</th>")
                    .append("<th>Visits</th></tr>");
            for (RunRecord run : runs) {
                body.append("<tr><td>").append(Html.escape(run.startedAt()))
                        .append("</td><td><strong>").append(Html.escape(run.jobId())).append("</strong>")
                        .append("</td><td class=\"muted\">").append(Html.escape(run.trigger()))
                        .append("</td><td>").append(Html.escape(Durations.format(run.duration())))
                        .append("</td><td>");
                for (RunRecord.VisitRecord visit : run.visits()) {
                    body.append(Html.badge(visit.success(), visit.serverId()))
                            .append(" <span class=\"muted\">")
                            .append(Html.escape(visit.detail())).append("</span><br>");
                }
                body.append("</td></tr>");
            }
            body.append("</table>");
        }

        body.append("<h2>Client</h2><p class=\"muted\">Speaking Minecraft protocol ")
                .append(orchestrator.protocols().hasTranslation()
                        ? "with translation available for other versions."
                        : "natively only — protocol translation is not installed.")
                .append("</p>");

        return Html.page("chronit", body.toString());
    }

    private void runJob(HttpExchange exchange, String jobId) throws IOException {
        Optional<JobConfig> job = config.job(jobId);
        if (job.isEmpty()) {
            respond(exchange, 404, "text/plain", "No such job");
            return;
        }
        // Runs on its own thread: a job takes minutes to hours, far beyond any request timeout.
        Thread worker = new Thread(() -> {
            try {
                orchestrator.runJob(job.get(), "web");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "chronit-web-run-" + jobId);
        worker.setDaemon(true);
        worker.start();

        redirectToDashboard(exchange);
    }

    private void handleLogin(HttpExchange exchange, String accountId) throws IOException {
        Optional<AccountConfig> account = config.account(accountId);
        if (account.isEmpty() || account.get().authOrDefault() != AccountConfig.AuthMode.MICROSOFT) {
            respond(exchange, 404, "text/plain", "No such Microsoft account");
            return;
        }

        if (exchange.getRequestMethod().equals("POST")) {
            logins.start(account.get());
            exchange.getResponseHeaders().add("Location", "../" + accountId + "/login" + tokenQuery());
            respond(exchange, 303, "text/plain", "");
            return;
        }

        StringBuilder body = new StringBuilder();
        Integer refresh = null;
        body.append("<h2>Log in: ").append(Html.escape(accountId)).append("</h2>");

        Optional<LoginFlows.Flow> flow = logins.get(accountId);
        if (flow.isEmpty()) {
            body.append("<p>Starting a login opens a Microsoft device authorisation. You will get a "
                            + "short code to enter on another device.</p>")
                    .append("<form method=\"post\" action=\"login").append(tokenQuery())
                    .append("\"><button>Start login</button></form>");
        } else {
            LoginFlows.Flow current = flow.get();
            switch (current.state()) {
                case STARTING -> {
                    body.append("<p class=\"muted\">Requesting a code from Microsoft...</p>");
                    refresh = 2;
                }
                case WAITING -> {
                    body.append("<div class=\"card\"><p>Open <a href=\"")
                            .append(Html.escape(current.prompt().verificationUri()))
                            .append("\" target=\"_blank\" rel=\"noopener noreferrer\">")
                            .append(Html.escape(current.prompt().verificationUri()))
                            .append("</a> and enter:</p><div class=\"code\">")
                            .append(Html.escape(current.prompt().userCode()))
                            .append("</div><p class=\"muted\">Or open <a href=\"")
                            .append(Html.escape(current.prompt().directVerificationUri()))
                            .append("\" target=\"_blank\" rel=\"noopener noreferrer\">this link</a>, "
                                    + "which already includes the code. Expires ")
                            .append(Html.escape(current.prompt().expiresAt()))
                            .append(".</p></div>");
                    refresh = 3;
                }
                case DONE -> {
                    body.append("<p>").append(Html.badge(true, "Logged in")).append("</p>");
                    logins.clear(accountId);
                }
                case FAILED -> {
                    body.append("<p>").append(Html.badge(false, "Failed")).append(" ")
                            .append(Html.escape(current.message())).append("</p>")
                            .append("<form method=\"post\" action=\"login").append(tokenQuery())
                            .append("\"><button>Try again</button></form>");
                    logins.clear(accountId);
                }
            }
        }

        body.append("<p><a href=\"../../\">Back</a></p>");
        respond(exchange, 200, "text/html", Html.page("chronit — login", body.toString(), refresh));
    }

    // ------------------------------------------------------------------ helpers

    /** Carries the token through links and forms when one is configured. */
    private String tokenQuery() {
        return webConfig.token() != null && !webConfig.token().isBlank()
                ? "?token=" + java.net.URLEncoder.encode(webConfig.token(), StandardCharsets.UTF_8)
                : "";
    }

    private void redirectToDashboard(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().add("Location", "/" + tokenQuery());
        respond(exchange, 303, "text/plain", "");
    }

    private static Optional<String> queryParam(HttpExchange exchange, String name) {
        String query = exchange.getRequestURI().getQuery();
        if (query == null) {
            return Optional.empty();
        }
        for (String pair : query.split("&")) {
            int equals = pair.indexOf('=');
            if (equals > 0 && pair.substring(0, equals).equals(name)) {
                return Optional.of(java.net.URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8));
            }
        }
        return Optional.empty();
    }

    private static String format(java.time.ZonedDateTime time) {
        return time == null ? "never" : time.toString();
    }

    private static void respond(HttpExchange exchange, int status, String contentType, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType + "; charset=utf-8");
        // The dashboard reflects live state; a cached copy would be actively misleading.
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }
}
