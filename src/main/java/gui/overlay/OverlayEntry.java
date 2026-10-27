package gui.overlay;

import java.util.ArrayList;
import java.util.List;

/**
 * An imported overlay as it is stored in config.json. The file itself is read again on startup.
 */
public class OverlayEntry {
    public String path;
    public String dimension;
    public boolean visible = true;
    public List<String> hiddenSets = new ArrayList<>();

    public OverlayEntry() { }

    public OverlayEntry(String path, String dimension, boolean visible, List<String> hiddenSets) {
        this.path = path;
        this.dimension = dimension;
        this.visible = visible;
        this.hiddenSets = hiddenSets;
    }
}
