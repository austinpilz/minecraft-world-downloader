package proxy.accounts;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import proxy.auth.AuthDetails;
import proxy.auth.MicrosoftAuthHandler;
import util.LocalDateTimeAdapter;
import util.PathUtils;

/**
 * The Microsoft accounts the proxy logs bots in with, the bot profiles that pick an account and an exit for each
 * bot, and the SOCKS pool. This holds refresh tokens and the pool's password, so it's kept out of config.json in
 * its own file that only the current user can read.
 */
public class AccountStore {
    private static AccountStore instance;

    private transient Path path;

    private List<Account> accounts = new ArrayList<>();
    private List<BotProfile> bots = new ArrayList<>();
    private PoolSettings pool = new PoolSettings();

    public static class Account {
        private String id;
        private String name;
        private String uuid;
        private MicrosoftAuthHandler auth;

        public Account(String name, String uuid, MicrosoftAuthHandler auth) {
            this.id = UUID.randomUUID().toString();
            this.name = name;
            this.uuid = uuid;
            this.auth = auth;
        }

        public String getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public String getUuid() {
            return uuid;
        }

        public MicrosoftAuthHandler getAuth() {
            return auth;
        }

        /**
         * Get valid details for the account, refreshing its tokens if they expired. Updates the name in case the
         * account was renamed. Save the store afterwards, since the refresh token may have changed.
         */
        public AuthDetails freshAuthDetails() {
            AuthDetails details = auth.freshAuthDetails();
            this.name = details.getUsername();
            this.uuid = details.getUuid();
            return details;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    public static class BotProfile {
        private String name;
        private String accountId;

        // the key of the pool entry the bot goes through, or null to connect directly
        private String exit;

        // if set, a free pool entry is picked the first time the bot connects, and kept after that
        private boolean autoExit = true;

        public BotProfile(String name, String accountId, String exit, boolean autoExit) {
            this.name = name;
            this.accountId = accountId;
            this.exit = exit;
            this.autoExit = autoExit;
        }

        public String getName() {
            return name;
        }

        public String getAccountId() {
            return accountId;
        }

        public String getExit() {
            return exit;
        }

        public boolean isAutoExit() {
            return autoExit;
        }

        void setExit(String exit) {
            this.exit = exit;
        }
    }

    public static class PoolSettings {
        private String entries = "";
        private String username = "";
        private String password = "";
        private boolean refuseDuplicateExitIps = true;

        public String getEntries() {
            return entries == null ? "" : entries;
        }

        public String getUsername() {
            return username == null ? "" : username;
        }

        public String getPassword() {
            return password == null ? "" : password;
        }

        public boolean refuseDuplicateExitIps() {
            return refuseDuplicateExitIps;
        }

        public void set(String entries, String username, String password, boolean refuseDuplicateExitIps) {
            this.entries = entries;
            this.username = username;
            this.password = password;
            this.refuseDuplicateExitIps = refuseDuplicateExitIps;
        }

        public SocksPool.ParseResult parse() {
            return SocksPool.parse(getEntries(), getUsername(), getPassword());
        }
    }

    public static synchronized AccountStore get() {
        if (instance == null) {
            instance = load(PathUtils.toPath("cache", "accounts.json"));
        }
        return instance;
    }

    public static AccountStore load(Path path) {
        AccountStore store = null;
        if (Files.isRegularFile(path)) {
            try {
                store = gson().fromJson(Files.readString(path, StandardCharsets.UTF_8), AccountStore.class);
            } catch (Exception ex) {
                System.err.println("Cannot read " + path + ": " + ex.getMessage());
            }
        }

        store = Objects.requireNonNullElseGet(store, AccountStore::new);
        store.path = path;
        if (store.accounts == null) { store.accounts = new ArrayList<>(); }
        if (store.bots == null) { store.bots = new ArrayList<>(); }
        if (store.pool == null) { store.pool = new PoolSettings(); }
        return store;
    }

    private static Gson gson() {
        return new GsonBuilder()
            .registerTypeAdapter(LocalDateTime.class, new LocalDateTimeAdapter())
            .setPrettyPrinting()
            .create();
    }

    public synchronized void save() {
        try {
            writeOwnerOnly(path, gson().toJson(this).getBytes(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            System.err.println("Cannot save accounts to " + path + ": " + ex.getMessage());
        }
    }

    /**
     * Write the file so that only the current user can read it. It's written to a new file that never had wider
     * permissions, then moved over the old one, so it's never readable by others even for a moment.
     */
    static void writeOwnerOnly(Path path, byte[] contents) throws IOException {
        Files.createDirectories(path.toAbsolutePath().getParent());
        Path temp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.deleteIfExists(temp);

        if (temp.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            Files.createFile(temp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } else {
            Files.createFile(temp);
            restrictToOwner(temp);
        }
        Files.write(temp, contents);

        try {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * On Windows, replace the file's ACL with a single entry for its owner. This also drops the entries inherited
     * from the folder, which would otherwise let e.g. other users read it.
     */
    private static void restrictToOwner(Path file) throws IOException {
        AclFileAttributeView view = Files.getFileAttributeView(file, AclFileAttributeView.class);
        if (view == null) {
            return;
        }

        UserPrincipal owner = Files.getOwner(file);
        AclEntry entry = AclEntry.newBuilder()
            .setType(AclEntryType.ALLOW)
            .setPrincipal(owner)
            .setPermissions(
                AclEntryPermission.READ_DATA, AclEntryPermission.WRITE_DATA, AclEntryPermission.APPEND_DATA,
                AclEntryPermission.READ_ATTRIBUTES, AclEntryPermission.WRITE_ATTRIBUTES,
                AclEntryPermission.READ_NAMED_ATTRS, AclEntryPermission.WRITE_NAMED_ATTRS,
                AclEntryPermission.READ_ACL, AclEntryPermission.WRITE_ACL,
                AclEntryPermission.DELETE, AclEntryPermission.SYNCHRONIZE
            )
            .build();
        view.setAcl(List.of(entry));
    }

    public synchronized List<Account> getAccounts() {
        return List.copyOf(accounts);
    }

    public synchronized List<BotProfile> getBots() {
        return List.copyOf(bots);
    }

    public PoolSettings getPool() {
        return pool;
    }

    /**
     * Replace the pool with the entries in a file. Credentials given here win over the saved ones, so passing none
     * keeps what was entered before. The pool is saved, since the account file is where its password is kept.
     */
    public synchronized SocksPool.ParseResult applyPoolFile(Path file, String sharedUsername, String sharedPassword) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);

        String username = sharedUsername == null || sharedUsername.isEmpty() ? pool.getUsername() : sharedUsername;
        String password = sharedPassword == null || sharedPassword.isEmpty() ? pool.getPassword() : sharedPassword;
        pool.set(text, username, password, pool.refuseDuplicateExitIps());

        save();
        return pool.parse();
    }

    public synchronized Optional<Account> findAccount(String id) {
        return accounts.stream().filter(a -> a.id.equals(id)).findFirst();
    }

    /**
     * Find the bot a client logs in as. Minecraft names aren't case-sensitive, so neither is this.
     */
    public synchronized Optional<BotProfile> findBot(String name) {
        return bots.stream().filter(b -> b.name.equalsIgnoreCase(name)).findFirst();
    }

    /**
     * Add an account, or replace the tokens of the one that's already there.
     */
    public synchronized Account addAccount(Account account) {
        for (int i = 0; i < accounts.size(); i++) {
            if (accounts.get(i).uuid.equals(account.uuid)) {
                account.id = accounts.get(i).id;
                accounts.set(i, account);
                return account;
            }
        }
        accounts.add(account);
        return account;
    }

    public synchronized void removeAccount(String id) {
        accounts.removeIf(a -> a.id.equals(id));
    }

    /**
     * Add the bot, or replace the one with the same name.
     */
    public synchronized void putBot(BotProfile bot) {
        bots.removeIf(b -> b.name.equalsIgnoreCase(bot.name));
        bots.add(bot);
    }

    public synchronized void removeBot(String name) {
        bots.removeIf(b -> b.name.equalsIgnoreCase(name));
    }

    /**
     * Pick the exit for a bot set to pick one automatically: the first pool entry no other bot has. The choice is
     * saved, so the bot keeps using the same IP from then on.
     * @return the entry, or empty if all of them are taken
     */
    synchronized Optional<SocksPool.Entry> assignExit(BotProfile bot, List<SocksPool.Entry> entries) {
        List<String> taken = bots.stream()
            .filter(b -> b != bot && b.exit != null)
            .map(b -> b.exit.toLowerCase(Locale.ROOT))
            .toList();

        Optional<SocksPool.Entry> free = entries.stream().filter(e -> !taken.contains(e.key())).findFirst();
        free.ifPresent(e -> bot.exit = e.key());
        return free;
    }
}
