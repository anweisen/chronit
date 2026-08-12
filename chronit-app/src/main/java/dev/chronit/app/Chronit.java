package dev.chronit.app;

import dev.chronit.app.command.AccountsCommand;
import dev.chronit.app.command.DaemonCommand;
import dev.chronit.app.command.LoginCommand;
import dev.chronit.app.command.PingCommand;
import dev.chronit.app.command.RunCommand;
import dev.chronit.app.command.ValidateCommand;
import dev.chronit.core.config.ConfigException;
import picocli.CommandLine;

/**
 * Command line entry point.
 *
 * <p>Both scheduling models are first class. {@code daemon} runs the built-in scheduler and is the
 * container's default command; {@code run} performs a single job and exits, for anyone driving this
 * from system cron, a systemd timer or a Kubernetes CronJob.
 */
@CommandLine.Command(
        name = "chronit",
        mixinStandardHelpOptions = true,
        versionProvider = Chronit.Version.class,
        description = "Logs a Minecraft account into a list of servers on a schedule.",
        subcommands = {
                DaemonCommand.class,
                RunCommand.class,
                LoginCommand.class,
                AccountsCommand.class,
                PingCommand.class,
                ValidateCommand.class
        })
public final class Chronit {

    public static void main(String[] args) {
        CommandLine commandLine = new CommandLine(new Chronit())
                .setExecutionExceptionHandler(Chronit::handleException)
                .setCaseInsensitiveEnumValuesAllowed(true);

        if (args.length == 0) {
            commandLine.usage(System.out);
            System.exit(CommandLine.ExitCode.USAGE);
        }
        System.exit(commandLine.execute(args));
    }

    /**
     * Prints a readable message rather than a stack trace for the errors a user is expected to
     * hit — an invalid config, an account needing a login, an unreachable server.
     */
    private static int handleException(Exception error, CommandLine commandLine,
                                       CommandLine.ParseResult parseResult) {
        if (error instanceof ConfigException) {
            commandLine.getErr().println(commandLine.getColorScheme().errorText(error.getMessage()));
            return CommandLine.ExitCode.USAGE;
        }
        commandLine.getErr().println(commandLine.getColorScheme().errorText(
                error.getClass().getSimpleName() + ": " + error.getMessage()));
        if (System.getenv("CHRONIT_DEBUG") != null) {
            error.printStackTrace(commandLine.getErr());
        } else {
            commandLine.getErr().println("Set CHRONIT_DEBUG=1 for a stack trace.");
        }
        return CommandLine.ExitCode.SOFTWARE;
    }

    static final class Version implements CommandLine.IVersionProvider {
        @Override
        public String[] getVersion() {
            String implementation = Chronit.class.getPackage().getImplementationVersion();
            return new String[]{
                    "chronit " + (implementation != null ? implementation : "development build"),
                    "Minecraft " + dev.chronit.driver.mcpl.McplDriver.NATIVE_VERSION
                            + " (protocol " + dev.chronit.driver.mcpl.McplDriver.NATIVE_PROTOCOL + ")",
                    "Protocol translation: " + (dev.chronit.core.driver.TranslationProvider.discover().isEmpty()
                            ? "not installed" : dev.chronit.core.driver.TranslationProvider.discover()
                            .stream().map(dev.chronit.core.driver.TranslationProvider::id).toList())
            };
        }
    }
}
