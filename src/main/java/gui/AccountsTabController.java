package gui;

import config.Config;
import gui.fleet.FleetStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import proxy.accounts.AccountCommands;
import proxy.accounts.AccountStore;
import proxy.accounts.AccountStore.Account;
import proxy.accounts.AccountStore.BotProfile;
import proxy.accounts.SocksPool;
import proxy.auth.DeviceCodeLogin;
import proxy.auth.MicrosoftAuthHandler;

/**
 * The accounts the proxy logs bots in with, and the bots. Changes are saved to the account store right away, apart
 * from the checkbox, which is saved with the other settings. Tokens are never shown.
 */
public class AccountsTabController {
    private static final String AUTO = "auto";
    private static final String DIRECT = "direct";

    public CheckBox proxyAccounts;
    public ListView<Account> accountList;
    public Button addAccountButton;
    public Button checkAccountButton;
    public Button removeAccountButton;
    public HBox codeBox;
    public Hyperlink codeLink;
    public TextField codeField;
    public Label accountStatus;
    public ListView<BotProfile> botList;
    public TextField botName;
    public ComboBox<Account> botAccount;
    public ComboBox<String> botExit;
    public Button saveBotButton;
    public Button removeBotButton;
    public Label botStatus;

    private AccountStore store;
    private AtomicBoolean loginCancelled;

    @FXML
    public void initialize() {
        Config config = GuiManager.getConfig();
        store = AccountStore.get();

        proxyAccounts.setSelected(config.proxyAccounts || Config.isFleet());
        // the connection is set up once during startup, so this can't change while running
        proxyAccounts.setDisable(config.isStarted() || Config.isFleet());

        botList.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(BotProfile bot, boolean empty) {
                super.updateItem(bot, empty);
                setText(empty || bot == null ? null : describe(bot));
            }
        });
        botList.getSelectionModel().selectedItemProperty().addListener((obs, old, bot) -> editBot(bot));
        accountList.getSelectionModel().selectedItemProperty().addListener((obs, old, account) -> updateButtons());

        refresh();
    }

    public void save(Config config) {
        if (!proxyAccounts.isDisabled()) {
            config.proxyAccounts = proxyAccounts.isSelected();
        }
    }

    /**
     * Called when the tab is opened, the pool may have changed on the other tab.
     */
    public void opened() {
        refresh();
    }

    private String describe(BotProfile bot) {
        String account = store.findAccount(bot.getAccountId()).map(Account::getName).orElse("(removed account)");
        return bot.getName() + "  →  " + account + "  via " + FleetStatus.describeExit(bot);
    }

    private void refresh() {
        Account selectedAccount = accountList.getSelectionModel().getSelectedItem();
        accountList.getItems().setAll(store.getAccounts());
        if (selectedAccount != null) {
            accountList.getSelectionModel().select(selectedAccount);
        }

        BotProfile selectedBot = botList.getSelectionModel().getSelectedItem();
        botList.getItems().setAll(store.getBots());
        if (selectedBot != null) {
            store.findBot(selectedBot.getName()).ifPresent(b -> botList.getSelectionModel().select(b));
        }

        Account chosen = botAccount.getValue();
        botAccount.getItems().setAll(store.getAccounts());
        if (chosen != null && store.getAccounts().contains(chosen)) {
            botAccount.setValue(chosen);
        }

        String exit = botExit.getValue();
        List<String> exits = new ArrayList<>(List.of(AUTO, DIRECT));
        for (SocksPool.Entry entry : store.getPool().parse().entries()) {
            exits.add(entry.key());
        }
        botExit.getItems().setAll(exits);
        botExit.setValue(exit == null ? AUTO : exit);

        updateButtons();
    }

    private void updateButtons() {
        boolean loggingIn = loginCancelled != null;
        Account account = accountList.getSelectionModel().getSelectedItem();
        addAccountButton.setDisable(loggingIn);
        checkAccountButton.setDisable(account == null);
        removeAccountButton.setDisable(account == null);
    }

    @FXML
    void addAccount() {
        AtomicBoolean cancelled = new AtomicBoolean();
        loginCancelled = cancelled;
        accountStatus.setText("Asking Microsoft for a login code...");
        updateButtons();

        Thread thread = new Thread(() -> {
            String result;
            try {
                DeviceCodeLogin login = DeviceCodeLogin.start();
                Platform.runLater(() -> showCode(login));

                MicrosoftAuthHandler auth = login.await(cancelled::get);
                if (auth == null) {
                    result = "Adding the account was cancelled.";
                } else {
                    Account account = AccountCommands.storeLogin(store, auth);
                    result = "Added account " + account.getName() + ".";
                }
            } catch (InterruptedException ex) {
                result = "Adding the account was cancelled.";
            } catch (Exception ex) {
                result = "Cannot add the account: " + ex.getMessage();
            }

            String message = result;
            Platform.runLater(() -> {
                if (loginCancelled == cancelled) {
                    loginCancelled = null;
                }
                codeBox.setVisible(false);
                codeBox.setManaged(false);
                accountStatus.setText(message);
                refresh();
            });
        }, "Add account");
        thread.setDaemon(true);
        thread.start();
    }

    private void showCode(DeviceCodeLogin login) {
        codeLink.setText(login.getVerificationUri());
        codeField.setText(login.getUserCode());
        codeBox.setVisible(true);
        codeBox.setManaged(true);
        accountStatus.setText("Open the link in any browser, on any device, and enter the code. Log in with the account to add, not your own.");
    }

    @FXML
    void openCodeLink() {
        GuiManager.openWebLink(codeLink.getText());
    }

    @FXML
    void cancelAddAccount() {
        if (loginCancelled != null) {
            loginCancelled.set(true);
            accountStatus.setText("Cancelling...");
        }
    }

    /**
     * Refresh the account's tokens, to find out before a bot connects whether it has to be added again.
     */
    @FXML
    void checkAccount() {
        Account account = accountList.getSelectionModel().getSelectedItem();
        if (account == null) {
            return;
        }
        accountStatus.setText("Checking " + account.getName() + "...");
        checkAccountButton.setDisable(true);

        Thread thread = new Thread(() -> {
            String result;
            try {
                account.freshAuthDetails();
                store.save();
                result = account.getName() + " can log in.";
            } catch (Exception ex) {
                result = account.getName() + " cannot log in, add it again. " + ex.getMessage();
            }
            String message = result;
            Platform.runLater(() -> {
                accountStatus.setText(message);
                refresh();
            });
        }, "Check account");
        thread.setDaemon(true);
        thread.start();
    }

    @FXML
    void removeAccount() {
        Account account = accountList.getSelectionModel().getSelectedItem();
        if (account == null) {
            return;
        }
        store.removeAccount(account.getId());
        store.save();
        accountStatus.setText("Removed " + account.getName() + ". Bots that used it can't connect until they get another account.");
        refresh();
    }

    private void editBot(BotProfile bot) {
        removeBotButton.setDisable(bot == null);
        if (bot == null) {
            return;
        }
        botName.setText(bot.getName());
        store.findAccount(bot.getAccountId()).ifPresentOrElse(botAccount::setValue, () -> botAccount.setValue(null));
        if (bot.getExit() != null) {
            botExit.setValue(bot.getExit());
        } else {
            botExit.setValue(bot.isAutoExit() ? AUTO : DIRECT);
        }
    }

    @FXML
    void saveBot() {
        String name = botName.getText().strip();
        Account account = botAccount.getValue();
        if (name.isEmpty() || account == null) {
            botStatus.setText("A bot needs a client name and an account.");
            return;
        }
        if (!name.matches("[A-Za-z0-9_]{1,16}")) {
            botStatus.setText("The client name must be 1 to 16 letters, digits or underscores, like a Minecraft name.");
            return;
        }

        String exit = botExit.getValue() == null ? AUTO : botExit.getValue().strip().toLowerCase(Locale.ROOT);
        boolean auto = exit.isEmpty() || exit.equals(AUTO);
        String fixedExit = auto || exit.equals(DIRECT) ? null : (exit.contains(":") ? exit : exit + ":1080");

        store.putBot(new BotProfile(name, account.getId(), fixedExit, auto));
        store.save();
        botStatus.setText("Saved bot " + name + ". A bot that is online uses the change the next time it connects.");
        refresh();
    }

    @FXML
    void removeBot() {
        BotProfile bot = botList.getSelectionModel().getSelectedItem();
        if (bot == null) {
            return;
        }
        store.removeBot(bot.getName());
        store.save();
        botStatus.setText("Removed bot " + bot.getName() + ".");
        refresh();
    }
}
