package gui.overlay;

import config.Config;
import game.data.coordinates.Coordinate2D;
import game.data.dimension.Dimension;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import util.PathUtils;

/**
 * Tracks which chunks of the current dimension have been saved, so shapes whose chunks have all been captured can be
 * drawn as done. Chunks already in the world folder are read from the region file headers when the dimension changes,
 * chunks saved after that are reported as they are written.
 *
 * Other threads only hand over chunks through a queue, the captured set and the per-shape counts are only touched on
 * the JavaFX thread in {@link #update}.
 */
public class ChunkCoverage {
    // larger shapes (e.g. a world border) are not tracked, they won't be completed anyway and would cost a lot of memory
    static final int MAX_SHAPE_CHUNKS = 256 * 256;

    private static final int REGION_HEADER_SIZE = 4096;

    private final Queue<Batch> pending = new ConcurrentLinkedQueue<>();
    private volatile Dimension requestedDimension;

    private Dimension dimension;
    private final Set<Long> captured = new HashSet<>();

    // tracked shapes of the current dimension, indexed by the regions they overlap
    private final Map<Long, List<OverlayShape>> shapesByRegion = new HashMap<>();
    private int layerRevision = -1;
    private boolean rebuild = true;

    private record Batch(Dimension dimension, long[] chunks, boolean reset) { }

    public static long key(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    private static long regionKey(long chunk) {
        return key((int) (chunk >> 32) >> 5, (int) chunk >> 5);
    }

    /**
     * Start tracking the given dimension, reading the chunks that are already saved in a background thread.
     */
    public void setDimension(Dimension dimension) {
        if (dimension.equals(requestedDimension)) {
            return;
        }
        reset(dimension);

        Thread thread = new Thread(() -> readSavedChunks(dimension), "Overlay Coverage Scan");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Forget all captured chunks and start tracking the given dimension, without reading the world folder.
     */
    void reset(Dimension dimension) {
        requestedDimension = dimension;
        pending.add(new Batch(dimension, new long[0], true));
    }

    /**
     * Called (from any thread) when a chunk has been saved.
     */
    public void chunkSaved(Dimension dimension, Coordinate2D chunk) {
        pending.add(new Batch(dimension, new long[]{key(chunk.getX(), chunk.getZ())}, false));
    }

    private void readSavedChunks(Dimension dimension) {
        File[] files = PathUtils.toPath(Config.getWorldOutputDir(), dimension.getPath(), "region").toFile()
            .listFiles((dir, name) -> name.endsWith(".mca"));
        if (files == null) {
            return;
        }

        for (File file : files) {
            String[] parts = file.getName().split("\\.");
            if (parts.length != 4) {
                continue;
            }
            try (InputStream input = new FileInputStream(file)) {
                // region files are stored relative to the configured world center
                Coordinate2D region = new Coordinate2D(Integer.parseInt(parts[1]), Integer.parseInt(parts[2])).offsetRegionToActual();
                long[] chunks = chunksInHeader(input.readNBytes(REGION_HEADER_SIZE), region.getX(), region.getZ());
                if (chunks.length > 0) {
                    pending.add(new Batch(dimension, chunks, false));
                }
            } catch (IOException | NumberFormatException ex) {
                // unreadable files are skipped, their chunks just show as not captured
            }
        }
    }

    /**
     * Read which chunks are present from the location table at the start of a region file. Each chunk has a 4 byte
     * entry, of which the last byte is the size in sectors, 0 meaning the chunk isn't there.
     */
    static long[] chunksInHeader(byte[] header, int regionX, int regionZ) {
        List<Long> res = new ArrayList<>();
        for (int i = 0; i + 3 < header.length && i < REGION_HEADER_SIZE; i += 4) {
            if (header[i + 3] == 0) {
                continue;
            }
            int index = i / 4;
            res.add(key(regionX * 32 + (index & 31), regionZ * 32 + (index >> 5)));
        }
        return res.stream().mapToLong(Long::longValue).toArray();
    }

    /**
     * Apply chunks saved since the last call and update the captured count of every shape. Must be called on the
     * JavaFX thread.
     *
     * @param revision changes whenever layers are added or removed, so the shape index is rebuilt
     */
    public void update(List<OverlayLayer> layers, int revision) {
        Batch batch;
        while ((batch = pending.poll()) != null) {
            if (batch.reset) {
                dimension = batch.dimension;
                captured.clear();
                rebuild = true;
                continue;
            }
            if (!batch.dimension.equals(dimension)) {
                continue;
            }
            for (long chunk : batch.chunks) {
                if (captured.add(chunk) && !rebuild) {
                    countChunk(chunk);
                }
            }
        }

        if (rebuild || revision != layerRevision) {
            rebuild = false;
            layerRevision = revision;
            rebuildIndex(layers);
        }
    }

    private void countChunk(long chunk) {
        List<OverlayShape> shapes = shapesByRegion.get(regionKey(chunk));
        if (shapes == null) {
            return;
        }
        for (OverlayShape shape : shapes) {
            if (Arrays.binarySearch(shape.getChunks(), chunk) >= 0) {
                shape.setCapturedChunks(shape.getCapturedChunks() + 1);
            }
        }
    }

    private void rebuildIndex(List<OverlayLayer> layers) {
        shapesByRegion.clear();
        if (dimension == null) {
            return;
        }

        for (OverlayLayer layer : layers) {
            if (!layer.appliesTo(dimension)) {
                continue;
            }
            for (OverlaySet set : layer.getSets()) {
                for (OverlayShape shape : set.getShapes()) {
                    index(shape);
                }
            }
        }
    }

    private void index(OverlayShape shape) {
        long[] chunks = shape.getChunks();
        if (chunks == null) {
            return;
        }

        int count = 0;
        Set<Long> regions = new HashSet<>();
        for (long chunk : chunks) {
            if (captured.contains(chunk)) {
                count++;
            }
            regions.add(regionKey(chunk));
        }
        shape.setCapturedChunks(count);

        for (long region : regions) {
            shapesByRegion.computeIfAbsent(region, k -> new ArrayList<>()).add(shape);
        }
    }
}
