package gui.fleet;

import game.data.PlayerSession;
import gui.fleet.FleetStatus.Row;
import gui.fleet.FleetStatus.Status;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import proxy.accounts.AccountStore;
import proxy.accounts.AccountStore.Account;
import proxy.accounts.AccountStore.BotProfile;
import proxy.auth.MicrosoftAuthHandler;

import static org.assertj.core.api.Assertions.assertThat;

class FleetStatusTest {
    @TempDir
    Path dir;

    @Test
    void listsOnlineAndOfflineBotsByName() {
        AccountStore store = AccountStore.load(dir.resolve("accounts.json"));
        Account account = store.addAccount(new Account("Alt1", "uuid-1", new MicrosoftAuthHandler(null, 0)));
        store.putBot(new BotProfile("charlie", account.getId(), null, true));
        store.putBot(new BotProfile("Alpha", account.getId(), "nl.example:1080", false));
        store.putBot(new BotProfile("bravo", "gone", null, false));

        PlayerSession alpha = PlayerSession.fleet("alpha");
        alpha.setAccountName("Alt1");
        alpha.setExit("nl.example:1080");
        alpha.setExitIp("203.0.113.5");
        alpha.quarantine("the server moved it to another server");
        PlayerSession stranger = PlayerSession.fleet("Delta");

        Map<String, Long> totals = Map.of("alpha", 40L, "bravo", 12L);
        long now = alpha.getConnectedAt() + 125_000;
        List<Row> rows = FleetStatus.snapshot(store, List.of(stranger, alpha),
            name -> totals.getOrDefault(name.toLowerCase(), 0L), now);

        assertThat(rows).extracting(Row::name).containsExactly("alpha", "bravo", "charlie", "Delta");

        Row a = rows.get(0);
        assertThat(a.isOnline()).isTrue();
        assertThat(a.status()).isEqualTo(Status.QUARANTINED);
        assertThat(a.reason()).contains("another server");
        assertThat(a.exitIp()).isEqualTo("203.0.113.5");
        assertThat(a.totalChunks()).isEqualTo(40);
        assertThat(a.uptimeSeconds()).isEqualTo(125);
        assertThat(a.idleSeconds()).isEqualTo(125);

        Row b = rows.get(1);
        assertThat(b.isOnline()).isFalse();
        assertThat(b.status()).isEqualTo(Status.OFFLINE);
        assertThat(b.account()).isEqualTo("(removed)");
        assertThat(b.exit()).isEqualTo("direct");
        assertThat(b.totalChunks()).isEqualTo(12);

        assertThat(rows.get(2).exit()).isEqualTo("auto");
        assertThat(rows.get(2).account()).isEqualTo("Alt1");
        assertThat(rows.get(3).status()).isEqualTo(Status.CONNECTING);
    }

    @Test
    void worksWithoutAccounts() {
        PlayerSession bot = PlayerSession.fleet("solo");
        bot.setSaving(false);

        List<Row> rows = FleetStatus.snapshot(null, List.of(bot), name -> 0, bot.getConnectedAt());

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).status()).isEqualTo(Status.SAVING_OFF);
        assertThat(rows.get(0).exit()).isEmpty();
    }

    @Test
    void formatsDurations() {
        assertThat(FleetStatus.formatDuration(7)).isEqualTo("0m 07s");
        assertThat(FleetStatus.formatDuration(187)).isEqualTo("3m 07s");
        assertThat(FleetStatus.formatDuration(3900)).isEqualTo("1h 05m");
    }
}
