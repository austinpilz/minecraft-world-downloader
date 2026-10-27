package gui.overlay;

import game.data.dimension.Dimension;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * All shapes imported from one overlay file. A layer belongs to a single dimension, since the server's world names
 * (e.g. world_survival) can't be matched to the dimension the client is in.
 */
public class OverlayLayer {
    private final Path path;
    private final String dimension;
    private final List<OverlaySet> sets;
    private final Set<String> hiddenSets;
    private boolean visible;

    public OverlayLayer(Path path, String dimension, List<OverlaySet> sets, boolean visible, Set<String> hiddenSets) {
        this.path = path;
        this.dimension = dimension;
        this.sets = sets;
        this.visible = visible;
        this.hiddenSets = new HashSet<>(hiddenSets);
    }

    public boolean appliesTo(Dimension dimension) {
        return this.dimension.equals(dimension.toString());
    }

    public Path getPath() {
        return path;
    }

    public String getName() {
        return path.getFileName().toString();
    }

    public String getDimension() {
        return dimension;
    }

    public List<OverlaySet> getSets() {
        return sets;
    }

    public boolean isVisible() {
        return visible;
    }

    public void setVisible(boolean visible) {
        this.visible = visible;
    }

    public boolean isSetVisible(OverlaySet set) {
        return !hiddenSets.contains(set.getId());
    }

    public void setSetVisible(OverlaySet set, boolean visible) {
        if (visible) {
            hiddenSets.remove(set.getId());
        } else {
            hiddenSets.add(set.getId());
        }
    }

    public int countShapes() {
        return sets.stream().mapToInt(set -> set.getShapes().size()).sum();
    }

    OverlayEntry toEntry() {
        return new OverlayEntry(path.toString(), dimension, visible, new ArrayList<>(hiddenSets));
    }

    /**
     * Readable name for the given dimension identifier, e.g. "Nether" for minecraft:the_nether.
     */
    public static String dimensionLabel(String dimension) {
        if (Dimension.OVERWORLD.toString().equals(dimension)) { return "Overworld"; }
        if (Dimension.NETHER.toString().equals(dimension)) { return "Nether"; }
        if (Dimension.END.toString().equals(dimension)) { return "End"; }
        return dimension;
    }
}
