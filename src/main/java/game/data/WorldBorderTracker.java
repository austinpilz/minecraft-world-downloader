package game.data;

import game.data.dimension.Dimension;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Remembers the world border the server sent for each dimension, so it can be drawn on the map, also in later
 * sessions.
 * <p>
 * Borders are kept in small text files in the downloader's image cache rather than in the world, so the downloaded
 * world keeps Minecraft's default border. Coordinates are the server's, not affected by the world center offset.
 * Only where the border ends up is kept, a border that is moving is shown at its destination.
 */
public class WorldBorderTracker {
    private static final String HEADER = "# World border the server sent for this dimension: centerX centerZ size";

    public record Border(double centerX, double centerZ, double size) {
        public double minX() {
            return centerX - size / 2;
        }

        public double maxX() {
            return centerX + size / 2;
        }

        public double minZ() {
            return centerZ - size / 2;
        }

        public double maxZ() {
            return centerZ + size / 2;
        }
    }

    private final Function<Dimension, Path> fileLocator;
    // loaded on first use, empty if no border is known for the dimension
    private final Map<Dimension, Optional<Border>> borders = new ConcurrentHashMap<>();

    public WorldBorderTracker(Function<Dimension, Path> fileLocator) {
        this.fileLocator = fileLocator;
    }

    /**
     * The border of the given dimension, or null if the server never sent one.
     */
    public Border get(Dimension dimension) {
        if (dimension == null) {
            return null;
        }
        return borders.computeIfAbsent(dimension, this::load).orElse(null);
    }

    public void setBorder(Dimension dimension, double centerX, double centerZ, double size) {
        update(dimension, new Border(centerX, centerZ, size));
    }

    public void setCenter(Dimension dimension, double centerX, double centerZ) {
        Border current = get(dimension);
        if (current != null) {
            update(dimension, new Border(centerX, centerZ, current.size()));
        }
    }

    public void setSize(Dimension dimension, double size) {
        Border current = get(dimension);
        if (current != null) {
            update(dimension, new Border(current.centerX(), current.centerZ(), size));
        }
    }

    /**
     * Forget all borders, used when the downloaded world (and with it the image cache) is deleted.
     */
    public void clear() {
        borders.clear();
    }

    private synchronized void update(Dimension dimension, Border border) {
        if (dimension == null || !Double.isFinite(border.centerX()) || !Double.isFinite(border.centerZ())
            || !Double.isFinite(border.size()) || border.size() <= 0) {
            return;
        }
        if (border.equals(get(dimension))) {
            return;
        }
        borders.put(dimension, Optional.of(border));
        save(dimension, border);
    }

    private Optional<Border> load(Dimension dimension) {
        Path file = fileLocator.apply(dimension);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                Border border = parseLine(line);
                if (border != null) {
                    return Optional.of(border);
                }
            }
        } catch (IOException ex) {
            System.out.println("Could not read world border from " + file + ". Reason: " + ex.getMessage());
        }
        return Optional.empty();
    }

    static Border parseLine(String line) {
        line = line.trim();
        if (line.isEmpty() || line.startsWith("#")) {
            return null;
        }
        String[] parts = line.split("\\s+");
        if (parts.length != 3) {
            return null;
        }
        try {
            Border border = new Border(Double.parseDouble(parts[0]), Double.parseDouble(parts[1]), Double.parseDouble(parts[2]));
            boolean valid = Double.isFinite(border.centerX()) && Double.isFinite(border.centerZ())
                && Double.isFinite(border.size()) && border.size() > 0;
            return valid ? border : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /**
     * Written next to the old file first and then moved over it, so a crash while saving doesn't lose the old one.
     */
    private void save(Dimension dimension, Border border) {
        Path file = fileLocator.apply(dimension);
        try {
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(temp, List.of(HEADER, border.centerX() + " " + border.centerZ() + " " + border.size()), StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            System.out.println("Could not save world border to " + file + ". Reason: " + ex.getMessage());
        }
    }
}
