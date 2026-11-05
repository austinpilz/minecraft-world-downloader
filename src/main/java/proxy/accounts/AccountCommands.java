package proxy.accounts;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import proxy.SocksProxyDetails;
import proxy.accounts.AccountStore.Account;
import proxy.accounts.AccountStore.BotProfile;
import proxy.auth.AuthDetails;
import proxy.auth.DeviceCodeLogin;
import proxy.auth.MicrosoftAuthHandler;

/**
 * Command line management of the proxy's accounts, bots and SOCKS pool, for running without the GUI. Nothing here
 * prints a token or a password.
 */
public final class AccountCommands {
    private AccountCommands() { }

    /**
     * Load the pool file given on the command line into the store.
     * @return the pool's entries
     */
    public static SocksPool.ParseResult loadPoolFile(AccountStore store, String file, String username, String password) {
        try {
            SocksPool.ParseResult result = store.applyPoolFile(Path.of(file), username, password);
            result.errors().forEach(e -> System.err.println("SOCKS pool " + file + ": " + e));
            System.out.println("SOCKS pool: " + result.entries().size() + " entries from " + file + ".");
            return result;
        } catch (IOException ex) {
            throw new IllegalArgumentException("Cannot read the SOCKS pool " + file + ": " + ex.getMessage(), ex);
        }
    }

    /**
     * Add an account with the Microsoft device code flow. The code can be entered in any browser, on any machine.
     */
    public static Account addAccount(AccountStore store) throws InterruptedException {
        DeviceCodeLogin login = DeviceCodeLogin.start();
        System.out.println("Open " + login.getVerificationUri() + " and enter the code " + login.getUserCode()
            + " to log in the account to add. Waiting...");

        MicrosoftAuthHandler auth = login.await(() -> false);
        AuthDetails details = auth.freshAuthDetails();

        Account account = store.addAccount(new Account(details.getUsername(), details.getUuid(), auth));
        store.save();
        System.out.println("Added account " + account.getName() + ".");
        return account;
    }

    /**
     * Add a bot, written as ClientName:AccountName or ClientName:AccountName@host:port. ClientName is what the
     * client connects with, e.g. the name of its Cracked account in Meteor. Without a host the bot picks a free
     * exit from the pool the first time it connects, or connects directly if there is no pool.
     */
    public static BotProfile addBot(AccountStore store, String spec) {
        String[] parts = spec.split(":", 2);
        if (parts.length < 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new IllegalArgumentException("A bot is written as ClientName:AccountName or ClientName:AccountName@host:port, not '" + spec + "'.");
        }

        String clientName = parts[0].strip();
        String accountName = parts[1].strip();
        String exit = null;

        int at = accountName.indexOf('@');
        if (at >= 0) {
            exit = normaliseExit(accountName.substring(at + 1));
            accountName = accountName.substring(0, at);
        }

        String wanted = accountName;
        Optional<Account> account = store.getAccounts().stream().filter(a -> a.getName().equalsIgnoreCase(wanted)).findFirst();
        if (account.isEmpty()) {
            throw new IllegalArgumentException("There is no account named " + wanted + ", add it with --add-account first.");
        }

        BotProfile bot = new BotProfile(clientName, account.get().getId(), exit, exit == null);
        store.putBot(bot);
        store.save();
        System.out.println("Added bot " + clientName + " using account " + account.get().getName()
            + (exit == null ? "." : " through " + exit + "."));
        return bot;
    }

    public static void listBots(AccountStore store) {
        System.out.println("Accounts:");
        store.getAccounts().forEach(a -> System.out.println("  " + a.getName() + " (" + a.getUuid() + ")"));

        System.out.println("Bots:");
        for (BotProfile bot : store.getBots()) {
            String account = store.findAccount(bot.getAccountId()).map(Account::getName).orElse("(removed)");
            String exit = bot.getExit() != null ? bot.getExit() : bot.isAutoExit() ? "(picks a free exit when it connects)" : "direct";
            System.out.println("  " + bot.getName() + " -> account " + account + ", exit " + exit);
        }

        SocksPool.ParseResult pool = store.getPool().parse();
        System.out.println("SOCKS pool: " + pool.entries().size() + " entries");
    }

    /**
     * Connect through every pool entry and show the exit IP, so dead entries and entries that share an IP show up
     * before bots are started.
     * @return true if every entry works and has an exit IP of its own
     */
    public static boolean testPool(AccountStore store, ExitChecker checker) {
        SocksPool.ParseResult pool = store.getPool().parse();
        pool.errors().forEach(e -> System.err.println("SOCKS pool: " + e));

        if (pool.entries().isEmpty()) {
            System.out.println("The SOCKS pool is empty.");
            return false;
        }

        boolean allGood = pool.errors().isEmpty();
        Map<String, String> ipOwners = new HashMap<>();

        for (SocksPool.Entry entry : pool.entries()) {
            ExitChecker.Result result;
            try {
                SocksProxyDetails exit = entry.toDetails().pin();
                result = checker.check(exit);
            } catch (java.net.UnknownHostException ex) {
                result = new ExitChecker.Result(null, 0, "cannot find host " + entry.host());
            }

            if (!result.ok()) {
                allGood = false;
                System.out.println("FAIL  " + entry.key() + "  " + result.error());
                continue;
            }

            String other = ipOwners.putIfAbsent(result.ip(), entry.key());
            if (other != null) {
                allGood = false;
                System.out.println("DUP   " + entry.key() + "  " + result.ip() + " (same exit IP as " + other + ")");
            } else {
                System.out.println("OK    " + entry.key() + "  " + result.ip() + "  " + result.latencyMs() + " ms");
            }
        }
        return allGood;
    }

    private static String normaliseExit(String exit) {
        String value = exit.strip().toLowerCase(Locale.ROOT);
        return value.contains(":") ? value : value + ":1080";
    }
}
