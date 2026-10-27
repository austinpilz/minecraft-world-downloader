package gui.overlay;

import config.Config;
import game.data.dimension.Dimension;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Keeps track of the imported overlay layers and stores them in the config file so they are restored on startup.
 */
public class OverlayManager {
    private final List<OverlayLayer> layers = new ArrayList<>();

    // entries from the config whose file could not be read, kept so they aren't lost when the config is saved
    private final List<OverlayEntry> unavailable = new ArrayList<>();

    private final ChunkCoverage coverage = new ChunkCoverage();
    private int revision;

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
     */
    public void updateCoverage() {
        coverage.update(layers, revision);
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
        if (!Config.showOverlays()) {
            return null;
        }

        OverlayShape[] best = new OverlayShape[1];
        forEachVisible(dimension, shape -> {
            if (shape.contains(x, z, tolerance) && (best[0] == null || shape.boundingArea() < best[0].boundingArea())) {
                best[0] = shape;
            }
        });
        return best[0];
    }
}
