package dev.chronit.app.command;

import dev.chronit.core.auth.AccountManager;
import dev.chronit.core.auth.AccountStatus;
import dev.chronit.core.config.ChronitConfig;
import picocli.CommandLine;

import java.util.concurrent.Callable;

/** Shows which accounts are ready and which need a login. */
@CommandLine.Command(
        name = "accounts",
        description = "List configured accounts and whether they can be used right now.")
public final class AccountsCommand implements Callable<Integer> {

    @CommandLine.Mixin
    ConfigMixin configMixin;

    @Override
    public Integer call() {
        ChronitConfig config = configMixin.load();
        AccountManager accounts = new AccountManager(config);

        System.out.printf("%-14s %-12s %-18s %-26s %s%n",
                "ID", "STATE", "USERNAME", "TOKEN EXPIRES", "DETAIL");

        boolean allUsable = true;
        for (var account : config.accountsOrEmpty()) {
            AccountStatus status = accounts.status(account);
            allUsable &= status.isUsable();
            System.out.printf("%-14s %-12s %-18s %-26s %s%n",
                    status.id(),
                    status.state(),
                    status.username() != null ? status.username() : "-",
                    status.tokenExpiry() != null ? status.tokenExpiry().toString() : "-",
                    status.detail());
        }

        // A non-zero exit lets a monitoring check notice that a login is due before a scheduled
        // run fails because of it.
        return allUsable ? 0 : 1;
    }
}
