package proxy.accounts;

import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import proxy.SocksProxyDetails;
import proxy.accounts.AccountStore.Account;
import proxy.accounts.AccountStore.BotProfile;
import proxy.auth.AuthDetails;

/**
 * Decides whether a client may log in as a bot, and with what: the bot's account, and the exit its traffic leaves
 * from. The client leg is unauthenticated, so refusing names that aren't a known bot is the access control.
 */
public class BotLogin {
    /**
     * What a bot connects with.
     * @param exit   the pinned SOCKS exit, or null to connect directly
     * @param exitIp the IP the server will see
     */
    public record Session(String botName, AuthDetails account, SocksProxyDetails exit, String exitIp) { }

    /**
     * A login that can't go ahead. The message is shown to the client, so it has to be safe for the bot's owner to read.
     */
    public static class Refused extends Exception {
        public Refused(String message) {
            super(message);
        }
    }

    private final AccountStore store;
    private final ExitChecker exitChecker;
    private final Function<Account, AuthDetails> authProvider;
    private final Set<String> onlineExitIps = ConcurrentHashMap.newKeySet();
    private final Set<String> onlineBots = ConcurrentHashMap.newKeySet();

    public BotLogin(AccountStore store, ExitChecker exitChecker) {
        this(store, exitChecker, Account::freshAuthDetails);
    }

    public BotLogin(AccountStore store, ExitChecker exitChecker, Function<Account, AuthDetails> authProvider) {
        this.store = store;
        this.exitChecker = exitChecker;
        this.authProvider = authProvider;
    }

    /**
     * Check the client name against the bot list and get everything needed to connect for it. On success the exit
     * IP counts as in use, and the bot as online, until {@link #release} is called. Several clients can log in at
     * the same time, but not as the same bot.
     */
    public Session login(String clientName) throws Refused {
        BotProfile bot = store.findBot(clientName)
            .orElseThrow(() -> new Refused("\"" + clientName + "\" is not a bot of this proxy."));

        String botKey = bot.getName().toLowerCase(Locale.ROOT);
        if (!onlineBots.add(botKey)) {
            throw new Refused("Bot " + bot.getName() + " is already connected.");
        }
        try {
            return login(bot);
        } catch (Refused | RuntimeException ex) {
            onlineBots.remove(botKey);
            throw ex;
        }
    }

    private Session login(BotProfile bot) throws Refused {
        Account account = store.findAccount(bot.getAccountId())
            .orElseThrow(() -> new Refused("The account of bot " + bot.getName() + " was removed from the proxy."));

        SocksPool.Entry entry = chooseExit(bot);

        AuthDetails details;
        try {
            details = authProvider.apply(account);
        } catch (RuntimeException ex) {
            throw new Refused("Cannot log in " + account.getName() + ": " + ex.getMessage());
        } finally {
            // refreshing can replace the refresh token
            store.save();
        }

        SocksProxyDetails exit = null;
        if (entry != null) {
            try {
                exit = entry.toDetails().pin();
            } catch (UnknownHostException ex) {
                throw new Refused("Cannot find the exit " + entry.key() + " for bot " + bot.getName() + ".");
            }
        }

        ExitChecker.Result result = exitChecker.check(exit);
        if (!result.ok()) {
            throw new Refused("The exit " + entry.key() + " for bot " + bot.getName() + " is not working: " + result.error());
        }

        if (store.getPool().refuseDuplicateExitIps() && !onlineExitIps.add(result.ip())) {
            throw new Refused("Another bot is already connected from " + result.ip() + ", the exit IP of bot " + bot.getName() + ".");
        }

        return new Session(bot.getName(), details, exit, result.ip());
    }

    /**
     * The bot disconnected, so its exit IP can be used by another bot again.
     */
    public void release(Session session) {
        if (session != null) {
            onlineExitIps.remove(session.exitIp());
            onlineBots.remove(session.botName().toLowerCase(Locale.ROOT));
        }
    }

    /**
     * Get the pool entry a bot leaves from: the one saved for it, or a free one for bots that pick their own. Null
     * means the bot connects directly.
     */
    private SocksPool.Entry chooseExit(BotProfile bot) throws Refused {
        SocksPool.ParseResult pool = store.getPool().parse();

        String key = bot.getExit();
        if (key == null && bot.isAutoExit() && !pool.entries().isEmpty()) {
            // bots logging in at the same time must not be given the same exit
            synchronized (store) {
                SocksPool.Entry assigned = store.assignExit(bot, pool.entries())
                    .orElseThrow(() -> new Refused("Every exit in the SOCKS pool is already taken by another bot."));
                store.save();
                return assigned;
            }
        }
        if (key == null) {
            return null;
        }

        String wanted = key.toLowerCase(Locale.ROOT);
        return pool.entries().stream()
            .filter(e -> e.key().equals(wanted))
            .findFirst()
            .orElseThrow(() -> new Refused("The exit " + key + " of bot " + bot.getName() + " is not in the SOCKS pool."));
    }
}
