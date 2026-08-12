package dev.chronit.app.command;

import dev.chronit.core.auth.AccountManager;
import dev.chronit.core.auth.AuthException;
import dev.chronit.core.config.AccountConfig;
import dev.chronit.core.config.ChronitConfig;
import dev.chronit.core.driver.AuthContext;
import picocli.CommandLine;

import java.util.concurrent.Callable;

/**
 * Interactive Microsoft login for one account.
 *
 * <p>Uses the device code flow: the code and link are printed here and entered on any device with a
 * browser, which is the only workable option for something running in a container with no browser
 * and no public callback URL.
 */
@CommandLine.Command(
        name = "login",
        description = "Log a Microsoft account in and store its session.")
public final class LoginCommand implements Callable<Integer> {

    @CommandLine.Mixin
    ConfigMixin configMixin;

    @CommandLine.Parameters(index = "0", description = "Account id from the configuration.")
    String accountId;

    @Override
    public Integer call() {
        ChronitConfig config = configMixin.load();
        AccountConfig account = config.account(accountId).orElse(null);
        if (account == null) {
            System.err.println("No account with id '" + accountId + "'. Configured accounts: "
                    + config.accountsOrEmpty().stream().map(AccountConfig::id).toList());
            return CommandLine.ExitCode.USAGE;
        }

        AccountManager accounts = new AccountManager(config);
        try {
            AuthContext identity = accounts.login(account, prompt -> {
                System.out.println();
                System.out.println("  To authorise account '" + accountId + "':");
                System.out.println();
                System.out.println("    " + prompt.describe());
                System.out.println();
                System.out.println("  Waiting... (the code expires at " + prompt.expiresAt() + ")");
            });

            System.out.println();
            System.out.println("Logged in as " + identity.username() + " (" + identity.uuid() + ")");
            System.out.println("Session stored at " + accounts.tokenFile(account));
            if (!identity.canSignChat()) {
                System.out.println("Note: no chat signing certificate was available. Commands still work; "
                        + "plain chat will be rejected by servers that enforce secure profiles.");
            }
            return 0;
        } catch (AuthException e) {
            System.err.println("Login failed: " + e.getMessage());
            return CommandLine.ExitCode.SOFTWARE;
        }
    }
}
