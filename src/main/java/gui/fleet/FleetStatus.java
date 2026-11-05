package gui.fleet;

import game.data.PlayerSession;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.ToLongFunction;
import java.util.stream.Collectors;
import proxy.accounts.AccountStore;
import proxy.accounts.AccountStore.BotProfile;

/**
 * What the Fleet panel shows: a row for every bot that is set up or online. Kept free of JavaFX so it can be tested.
 */
public final class FleetStatus {
    private FleetStatus() { }

    public enum Status {
        OFFLINE("Offline"),
        CONNECTING("Connecting"),
        ONLINE("Saving"),
        SAVING_OFF("Saving off"),
        QUARANTINED("Quarantined");

        private final String label;

        Status(String label) {
            this.label = label;
        }

        static Status of(PlayerSession.State state) {
            return switch (state) {
                case CONNECTING -> CONNECTING;
                case ONLINE -> ONLINE;
                case SAVING_OFF -> SAVING_OFF;
                case QUARANTINED -> QUARANTINED;
            };
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /**
     * One bot. The session is null while the bot is offline.
     * @param exit the exit it goes through, or for an offline bot the exit it will use
     * @param idleSeconds seconds since the server last sent the bot anything
     */
    public record Row(String name, String account, String exit, String exitIp, Status status, String dimension,
                      double x, double z, long sessionChunks, long totalChunks, long chunksPerMinute,
                      long idleSeconds, long uptimeSeconds, String reason, PlayerSession session) {
        public boolean isOnline() {
            return session != null;
        }
    }

    /**
     * @param store the bots that are set up, may be null when the proxy doesn't hold the accounts
     * @param sessions the bots that are online
     * @param totalChunks the chunks a bot brought in since the proxy started, by bot name
     */
    public static List<Row> snapshot(AccountStore store, List<PlayerSession> sessions,
                                     ToLongFunction<String> totalChunks, long now) {
        List<Row> rows = new ArrayList<>();
        for (PlayerSession session : sessions) {
            rows.add(onlineRow(session, totalChunks, now));
        }

        if (store != null) {
            Set<String> online = sessions.stream().map(s -> key(s.getName())).collect(Collectors.toSet());
            for (BotProfile bot : store.getBots()) {
                if (!online.contains(key(bot.getName()))) {
                    rows.add(offlineRow(store, bot, totalChunks));
                }
            }
        }

        rows.sort(Comparator.comparing(r -> key(r.name())));
        return rows;
    }

    private static Row onlineRow(PlayerSession session, ToLongFunction<String> totalChunks, long now) {
        String dimension = session.getDimension() == null ? "" : session.getDimension().getName().replaceFirst("^minecraft:", "");
        long idle = session.getLastPacketAt() == 0 ? 0 : Math.max(0, now - session.getLastPacketAt()) / 1000;
        long uptime = Math.max(0, now - session.getConnectedAt()) / 1000;

        return new Row(session.getName(), orEmpty(session.getAccountName()), orEmpty(session.getExit()),
            orEmpty(session.getExitIp()), Status.of(session.getState()), dimension, session.getX(), session.getZ(),
            session.getChunksReceived(), totalChunks.applyAsLong(session.getName()), session.getChunksPerMinute(),
            idle, uptime, orEmpty(session.getQuarantineReason()), session);
    }

    private static Row offlineRow(AccountStore store, BotProfile bot, ToLongFunction<String> totalChunks) {
        String account = store.findAccount(bot.getAccountId()).map(AccountStore.Account::getName).orElse("(removed)");
        return new Row(bot.getName(), account, describeExit(bot), "", Status.OFFLINE, "", 0, 0, 0,
            totalChunks.applyAsLong(bot.getName()), 0, 0, 0, "", null);
    }

    /**
     * The exit a bot is set up to use.
     */
    public static String describeExit(BotProfile bot) {
        if (bot.getExit() != null) {
            return bot.getExit();
        }
        return bot.isAutoExit() ? "auto" : "direct";
    }

    /**
     * Seconds as a short duration, like 1h 05m or 3m 07s.
     */
    public static String formatDuration(long seconds) {
        if (seconds >= 3600) {
            return String.format(Locale.ROOT, "%dh %02dm", seconds / 3600, seconds / 60 % 60);
        }
        return String.format(Locale.ROOT, "%dm %02ds", seconds / 60, seconds % 60);
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
