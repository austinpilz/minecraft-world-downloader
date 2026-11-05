package proxy.accounts;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import proxy.accounts.AccountStore.Account;
import proxy.accounts.AccountStore.BotProfile;
import proxy.auth.MicrosoftAuthHandler;

import static org.assertj.core.api.Assertions.assertThat;

class AccountStoreTest {
    @TempDir
    Path dir;

    private static Account account(String name, String uuid) {
        return new Account(name, uuid, new MicrosoftAuthHandler(null, 0));
    }

    @Test
    void savedStoreIsOwnerOnlyAndRoundTrips() throws Exception {
        Path file = dir.resolve("cache").resolve("accounts.json");
        Files.createDirectories(file.getParent());

        AccountStore store = AccountStore.load(file);
        Account a = store.addAccount(account("Alice", "uuid-a"));
        store.putBot(new BotProfile("bot1", a.getId(), "nl.example:1080", false));
        store.getPool().set("nl.example\nde.example", "svc", "hunter2", true);
        store.save();

        if (Files.getFileStore(file).supportsFileAttributeView("posix")) {
            assertThat(Files.getPosixFilePermissions(file))
                .isEqualTo(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        }

        AccountStore loaded = AccountStore.load(file);
        assertThat(loaded.getAccounts()).hasSize(1);
        assertThat(loaded.findBot("BOT1")).isPresent();
        assertThat(loaded.findBot("bot1").get().getExit()).isEqualTo("nl.example:1080");
        assertThat(loaded.getPool().getPassword()).isEqualTo("hunter2");
        assertThat(loaded.findAccount(a.getId())).isPresent();
    }

    @Test
    void addingTheSameAccountTwiceReplacesItAndKeepsTheId() {
        AccountStore store = AccountStore.load(dir.resolve("a.json"));
        Account first = store.addAccount(account("Alice", "uuid-a"));
        String id = first.getId();
        Account second = store.addAccount(account("Alice", "uuid-a"));

        assertThat(store.getAccounts()).hasSize(1);
        assertThat(second.getId()).isEqualTo(id);
    }

    @Test
    void autoAssignmentGivesEachBotItsOwnExitAndKeepsIt() {
        AccountStore store = AccountStore.load(dir.resolve("a.json"));
        List<SocksPool.Entry> entries = SocksPool.parse("one.example\ntwo.example", "u", "p").entries();

        BotProfile a = new BotProfile("a", "acc", null, true);
        BotProfile b = new BotProfile("b", "acc", null, true);
        BotProfile c = new BotProfile("c", "acc", null, true);
        store.putBot(a);
        store.putBot(b);
        store.putBot(c);

        assertThat(store.assignExit(a, entries)).isPresent();
        assertThat(store.assignExit(b, entries)).isPresent();
        assertThat(store.assignExit(c, entries)).isEmpty();

        assertThat(a.getExit()).isEqualTo("one.example:1080");
        assertThat(b.getExit()).isEqualTo("two.example:1080");
        assertThat(c.getExit()).isNull();
    }

    @Test
    void poolFileReplacesEntriesAndKeepsSavedCredentialsWhenNoneAreGiven() throws Exception {
        AccountStore store = AccountStore.load(dir.resolve("a.json"));
        store.getPool().set("old.example", "svc", "pw", true);

        Path list = dir.resolve("list.txt");
        Files.writeString(list, "new.example\nnew2.example\n");
        SocksPool.ParseResult result = store.applyPoolFile(list, null, "");

        assertThat(result.entries()).hasSize(2);
        assertThat(result.entries().get(0).username()).isEqualTo("svc");
        assertThat(result.entries().get(0).password()).isEqualTo("pw");

        store.applyPoolFile(list, "other", "otherpw");
        assertThat(store.getPool().getUsername()).isEqualTo("other");
        assertThat(AccountStore.load(dir.resolve("a.json")).getPool().getPassword()).isEqualTo("otherpw");
    }

    @Test
    void unreadableFileGivesAnEmptyStore() throws Exception {
        Path file = dir.resolve("bad.json");
        Files.writeString(file, "{ this is not json");

        assertThat(AccountStore.load(file).getAccounts()).isEmpty();
    }
}
