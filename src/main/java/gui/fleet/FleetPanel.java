package gui.fleet;

import config.Config;
import game.data.PlayerSession;
import game.data.WorldManager;
import gui.GuiManager;
import gui.fleet.FleetStatus.Row;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.stage.Stage;
import javafx.util.Duration;
import proxy.accounts.AccountStore;
import proxy.accounts.AccountStore.BotProfile;
import proxy.accounts.SocksPool;

/**
 * Every bot of the fleet: where it is, what it brought in, and controls to follow, stop, resume or kick it.
 */
public class FleetPanel {
    private static final String AUTO = "auto (a free pool entry)";
    private static final String DIRECT = "direct (no proxy)";

    private static FleetPanel current;

    public Button pauseAllButton;
    public Button acceptingButton;
    public Button kickAllButton;
    public Label summaryLabel;
    public TableView<Row> table;
    public Button focusButton;
    public Button savingButton;
    public Button resumeButton;
    public Button kickButton;
    public ComboBox<String> exitChoice;
    public Button exitButton;
    public Label reasonLabel;

    private final ObservableList<Row> rows = FXCollections.observableArrayList();
    private Timeline refresher;
    // the bot the exit choice was filled in for, so the refresh doesn't undo what the user picked
    private String exitChoiceFor;
    // replacing the rows clears the selection for a moment, which mustn't count as the user changing it
    private boolean refreshing;

    @FXML
    void initialize() {
        current = this;

        addColumn("Bot", 95, Row::name);
        addColumn("Status", 90, Row::status);
        addColumn("Account", 95, Row::account);
        addColumn("Exit", 130, Row::exit);
        addColumn("Exit IP", 115, Row::exitIp);
        addColumn("Dimension", 90, Row::dimension);
        addColumn("X", 55, r -> r.isOnline() ? (long) Math.floor(r.x()) : null);
        addColumn("Z", 55, r -> r.isOnline() ? (long) Math.floor(r.z()) : null);
        addColumn("Chunks", 60, r -> r.isOnline() ? r.sessionChunks() : null);
        addColumn("Total", 55, Row::totalChunks);
        addColumn("Per min", 60, r -> r.isOnline() ? r.chunksPerMinute() : null);
        addColumn("Idle", 60, r -> r.isOnline() ? FleetStatus.formatDuration(r.idleSeconds()) : "");
        addColumn("Online", 60, r -> r.isOnline() ? FleetStatus.formatDuration(r.uptimeSeconds()) : "");

        table.setItems(rows);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.setRowFactory(t -> {
            TableRow<Row> row = new TableRow<>() {
                @Override
                protected void updateItem(Row item, boolean empty) {
                    super.updateItem(item, empty);
                    highlightQuarantined(this);
                }
            };
            row.selectedProperty().addListener((obs, old, selected) -> highlightQuarantined(row));
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) {
                    focus();
                }
            });
            return row;
        });
        table.getSelectionModel().selectedItemProperty().addListener((obs, old, row) -> {
            if (!refreshing) {
                updateSelection();
            }
        });

        refresh();
        refresher = new Timeline(new KeyFrame(Duration.seconds(1), e -> refresh()));
        refresher.setCycleCount(Timeline.INDEFINITE);
        refresher.play();
    }

    /**
     * Tint quarantined bots red, unless selected so the selection still shows.
     */
    private static void highlightQuarantined(TableRow<Row> row) {
        Row item = row.getItem();
        boolean stopped = !row.isEmpty() && item != null && item.status() == FleetStatus.Status.QUARANTINED;
        row.setStyle(stopped && !row.isSelected() ? "-fx-background-color: #f4c7c3;" : "");
    }

    private <T> void addColumn(String title, double width, Function<Row, T> value) {
        TableColumn<Row, T> column = new TableColumn<>(title);
        column.setPrefWidth(width);
        column.setMinWidth(width);
        column.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(value.apply(cell.getValue())));
        table.getColumns().add(column);
    }

    /**
     * Called when the window closes.
     */
    public void close() {
        refresher.stop();
        if (current == this) {
            current = null;
        }
    }

    /**
     * Select the bot in the panel, if the panel is open. Used when the bot is clicked on the map.
     */
    public static void select(PlayerSession session) {
        if (current != null) {
            current.selectByName(session.getName());
        }
    }

    private void selectByName(String name) {
        Row row = findRow(name);
        if (row != null) {
            table.getSelectionModel().select(row);
            table.scrollTo(row);
        }
    }

    private Row findRow(String name) {
        for (Row row : rows) {
            if (row.name().equalsIgnoreCase(name)) {
                return row;
            }
        }
        return null;
    }

    private static AccountStore store() {
        return Config.proxyAccounts() ? AccountStore.get() : null;
    }

    private void refresh() {
        WorldManager manager = WorldManager.getInstance();
        Row selected = table.getSelectionModel().getSelectedItem();

        List<Row> snapshot = FleetStatus.snapshot(store(), manager.getFleetSessions(), manager::getTotalChunks,
            System.currentTimeMillis());
        refreshing = true;
        try {
            rows.setAll(snapshot);
            // keep the selection without scrolling to it, the user may be looking at another part of the table
            Row again = selected == null ? null : findRow(selected.name());
            if (again != null) {
                table.getSelectionModel().select(again);
            }
        } finally {
            refreshing = false;
        }

        long online = snapshot.stream().filter(Row::isOnline).count();
        long saving = snapshot.stream().filter(r -> r.status() == FleetStatus.Status.ONLINE).count();
        long perMinute = snapshot.stream().mapToLong(Row::chunksPerMinute).sum();
        summaryLabel.setText(String.format(Locale.ROOT, "%d online, %d saving, %d chunks/min", online, saving, perMinute));

        pauseAllButton.setText(manager.isPaused() ? "Resume all saving" : "Pause all saving");
        acceptingButton.setText(manager.isAcceptingBots() ? "Stop taking new bots" : "Take new bots");
        kickAllButton.setDisable(online == 0);
        updateSelection();
    }

    private Row selected() {
        return table.getSelectionModel().getSelectedItem();
    }

    private void updateSelection() {
        Row row = selected();
        PlayerSession session = row == null ? null : row.session();

        focusButton.setDisable(session == null || session.getDimension() == null);
        savingButton.setDisable(session == null);
        savingButton.setText(session != null && !session.isSaving() ? "Start saving" : "Stop saving");
        resumeButton.setDisable(session == null || !session.isQuarantined());
        kickButton.setDisable(session == null);
        boolean hasReason = row != null && !row.reason().isEmpty();
        reasonLabel.setText(hasReason ? row.name() + " stopped saving because " + row.reason() + ". Resume it if it is still in the same world, or kick it." : "");
        reasonLabel.setVisible(hasReason);
        reasonLabel.setManaged(hasReason);

        BotProfile bot = row == null || store() == null ? null : store().findBot(row.name()).orElse(null);
        exitChoice.setDisable(bot == null);
        exitButton.setDisable(bot == null);
        String name = bot == null ? null : bot.getName();
        if (!Objects.equals(name, exitChoiceFor)) {
            exitChoiceFor = name;
            fillExitChoice(bot);
        }
    }

    private void fillExitChoice(BotProfile bot) {
        if (bot == null) {
            exitChoice.getItems().clear();
            return;
        }

        List<String> choices = new ArrayList<>(List.of(AUTO, DIRECT));
        for (SocksPool.Entry entry : store().getPool().parse().entries()) {
            choices.add(entry.key());
        }
        if (bot.getExit() != null && !choices.contains(bot.getExit())) {
            choices.add(bot.getExit());
        }
        exitChoice.getItems().setAll(choices);

        if (bot.getExit() != null) {
            exitChoice.setValue(bot.getExit());
        } else {
            exitChoice.setValue(bot.isAutoExit() ? AUTO : DIRECT);
        }
    }

    @FXML
    void focus() {
        Row row = selected();
        if (row != null && row.session() != null && row.session().getDimension() != null) {
            WorldManager.getInstance().setFocus(row.session());
            GuiManager.followFocusedBot();
        }
    }

    @FXML
    void toggleSaving() {
        Row row = selected();
        if (row != null && row.session() != null) {
            row.session().setSaving(!row.session().isSaving());
            refresh();
        }
    }

    @FXML
    void resume() {
        Row row = selected();
        if (row != null && row.session() != null) {
            row.session().resume();
            System.out.println("Bot " + row.name() + " was resumed.");
            refresh();
        }
    }

    @FXML
    void kick() {
        Row row = selected();
        if (row != null && row.session() != null) {
            row.session().kick();
        }
    }

    @FXML
    void setExit() {
        Row row = selected();
        AccountStore store = store();
        String choice = exitChoice.getValue();
        if (row == null || store == null || choice == null) {
            return;
        }

        store.findBot(row.name()).ifPresent(bot -> {
            boolean auto = choice.equals(AUTO);
            String exit = auto || choice.equals(DIRECT) ? null : choice;
            store.putBot(new BotProfile(bot.getName(), bot.getAccountId(), exit, auto));
            store.save();
            System.out.println("Bot " + bot.getName() + " uses " + choice + " the next time it connects.");
        });
        exitChoiceFor = null;
    }

    @FXML
    void togglePauseAll() {
        WorldManager manager = WorldManager.getInstance();
        if (manager.isPaused()) {
            manager.resumeSaving();
        } else {
            manager.pauseSaving();
        }
        refresh();
    }

    @FXML
    void toggleAccepting() {
        WorldManager manager = WorldManager.getInstance();
        manager.setAcceptingBots(!manager.isAcceptingBots());
        refresh();
    }

    @FXML
    void kickAll() {
        Alert alert = new Alert(Alert.AlertType.NONE, "Disconnect every bot of the fleet?", ButtonType.CANCEL, ButtonType.YES);
        GuiManager.addIcon((Stage) alert.getDialogPane().getScene().getWindow());
        alert.setTitle("Kick all bots");
        alert.getDialogPane().getStylesheets().add(getClass().getResource("/ui/dark.css").toExternalForm());
        alert.showAndWait();

        if (alert.getResult() == ButtonType.YES) {
            WorldManager.getInstance().kickAllBots();
        }
    }
}
