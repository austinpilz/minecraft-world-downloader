package gui;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import proxy.accounts.AccountCommands;
import proxy.accounts.AccountStore;
import proxy.accounts.AccountStore.PoolSettings;
import proxy.accounts.ExitChecker;
import proxy.accounts.SocksPool;

/**
 * The SOCKS5 exits the bots use, pasted as a list with one set of credentials for all of them. Saved to the account
 * store rather than the config, since it holds a password.
 */
public class SocksPoolTabController {
    public TextArea entries;
    public TextField username;
    public PasswordField password;
    public CheckBox refuseDuplicates;
    public Button testButton;
    public Label poolStatus;
    public TextArea results;

    private AccountStore store;

    @FXML
    public void initialize() {
        store = AccountStore.get();
        PoolSettings pool = store.getPool();

        entries.setText(pool.getEntries());
        username.setText(pool.getUsername());
        password.setText(pool.getPassword());
        refuseDuplicates.setSelected(pool.refuseDuplicateExitIps());

        entries.textProperty().addListener((obs, old, text) -> showCount());
        showCount();
    }

    private SocksPool.ParseResult parse() {
        return SocksPool.parse(entries.getText(), username.getText().strip(), password.getText());
    }

    private void showCount() {
        SocksPool.ParseResult pool = parse();
        String text = pool.entries().size() + (pool.entries().size() == 1 ? " entry" : " entries");
        if (!pool.errors().isEmpty()) {
            text += ", " + pool.errors().size() + " lines can't be read";
        }
        poolStatus.setText(text);
    }

    /**
     * Save the pool to the account store, if it changed. The bots use it from the next one that connects.
     */
    public void save() {
        PoolSettings pool = store.getPool();
        String newEntries = entries.getText();
        String newUsername = username.getText().strip();
        String newPassword = password.getText();
        boolean newRefuse = refuseDuplicates.isSelected();

        boolean changed = !newEntries.equals(pool.getEntries()) || !newUsername.equals(pool.getUsername())
            || !newPassword.equals(pool.getPassword()) || newRefuse != pool.refuseDuplicateExitIps();
        if (changed) {
            pool.set(newEntries, newUsername, newPassword, newRefuse);
            store.save();
        }
    }

    @FXML
    void testPool() {
        SocksPool.ParseResult pool = parse();
        testButton.setDisable(true);
        results.clear();
        poolStatus.setText("Testing " + pool.entries().size() + " entries...");

        Thread thread = new Thread(() -> {
            boolean allGood = AccountCommands.testPool(pool, new ExitChecker(),
                line -> Platform.runLater(() -> results.appendText(line + "\n")));

            Platform.runLater(() -> {
                testButton.setDisable(false);
                poolStatus.setText(allGood ? "Every entry works and has its own exit IP." : "Some entries don't work or share an exit IP, see below.");
            });
        }, "Test SOCKS pool");
        thread.setDaemon(true);
        thread.start();
    }
}
