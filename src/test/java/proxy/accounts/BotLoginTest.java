package proxy.accounts;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import proxy.accounts.AccountStore.Account;
import proxy.accounts.AccountStore.BotProfile;
import proxy.auth.AuthDetails;
import proxy.auth.MicrosoftAuthHandler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BotLoginTest {
    @TempDir
    Path dir;

    private TestHttpServer echo;
    private TestSocks5Server socksA;
    private TestSocks5Server socksB;
    private AccountStore store;
    private Account account;
    private BotLogin login;

    @BeforeEach
    void setUp() throws Exception {
        echo = new TestHttpServer("203.0.113.7", 204);
        socksA = new TestSocks5Server("svc", "pw");
        socksB = new TestSocks5Server("svc", "pw");

        store = AccountStore.load(dir.resolve("accounts.json"));
        account = store.addAccount(new Account("Alice", "uuid-a", new MicrosoftAuthHandler(null, 0)));
        store.getPool().set("127.0.0.1:" + socksA.port() + "\n127.0.0.1:" + socksB.port(), "svc", "pw", true);

        login = new BotLogin(store, new ExitChecker(List.of("http://127.0.0.1:" + echo.port() + "/")),
            a -> new AuthDetails(a.getName(), a.getUuid(), "token-" + a.getName()));
    }

    @AfterEach
    void tearDown() throws Exception {
        echo.close();
        socksA.close();
        socksB.close();
    }

    @Test
    void unknownClientNamesAreRefused() {
        assertThatThrownBy(() -> login.login("Mallory"))
            .isInstanceOf(BotLogin.Refused.class)
            .hasMessageContaining("not a bot");
    }

    @Test
    void aBotCanOnlyBeConnectedOnce() throws Exception {
        store.putBot(new BotProfile("Bot1", account.getId(), null, true));
        BotLogin.Session first = login.login("Bot1");

        assertThatThrownBy(() -> login.login("BOT1"))
            .isInstanceOf(BotLogin.Refused.class)
            .hasMessageContaining("already connected");

        login.release(first);
        assertThat(login.login("bot1").botName()).isEqualTo("Bot1");
    }

    @Test
    void refusedLoginDoesntKeepTheBotOnline() {
        store.putBot(new BotProfile("Bot1", account.getId(), "127.0.0.1:1", false));

        assertThatThrownBy(() -> login.login("Bot1")).hasMessageContaining("not in the SOCKS pool");
        assertThatThrownBy(() -> login.login("Bot1")).hasMessageContaining("not in the SOCKS pool");
    }

    @Test
    void botNamesAreCaseInsensitive() throws Exception {
        store.putBot(new BotProfile("Bot1", account.getId(), null, true));

        assertThat(login.login("bot1").botName()).isEqualTo("Bot1");
    }

    @Test
    void autoBotsGetAStickyExitThatIsSaved() throws Exception {
        BotProfile bot = new BotProfile("bot1", account.getId(), null, true);
        store.putBot(bot);

        BotLogin.Session first = login.login("bot1");
        login.release(first);
        BotLogin.Session second = login.login("bot1");

        assertThat(bot.getExit()).isEqualTo("127.0.0.1:" + socksA.port());
        assertThat(first.exit().getPort()).isEqualTo(socksA.port());
        assertThat(second.exit().getPort()).isEqualTo(socksA.port());
        assertThat(first.exit().getPinnedIp()).isEqualTo("127.0.0.1");
        assertThat(AccountStore.load(dir.resolve("accounts.json")).findBot("bot1").get().getExit())
            .isEqualTo("127.0.0.1:" + socksA.port());
    }

    @Test
    void theSessionCarriesTheBotsOwnAccount() throws Exception {
        store.putBot(new BotProfile("bot1", account.getId(), null, true));

        BotLogin.Session session = login.login("bot1");

        assertThat(session.account().getUuid()).isEqualTo("uuid-a");
        assertThat(session.account().getAccessToken()).isEqualTo("token-Alice");
        assertThat(session.exitIp()).isEqualTo("203.0.113.7");
    }

    @Test
    void twoBotsOnOneExitIpAreRefusedUntilTheFirstDisconnects() throws Exception {
        // both test exits report the same IP, as two bots behind one address would
        store.putBot(new BotProfile("bot1", account.getId(), null, true));
        store.putBot(new BotProfile("bot2", account.getId(), null, true));

        BotLogin.Session first = login.login("bot1");

        assertThatThrownBy(() -> login.login("bot2"))
            .isInstanceOf(BotLogin.Refused.class)
            .hasMessageContaining("203.0.113.7");

        login.release(first);
        assertThat(login.login("bot2").botName()).isEqualTo("bot2");
    }

    @Test
    void duplicateGuardCanBeTurnedOff() throws Exception {
        store.getPool().set(store.getPool().getEntries(), "svc", "pw", false);
        store.putBot(new BotProfile("bot1", account.getId(), null, true));
        store.putBot(new BotProfile("bot2", account.getId(), null, true));

        login.login("bot1");

        assertThat(login.login("bot2").botName()).isEqualTo("bot2");
    }

    @Test
    void aDeadExitRefusesTheLoginAndDoesNotHoldTheIp() throws Exception {
        int port = socksA.port();
        socksA.close();
        store.getPool().set("127.0.0.1:" + port, "svc", "pw", true);
        store.putBot(new BotProfile("bot1", account.getId(), null, true));

        assertThatThrownBy(() -> login.login("bot1"))
            .isInstanceOf(BotLogin.Refused.class)
            .hasMessageContaining("is not working");
    }

    @Test
    void exhaustedPoolRefusesTheThirdBot() throws Exception {
        for (String name : List.of("a", "b", "c")) {
            store.putBot(new BotProfile(name, account.getId(), null, true));
        }
        store.getPool().set(store.getPool().getEntries(), "svc", "pw", false);

        login.login("a");
        login.login("b");

        assertThatThrownBy(() -> login.login("c"))
            .isInstanceOf(BotLogin.Refused.class)
            .hasMessageContaining("already taken");
    }

    @Test
    void aBotPinnedToAnExitThatLeftThePoolIsRefused() {
        store.putBot(new BotProfile("bot1", account.getId(), "gone.example:1080", false));

        assertThatThrownBy(() -> login.login("bot1"))
            .isInstanceOf(BotLogin.Refused.class)
            .hasMessageContaining("not in the SOCKS pool");
    }

    @Test
    void aFailedAccountRefreshIsRefusedWithoutSecrets() {
        store.putBot(new BotProfile("bot1", account.getId(), null, true));
        BotLogin failing = new BotLogin(store, new ExitChecker(List.of()), a -> {
            throw new RuntimeException("refresh token expired");
        });

        assertThatThrownBy(() -> failing.login("bot1"))
            .isInstanceOf(BotLogin.Refused.class)
            .hasMessageContaining("Cannot log in Alice")
            .hasMessageContaining("refresh token expired");
    }

    @Test
    void botWithNoPoolAndNoExitConnectsDirectly() throws Exception {
        store.getPool().set("", "", "", true);
        store.putBot(new BotProfile("bot1", account.getId(), null, true));

        BotLogin.Session session = login.login("bot1");

        assertThat(session.exit()).isNull();
        assertThat(session.exitIp()).isEqualTo(ExitChecker.DIRECT);
    }
}
