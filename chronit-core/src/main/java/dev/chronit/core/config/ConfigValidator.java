package dev.chronit.core.config;

import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.parser.CronParser;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Checks a loaded configuration and reports every problem at once.
 *
 * <p>The whole point of this application is unattended operation, so problems must surface when the
 * config is written rather than at the scheduled hour. Messages name the exact path
 * ({@code jobs[nightly].visits[0].server}) and, where useful, say how to fix it.
 */
public final class ConfigValidator {

    private static final CronParser UNIX_CRON =
            new CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX));
    private static final CronParser QUARTZ_CRON =
            new CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.QUARTZ));

    private ConfigValidator() {
    }

    public static void validate(ChronitConfig config) {
        List<String> problems = new ArrayList<>();

        validateAccounts(config, problems);
        validateServers(config, problems);
        validateJobs(config, problems);
        validateDefaults(config, problems);
        validateWeb(config, problems);

        if (config.accountsOrEmpty().isEmpty()) {
            problems.add("accounts: at least one account is required");
        }
        if (config.serversOrEmpty().isEmpty()) {
            problems.add("servers: at least one server is required");
        }

        if (!problems.isEmpty()) {
            throw new ConfigException("Configuration is invalid", problems);
        }
    }

    private static void validateAccounts(ChronitConfig config, List<String> problems) {
        Set<String> seen = new HashSet<>();
        List<AccountConfig> accounts = config.accountsOrEmpty();
        for (int i = 0; i < accounts.size(); i++) {
            AccountConfig account = accounts.get(i);
            String path = "accounts[" + (account.id() != null ? account.id() : i) + "]";

            if (isBlank(account.id())) {
                problems.add(path + ".id: required");
            } else if (!seen.add(account.id())) {
                problems.add(path + ".id: duplicate account id '" + account.id() + "'");
            }

            if (account.authOrDefault() == AccountConfig.AuthMode.OFFLINE) {
                if (isBlank(account.username())) {
                    problems.add(path + ".username: required for offline accounts");
                } else if (!account.username().matches("\\w{1,16}")) {
                    problems.add(path + ".username: '" + account.username()
                            + "' is not a valid Minecraft name (1-16 characters, letters/digits/underscore)");
                }
            }
        }
    }

    private static void validateServers(ChronitConfig config, List<String> problems) {
        Set<String> seen = new HashSet<>();
        List<ServerConfig> servers = config.serversOrEmpty();
        for (int i = 0; i < servers.size(); i++) {
            ServerConfig server = servers.get(i);
            String path = "servers[" + (server.id() != null ? server.id() : i) + "]";

            if (isBlank(server.id())) {
                problems.add(path + ".id: required");
            } else if (!seen.add(server.id())) {
                problems.add(path + ".id: duplicate server id '" + server.id() + "'");
            }
            if (isBlank(server.host())) {
                problems.add(path + ".host: required");
            }
            if (server.port() != null && (server.port() < 1 || server.port() > 65535)) {
                problems.add(path + ".port: " + server.port() + " is not a valid port");
            }
            if (server.protocol() != null && ProtocolSpec.parse(server.protocol()) instanceof ProtocolSpec.Exact exact
                    && (exact.protocol() < 0 || exact.protocol() > 100_000)) {
                problems.add(path + ".protocol: " + exact.protocol() + " is not a plausible protocol number");
            }
            if (server.proxy() != null) {
                ProxyConfig proxy = server.proxy();
                if (isBlank(proxy.host())) {
                    problems.add(path + ".proxy.host: required");
                }
                if (proxy.port() < 1 || proxy.port() > 65535) {
                    problems.add(path + ".proxy.port: " + proxy.port() + " is not a valid port");
                }
            }
            validateResourcePack(server.resourcePack(), path + ".resourcePack", problems);
            validateReadyWhen(server.readyWhen(), path + ".readyWhen", problems);
            requireNonNegative(server.connectTimeout(), path + ".connectTimeout", problems);
        }
    }

    private static void validateJobs(ChronitConfig config, List<String> problems) {
        Set<String> seen = new HashSet<>();
        List<JobConfig> jobs = config.jobsOrEmpty();
        for (int i = 0; i < jobs.size(); i++) {
            JobConfig job = jobs.get(i);
            String path = "jobs[" + (job.id() != null ? job.id() : i) + "]";

            if (isBlank(job.id())) {
                problems.add(path + ".id: required");
            } else if (!seen.add(job.id())) {
                problems.add(path + ".id: duplicate job id '" + job.id() + "'");
            }

            if (isBlank(job.cron())) {
                problems.add(path + ".cron: required");
            } else if (!parsesAsCron(job.cron())) {
                problems.add(path + ".cron: '" + job.cron()
                        + "' is not a valid cron expression (five fields, or six with leading seconds)");
            }

            if (job.visits() == null || job.visits().isEmpty()) {
                problems.add(path + ".visits: at least one visit is required");
                continue;
            }
            for (int v = 0; v < job.visits().size(); v++) {
                validateVisit(config, job.visits().get(v), path + ".visits[" + v + "]", problems);
            }
        }
    }

    private static void validateVisit(ChronitConfig config, VisitConfig visit, String path, List<String> problems) {
        if (isBlank(visit.server())) {
            problems.add(path + ".server: required");
        } else if (config.server(visit.server()).isEmpty()) {
            problems.add(path + ".server: no server with id '" + visit.server() + "'");
        }
        if (isBlank(visit.account())) {
            problems.add(path + ".account: required");
        } else if (config.account(visit.account()).isEmpty()) {
            problems.add(path + ".account: no account with id '" + visit.account() + "'");
        }
        requireNonNegative(visit.stayFor(), path + ".stayFor", problems);
        requireNonNegative(visit.gapAfter(), path + ".gapAfter", problems);

        if (visit.onFail() != null && visit.onFail().retries() != null && visit.onFail().retries() < 0) {
            problems.add(path + ".onFail.retries: must not be negative");
        }

        List<ActionConfig> onReady = visit.onReadyOrEmpty();
        for (int a = 0; a < onReady.size(); a++) {
            validateAction(onReady.get(a), path + ".onReady[" + a + "]", problems);
        }
        List<ActionConfig> onLeave = visit.onLeaveOrEmpty();
        for (int a = 0; a < onLeave.size(); a++) {
            validateAction(onLeave.get(a), path + ".onLeave[" + a + "]", problems);
        }
    }

    private static void validateAction(ActionConfig action, String path, List<String> problems) {
        int set = 0;
        if (action.command() != null) {
            set++;
        }
        if (action.chat() != null) {
            set++;
        }
        if (action.pause() != null) {
            set++;
        }
        if (action.click() != null) {
            set++;
        }
        if (action.closeScreen() != null) {
            set++;
        }
        String choices = "'command', 'chat', 'wait', 'click' or 'closeScreen'";
        if (set == 0) {
            problems.add(path + ": needs one of " + choices);
        } else if (set > 1) {
            problems.add(path + ": set only one of " + choices);
        }

        if (action.click() != null) {
            validateClick(action.click(), path + ".click", problems);
        }

        if (action.command() != null && action.command().startsWith("/")) {
            problems.add(path + ".command: drop the leading slash — '" + action.command()
                    + "' would be sent as a command named '/" + action.command().substring(1) + "'");
        }
        if (action.command() != null && action.command().isBlank()) {
            problems.add(path + ".command: must not be blank");
        }
        requireNonNegative(action.pause(), path + ".wait", problems);
        requireNonNegative(action.delayAfter(), path + ".delayAfter", problems);

        if (action.waitFor() != null) {
            validateWaitFor(action.waitFor(), path + ".waitFor", problems);
        }
    }

    private static void validateWaitFor(WaitForConfig waitFor, String path, List<String> problems) {
        boolean hasChat = waitFor.chat() != null;
        boolean hasScreen = waitFor.screen() != null;

        if (hasChat && hasScreen) {
            problems.add(path + ": set only one of 'chat' or 'screen'");
        } else if (!hasChat && !hasScreen) {
            problems.add(path + ": needs 'chat' or 'screen'");
        } else if (hasChat && waitFor.chat().isBlank()) {
            // An empty screen pattern is meaningful — any menu will do — but an empty chat pattern
            // matches every message, which is never what someone meant to write.
            problems.add(path + ".chat: must not be blank");
        } else {
            requireRegex(waitFor.pattern(), path + "." + (hasScreen ? "screen" : "chat"), problems);
        }

        requireNonNegative(waitFor.timeout(), path + ".timeout", problems);
    }

    private static void validateClick(ClickConfig click, String path, List<String> problems) {
        if (click.slot() == null) {
            problems.add(path + ".slot: required");
        } else if (click.slot() < 0) {
            problems.add(path + ".slot: must not be negative");
        } else if (click.inventory() == dev.chronit.core.driver.SlotClick.InventoryPart.PLAYER
                && click.slot() >= dev.chronit.core.driver.ContainerInfo.PLAYER_INVENTORY_SLOTS) {
            problems.add(path + ".slot: " + click.slot() + " is outside the player inventory "
                    + "(0-26 are the main rows, 27-35 the hotbar)");
        }
    }

    private static void validateDefaults(ChronitConfig config, List<String> problems) {
        DefaultsConfig defaults = config.defaults();
        if (defaults == null) {
            return;
        }
        if (defaults.jitter() != null && (defaults.jitter() < 0 || defaults.jitter() > 1)) {
            problems.add("defaults.jitter: " + defaults.jitter() + " must be between 0 and 1");
        }
        validateResourcePack(defaults.resourcePack(), "defaults.resourcePack", problems);
        validateReadyWhen(defaults.readyWhen(), "defaults.readyWhen", problems);
        requireNonNegative(defaults.connectTimeout(), "defaults.connectTimeout", problems);

        if (defaults.clientInformation() != null && defaults.clientInformation().viewDistance() != null) {
            int viewDistance = defaults.clientInformation().viewDistance();
            if (viewDistance < 2 || viewDistance > 32) {
                problems.add("defaults.clientInformation.viewDistance: " + viewDistance
                        + " is outside the range a real client can send (2-32)");
            }
        }
    }

    private static void validateResourcePack(ResourcePackConfig pack, String path, List<String> problems) {
        if (pack == null) {
            return;
        }
        requireNonNegative(pack.downloadDelay(), path + ".downloadDelay", problems);
        requireNonNegative(pack.applyDelay(), path + ".applyDelay", problems);
        requireNonNegative(pack.httpTimeout(), path + ".httpTimeout", problems);
        if (pack.maxSizeMb() != null && pack.maxSizeMb() <= 0) {
            problems.add(path + ".maxSizeMb: must be positive");
        }
    }

    private static void validateReadyWhen(ReadyWhenConfig ready, String path, List<String> problems) {
        if (ready == null) {
            return;
        }
        if (ready.chat() != null) {
            requireRegex(ready.chat(), path + ".chat", problems);
        }
        if (ready.minChunks() != null && ready.minChunks() < 0) {
            problems.add(path + ".minChunks: must not be negative");
        }
        requireNonNegative(ready.settle(), path + ".settle", problems);
        requireNonNegative(ready.timeout(), path + ".timeout", problems);
        if (ready.timeout() != null && ready.timeout().isZero()) {
            problems.add(path + ".timeout: must be greater than zero");
        }
    }

    private static void validateWeb(ChronitConfig config, List<String> problems) {
        WebConfig web = config.web();
        if (web == null || !web.isEnabled()) {
            return;
        }
        if (web.portOrDefault() < 1 || web.portOrDefault() > 65535) {
            problems.add("web.port: " + web.portOrDefault() + " is not a valid port");
        }
        if (!web.isLoopbackOnly() && isBlank(web.token())) {
            problems.add("web.token: required when web.bind is not loopback — the interface can start "
                    + "a login flow and show run history, so it must not be exposed unauthenticated");
        }
    }

    private static boolean parsesAsCron(String expression) {
        // Six fields means a leading seconds column, which the Quartz style understands.
        int fields = expression.trim().split("\\s+").length;
        try {
            if (fields >= 6) {
                QUARTZ_CRON.parse(expression).validate();
            } else {
                UNIX_CRON.parse(expression).validate();
            }
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static void requireRegex(String pattern, String path, List<String> problems) {
        try {
            Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            problems.add(path + ": not a valid regular expression — " + e.getDescription());
        }
    }

    private static void requireNonNegative(Duration duration, String path, List<String> problems) {
        if (duration != null && duration.isNegative()) {
            problems.add(path + ": must not be negative");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
