package gui.overlay;

import game.data.dimension.Dimension;
import gui.GuiManager;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.stage.Window;

/**
 * Lets the user pick an overlay file, then asks which dimension it belongs to and which of its sets to show.
 */
public class OverlayImportDialog {
    private static final Pattern END_NAME = Pattern.compile("(^|[^a-z])(the_)?end([^a-z]|$)");
    private static File lastDirectory;

    /**
     * @return a status message describing the import, or null if nothing was imported
     */
    public static String show(Window owner, OverlayManager manager, Dimension current) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Import map overlay");
        chooser.getExtensionFilters().addAll(
            new FileChooser.ExtensionFilter("JSON files", "*.json"),
            new FileChooser.ExtensionFilter("All files", "*.*")
        );
        if (lastDirectory != null && lastDirectory.isDirectory()) {
            chooser.setInitialDirectory(lastDirectory);
        }

        File file = chooser.showOpenDialog(owner);
        if (file == null) {
            return null;
        }
        lastDirectory = file.getParentFile();

        Path path = file.toPath().toAbsolutePath();
        OverlayParser.Result result;
        try {
            result = OverlayManager.read(path);
        } catch (Exception ex) {
            showError("Could not read " + file.getName() + ":\n" + ex.getMessage());
            return null;
        }
        if (result.countShapes() == 0) {
            showError("No shapes found in " + file.getName() + " (" + result.getSkipped() + " entries skipped).");
            return null;
        }

        Map<String, String> dimensions = new LinkedHashMap<>();
        for (Dimension dim : Dimension.DEFAULTS) {
            dimensions.put(OverlayLayer.dimensionLabel(dim.toString()), dim.toString());
        }
        if (current != null && !Dimension.DEFAULTS.contains(current)) {
            dimensions.put(current.toString(), current.toString());
        }

        ComboBox<String> dimensionBox = new ComboBox<>();
        dimensionBox.getItems().addAll(dimensions.keySet());
        dimensionBox.setValue(OverlayLayer.dimensionLabel(guessDimension(file.getName(), current).toString()));

        VBox setBoxes = new VBox(4);
        List<CheckBox> checkBoxes = new ArrayList<>();
        for (OverlaySet set : result.getSets()) {
            CheckBox box = new CheckBox(set.getLabel() + " (" + set.getShapes().size() + ")");
            box.setSelected(true);
            box.setDisable(set.getShapes().isEmpty());
            checkBoxes.add(box);
            setBoxes.getChildren().add(box);
        }
        ScrollPane setScroll = new ScrollPane(setBoxes);
        setScroll.setFitToWidth(true);
        setScroll.setMaxHeight(240);

        VBox content = new VBox(8,
            new Label(String.format("Found %d shapes in %d sets (%s format).",
                result.countShapes(), result.getSets().size(), result.getFormat())),
            new Label("Dimension these shapes belong to:"), dimensionBox,
            new Label("Sets to show:"), setScroll
        );
        content.setPadding(new Insets(10));

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Import " + file.getName());
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, ButtonType.OK);
        style(dialog);

        if (dialog.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return null;
        }

        Set<String> hiddenSets = new HashSet<>();
        for (int i = 0; i < checkBoxes.size(); i++) {
            if (!checkBoxes.get(i).isSelected()) {
                hiddenSets.add(result.getSets().get(i).getId());
            }
        }

        String dimension = dimensions.get(dimensionBox.getValue());
        manager.add(new OverlayLayer(path, dimension, result.getSets(), true, hiddenSets));

        return String.format("Imported %d shapes from %s, %d skipped", result.countShapes(), file.getName(), result.getSkipped());
    }

    /**
     * Dynmap files are named after the world (marker_world_nether.json), so use that if it's there. Otherwise
     * assume the file is for the dimension the player is in.
     */
    static Dimension guessDimension(String fileName, Dimension current) {
        String name = fileName.toLowerCase(Locale.ROOT);
        if (name.contains("nether")) {
            return Dimension.NETHER;
        }
        if (END_NAME.matcher(name).find()) {
            return Dimension.END;
        }
        return current == null ? Dimension.OVERWORLD : current;
    }

    private static void showError(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR, message, ButtonType.OK);
        alert.setTitle("Import map overlay");
        alert.setHeaderText(null);
        style(alert);
        alert.showAndWait();
    }

    private static void style(Dialog<?> dialog) {
        GuiManager.addIcon((Stage) dialog.getDialogPane().getScene().getWindow());
        dialog.getDialogPane().getStylesheets().add(OverlayImportDialog.class.getResource("/ui/dark.css").toExternalForm());
    }
}
