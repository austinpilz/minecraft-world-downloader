package gui.overlay;

import config.Config;
import game.data.WorldBorderTracker.Border;
import game.data.dimension.Dimension;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import javafx.scene.paint.Color;

/**
 * Keeps track of the imported overlay layers and stores them in the config file so they are restored on startup.
 */
public class OverlayManager {
    static final String SERVER_BORDER_LABEL = "World border";

    private final List<OverlayLayer> layers = new ArrayList<>();

    // entries from the config whose file could not be read, kept so they aren't lost when the config is saved
    private final List<OverlayEntry> unavailable = new ArrayList<>();

    private final ChunkCoverage coverage = new ChunkCoverage();
    private int revision;

    // the world border the server sent, shown like an imported world border. Only used on the JavaFX thread.
    private Function<Dimension, Border> serverBorders = dimension -> null;
    private Dimension serverBorderDimension;
    private Border serverBorder;
    private OverlayShape serverBorderShape;

    public void loadFromConfig() {
        for (OverlayEntry entry : Config.getOverlays()) {
            try {
                Path path = Path.of(entry.path);
                Set<String> hiddenSets = entry.hiddenSets == null ? Set.of() : new HashSet<>(entry.hiddenSets);
                layers.add(new OverlayLayer(path, entry.dimension, read(path).getSets(), entry.visible, hiddenSets));
            } catch (Exception ex) {
                System.out.println("Could not load map overlay " + entry.path + ": " + ex.getMessage());
                unavailable.add(entry);
            }
        }
    }

    public static OverlayParser.Result read(Path path) throws IOException {
        try (Reader reader = Files.newBufferedReader(path)) {
            return OverlayParser.parse(reader);
        }
    }

    public void add(OverlayLayer layer) {
        layers.add(layer);
        revision++;
        save();
    }

    public void remove(OverlayLayer layer) {
        layers.remove(layer);
        revision++;
        save();
    }

    public void clear() {
        layers.clear();
        unavailable.clear();
        revision++;
        save();
    }

    public void save() {
        List<OverlayEntry> entries = new ArrayList<>(unavailable);
        layers.forEach(layer -> entries.add(layer.toEntry()));

        Config.setOverlays(entries);
        Config.save();
    }

    public ChunkCoverage getCoverage() {
        return coverage;
    }

    /**
     * Apply newly saved chunks to the shapes' captured counts. Must be called on the JavaFX thread.
     *
     * @param dimension the dimension the coverage is tracked for
     */
    public void updateCoverage(Dimension dimension) {
        OverlayShape border = getServerBorderShape(dimension);
        coverage.update(layers, border == null ? List.of() : List.of(border), revision);
    }

    /**
     * @param serverBorders gives the world border the server sent for a dimension, or null if there is none or it
     *                      shouldn't be shown
     */
    public void setServerBorders(Function<Dimension, Border> serverBorders) {
        this.serverBorders = serverBorders;
    }

    /**
     * The world border the server sent for the given dimension as a shape, so it gets the same axes, name and chunk
     * count as a world border imported from a file. The shape itself is invisible, the border is drawn as blue stripes
     * by {@link gui.WorldBorderRenderer}. Null if there is no border to show. Must be called on the JavaFX thread.
     */
    public OverlayShape getServerBorderShape(Dimension dimension) {
        Border border = dimension == null ? null : serverBorders.apply(dimension);
        if (!Objects.equals(border, serverBorder) || !Objects.equals(dimension, serverBorderDimension)) {
            serverBorder = border;
            serverBorderDimension = dimension;
            serverBorderShape = border == null ? null : borderShape(border);
            // the coverage index has to pick up the new shape
            revision++;
        }
        return serverBorderShape;
    }

    static OverlayShape borderShape(Border border) {
        return OverlayShape.rectangle(border.minX(), border.minZ(), border.maxX(), border.maxZ())
            .withLabel(SERVER_BORDER_LABEL)
            .withStyle(Color.TRANSPARENT, Color.TRANSPARENT, 2)
            .withAxes(true);
    }

    public List<OverlayLayer> getLayers() {
        return layers;
    }

    public void forEachVisible(Dimension dimension, Consumer<OverlayShape> consumer) {
        for (OverlayLayer layer : layers) {
            if (!layer.isVisible() || !layer.appliesTo(dimension)) {
                continue;
            }
            for (OverlaySet set : layer.getSets()) {
                if (layer.isSetVisible(set)) {
                    set.getShapes().forEach(consumer);
                }
            }
        }
    }

    /**
     * Find the visible shape at the given world position. When shapes overlap (e.g. a region inside the world border)
     * the smallest one is returned, as that is the most specific.
     *
     * @param tolerance distance in blocks within which lines and points count as hit
     */
    public OverlayShape findAt(double x, double z, double tolerance, Dimension dimension) {
        OverlayShape[] best = new OverlayShape[1];
        Consumer<OverlayShape> check = shape -> {
            if (shape.contains(x, z, tolerance) && (best[0] == null || shape.boundingArea() < best[0].boundingArea())) {
                best[0] = shape;
            }
        };

        if (Config.showOverlays()) {
            forEachVisible(dimension, check);
        }
        // checked last, so an imported border of the same size wins
        OverlayShape border = getServerBorderShape(dimension);
        if (border != null) {
            check.accept(border);
        }
        return best[0];
    }
}
