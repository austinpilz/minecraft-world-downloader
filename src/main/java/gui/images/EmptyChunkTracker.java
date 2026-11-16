package gui.images;

import game.data.coordinates.Coordinate2D;
import game.data.coordinates.CoordinateDim2D;
import game.data.dimension.Dimension;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Remembers chunks that the server sent without any blocks in them (e.g. chunks it hasn't generated), so they can be
 * shown on the map as visited even though there was nothing to download.
 * <p>
 * Each dimension is kept in its own text file in the downloader's image cache rather than in the world itself, so it
 * has no effect on the world when it's opened in Minecraft. Coordinates are the server's chunk coordinates, they are
 * not affected by the world center offset.
 */
public class EmptyChunkTracker {
    private static final String HEADER = "# Chunks the server sent without any blocks, one 'x z' chunk coordinate per line.";

    private final Function<Dimension, Path> fileLocator;

    // chunks of the current dimension, grouped by region so a region image can quickly look up its chunks
    private final Map<Coordinate2D, Set<Coordinate2D>> chunksByRegion = new ConcurrentHashMap<>();
    private Dimension dimension;
    private boolean dirty;

    public EmptyChunkTracker(Function<Dimension, Path> fileLocator) {
        this.fileLocator = fileLocator;
    }

    /**
     * Save the chunks of the current dimension if needed, then load the chunks of the given dimension.
     */
    public synchronized void setDimension(Dimension dimension) {
        if (dimension == null || dimension.equals(this.dimension)) {
            return;
        }
        save();

        this.dimension = dimension;
        this.chunksByRegion.clear();
        this.dirty = false;
        load();
    }

    /**
     * Record whether the server sent the given chunk without any blocks.
     *
     * @return true if this changed what is stored for the chunk
     */
    public synchronized boolean setEmpty(CoordinateDim2D chunk, boolean empty) {
        if (!chunk.getDimension().equals(dimension)) {
            return false;
        }

        Coordinate2D coords = chunk.stripDimension();
        Coordinate2D region = coords.chunkToRegion();
        boolean changed;
        if (empty) {
            changed = chunksByRegion.computeIfAbsent(region, r -> ConcurrentHashMap.newKeySet()).add(coords);
        } else {
            Set<Coordinate2D> chunks = chunksByRegion.get(region);
            changed = chunks != null && chunks.remove(coords);
            if (chunks != null && chunks.isEmpty()) {
                chunksByRegion.remove(region);
            }
        }
        dirty |= changed;
        return changed;
    }

    public boolean isEmpty(CoordinateDim2D chunk) {
        if (!chunk.getDimension().equals(dimension)) {
            return false;
        }
        Coordinate2D coords = chunk.stripDimension();
        Set<Coordinate2D> chunks = chunksByRegion.get(coords.chunkToRegion());
        return chunks != null && chunks.contains(coords);
    }

    /**
     * The empty chunks within the given region, in global chunk coordinates.
     */
    public Collection<Coordinate2D> getChunksInRegion(Coordinate2D region) {
        Set<Coordinate2D> chunks = chunksByRegion.get(region);
        return chunks == null ? List.of() : new ArrayList<>(chunks);
    }

    /**
     * The regions that contain at least one empty chunk.
     */
    public Collection<Coordinate2D> getRegions() {
        return new ArrayList<>(chunksByRegion.keySet());
    }

    public int count() {
        return chunksByRegion.values().stream().mapToInt(Set::size).sum();
    }

    /**
     * Forget all empty chunks of the current dimension, used when all downloaded chunks are deleted.
     */
    public synchronized void clear() {
        chunksByRegion.clear();
        dirty = false;
    }

    private void load() {
        Path file = fileLocator.apply(dimension);
        if (!Files.isRegularFile(file)) {
            return;
        }

        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                Coordinate2D chunk = parseLine(line);
                if (chunk != null) {
                    chunksByRegion.computeIfAbsent(chunk.chunkToRegion(), r -> ConcurrentHashMap.newKeySet()).add(chunk);
                }
            }
        } catch (IOException ex) {
            System.out.println("Could not read empty chunks from " + file + ". Reason: " + ex.getMessage());
        }
    }

    static Coordinate2D parseLine(String line) {
        line = line.trim();
        if (line.isEmpty() || line.startsWith("#")) {
            return null;
        }
        String[] parts = line.split("\\s+");
        if (parts.length != 2) {
            return null;
        }
        try {
            return new Coordinate2D(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /**
     * Write the chunks of the current dimension to disk if they changed since the last save. The file is written
     * next to the old one first and then moved over it, so a crash while saving doesn't lose the old file.
     */
    public synchronized void save() {
        if (!dirty || dimension == null) {
            return;
        }

        Path file = fileLocator.apply(dimension);
        try {
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            try (BufferedWriter writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                writer.write(HEADER);
                writer.newLine();
                for (Set<Coordinate2D> chunks : chunksByRegion.values()) {
                    for (Coordinate2D chunk : chunks) {
                        writer.write(chunk.getX() + " " + chunk.getZ());
                        writer.newLine();
                    }
                }
            }
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            dirty = false;
        } catch (IOException ex) {
            System.out.println("Could not save empty chunks to " + file + ". Reason: " + ex.getMessage());
        }
    }
}
