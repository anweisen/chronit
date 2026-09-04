package net.anweisen.chronit.app;

import net.anweisen.chronit.app.command.AccountsCommand;
import net.anweisen.chronit.app.command.DaemonCommand;
import net.anweisen.chronit.app.command.LoginCommand;
import net.anweisen.chronit.app.command.PingCommand;
import net.anweisen.chronit.app.command.RunCommand;
import net.anweisen.chronit.app.command.ValidateCommand;
import net.anweisen.chronit.core.config.ConfigException;
import net.anweisen.chronit.core.driver.TranslationProvider;
import net.anweisen.chronit.driver.mcpl.McplDriver;
import picocli.CommandLine;

import java.util.List;

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
            List<TranslationProvider> translation = TranslationProvider.discover();
            return new String[]{
                    "chronit " + (implementation != null ? implementation : "development build"),
                    "Minecraft " + McplDriver.NATIVE_VERSION
                            + " (protocol " + McplDriver.NATIVE_PROTOCOL + ")",
                    "Protocol translation: " + (translation.isEmpty()
                            ? "not installed"
                            : translation.stream().map(TranslationProvider::id).toList())
            };
        }
    }
}
