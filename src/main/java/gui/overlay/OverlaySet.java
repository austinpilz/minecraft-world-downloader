package gui.overlay;

import java.util.ArrayList;
import java.util.List;

/**
 * A named group of shapes within an overlay file, e.g. "WorldGuard Regions" or "World border". Corresponds to a
 * marker set in BlueMap and Dynmap.
 */
public class OverlaySet {
    private final String id;
    private final String label;
    private final List<OverlayShape> shapes = new ArrayList<>();

    public OverlaySet(String id, String label) {
        this.id = id;
        this.label = label == null || label.isBlank() ? id : label;
    }

    public String getId() {
        return id;
    }

    public String getLabel() {
        return label;
    }

    public List<OverlayShape> getShapes() {
        return shapes;
    }
}
