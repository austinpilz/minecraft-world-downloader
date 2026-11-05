package gui.images;

import game.data.coordinates.Coordinate2D;
import game.data.dimension.Dimension;
import gui.Bounds;
import gui.overlay.ChunkCoverage;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import javafx.scene.image.Image;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;

/**
 * Lightweight alternative to the rendered map: one pixel per chunk, green once the chunk is saved and grey while it has
 * only been received. No chunk images are generated and nothing is written to the image cache, so this costs a small
 * fraction of the CPU and memory of the normal map.
 *
 * Fed by {@link ChunkCoverage}, which also reports chunks saved in earlier runs. Only used on the JavaFX thread.
 */
public class CoverageImageHandler implements ChunkCoverage.Listener {
    static final Color SAVED = Color.rgb(76, 175, 80);
    static final Color SEEN = Color.rgb(130, 130, 130);

    private static final int REGION_CHUNKS = 32;
    // side length of a region in blocks, see RegionImageHandler
    private static final int REGION_BLOCK_SIZE = REGION_CHUNKS * 16;

    private final Map<Long, WritableImage> regions = new HashMap<>();

    @Override
    public void reset(Dimension dimension) {
        regions.clear();
    }

    @Override
    public void chunkSaved(long chunk) {
        colour(chunk, SAVED);
    }

    @Override
    public void chunkSeen(long chunk) {
        colour(chunk, SEEN);
    }

    private void colour(long chunk, Color color) {
        int x = (int) (chunk >> 32);
        int z = (int) chunk;

        WritableImage image = regions.computeIfAbsent(
            ChunkCoverage.key(x >> 5, z >> 5), k -> new WritableImage(REGION_CHUNKS, REGION_CHUNKS)
        );
        image.getPixelWriter().setColor(x & 31, z & 31, color);
    }

    /**
     * Draws the regions overlapping the view, see {@link RegionImageHandler#drawAll}.
     */
    public void drawAll(Bounds bounds, BiConsumer<Coordinate2D, Image> drawRegion) {
        int minRegionX = (int) Math.floor(bounds.getMinX() / REGION_BLOCK_SIZE) - 1;
        int maxRegionX = (int) Math.floor(bounds.getMaxX() / REGION_BLOCK_SIZE) + 1;
        int minRegionZ = (int) Math.floor(bounds.getMinZ() / REGION_BLOCK_SIZE) - 1;
        int maxRegionZ = (int) Math.floor(bounds.getMaxZ() / REGION_BLOCK_SIZE) + 1;

        for (int x = minRegionX; x <= maxRegionX; x++) {
            for (int z = minRegionZ; z <= maxRegionZ; z++) {
                WritableImage image = regions.get(ChunkCoverage.key(x, z));
                if (image != null) {
                    drawRegion.accept(new Coordinate2D(x, z), image);
                }
            }
        }
    }

    public String stats() {
        return regions.size() + " coverage regions";
    }
}
