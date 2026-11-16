package gui;

import config.Config;
import game.data.WorldManager;
import game.data.dimension.Dimension;
import game.data.route.RoutePlanner;
import game.data.route.RouteService;
import gui.overlay.OverlayManager;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/**
 * Asks for the view distance, buffer and chunk budget, then plans a route over the shown overlay shapes on a
 * background thread.
 */
public class RoutePlanDialog {
    private RoutePlanDialog() { }

    /**
     * @param status receives progress messages, called on the JavaFX thread
     */
    public static void show(Dimension dimension, Consumer<String> status) {
        OverlayManager.Selection selection = OverlayManager.getInstance().collectVisibleChunks(dimension);
        int serverRadius = WorldManager.getInstance().getServerViewDistance();

        Spinner<Integer> radius = new Spinner<>(RoutePlanner.MIN_RADIUS, RoutePlanner.MAX_RADIUS, RouteService.defaultRadius());
        radius.setEditable(true);
        Spinner<Integer> buffer = new Spinner<>(0, 16, Config.getRouteBufferChunks());
        buffer.setEditable(true);
        Spinner<Integer> budget = new Spinner<>(0, 1_000_000, Config.getRouteChunkBudget(), 100);
        budget.setEditable(true);
        CheckBox skipCaptured = new CheckBox("Leave out chunks that are already saved");
        skipCaptured.setSelected(true);

        String shown = selection.chunks().length == 0
            ? "No overlay shapes are shown for this dimension. Import or show an overlay first."
            : String.format("Shown: %d shapes in %d sets, %,d chunks.", selection.shapes(), selection.sets(), selection.chunks().length);
        String serverHint = serverRadius > 0 ? " (server reports " + serverRadius + ")" : " (server view distance not known yet)";

        VBox content = new VBox(8,
            new Label(shown),
            new Label("View distance in chunks" + serverHint + ":"), radius,
            new Label("Extra chunks to capture around every shape:"), buffer,
            new Label("Chunk budget per minute, 0 for no limit:"), budget,
            skipCaptured,
            new Label("The route is drawn on the map once planned. Start it from the Route menu.")
        );
        content.setPadding(new Insets(10));

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Plan route");
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, ButtonType.OK);
        GuiManager.addIcon((Stage) dialog.getDialogPane().getScene().getWindow());
        dialog.getDialogPane().getStylesheets().add(RoutePlanDialog.class.getResource("/ui/dark.css").toExternalForm());

        if (dialog.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK || selection.chunks().length == 0) {
            return;
        }

        int radiusValue = radius.getValue();
        int bufferValue = buffer.getValue();
        int budgetValue = budget.getValue();
        boolean skip = skipCaptured.isSelected();
        Config.setRouteRadius(radiusValue);
        Config.setRouteBufferChunks(bufferValue);
        Config.setRouteChunkBudget(budgetValue);
        Config.save();

        status.accept("Planning route...");
        Thread thread = new Thread(() -> {
            String result;
            try {
                result = RouteService.describe(RouteService.plan(radiusValue, bufferValue, skip, RoutePlanner.DEFAULT_OPTIMISE_MILLIS));
            } catch (Exception ex) {
                result = "Could not plan route: " + ex.getMessage();
            }
            String message = result;
            Platform.runLater(() -> status.accept(message));
        }, "Route Planner");
        thread.setDaemon(true);
        thread.start();
    }
}
