package gui.images;

import static gui.images.RegionImage.NORMAL_PREFIX;
import static gui.images.RegionImage.SMALL_PREFIX;
import static util.ExceptionHandling.attempt;
import static util.ExceptionHandling.attemptQuiet;

import config.Config;
import game.data.WorldManager;
import game.data.coordinates.Coordinate2D;
import game.data.coordinates.CoordinateDim2D;
import game.data.dimension.Dimension;
import gui.Bounds;
import gui.ChunkImageState;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import javafx.scene.image.Image;
import javafx.scene.paint.Color;
import org.apache.commons.io.FileUtils;

/**
 * Class to manage overlay images.
 */
public class RegionImageHandler {
    private static final String CACHE_PATH = "image-cache";
    // side length of a region in blocks (32 chunks * 16 blocks) - kept in sync with Bounds.overlaps()
    private static final int REGION_BLOCK_SIZE = 512;

    private Map<Coordinate2D, RegionImages> regions;
    private Dimension activeDimension;
    private boolean isSaving = false;

    // draws are cheap, in-memory pixel writes and need to keep up with incoming chunks, so they get
    // their own executor. Saving/resampling involves disk I/O and PNG encode/decode and can take a
    // while on large worlds - if it shared a thread with draws, a slow save would back up every
    // pending chunk draw behind it and the whole preview would visibly stutter/lag.
    private final ExecutorService drawExecutor;
    private final ScheduledExecutorService ioExecutor;
    private static ImageMode overrideMode;
    private ImageMode imageMode = ImageMode.NORMAL;

    // bounds/scale from the most recent drawAll() call, used by the periodic off-screen shrink sweep
    private volatile Bounds lastBounds;
    private volatile double lastBlocksPerPixel = 1;

    public RegionImageHandler() {
        this.regions = new ConcurrentHashMap<>();

        drawExecutor = Executors.newSingleThreadExecutor(
            (r) -> new Thread(r, "Region Draw Handler")
        );
        ioExecutor = Executors.newSingleThreadScheduledExecutor(
            (r) -> new Thread(r, "Region IO Handler")
        );
        ioExecutor.scheduleWithFixedDelay(this::save, 20, 20, TimeUnit.SECONDS);
        ioExecutor.scheduleWithFixedDelay(this::flushPendingRegions, 2, 2, TimeUnit.SECONDS);
        ioExecutor.scheduleWithFixedDelay(this::shrinkOffscreenRegions, 5, 5, TimeUnit.SECONDS);
    }

    /**
     * Merge any chunk updates that were buffered (rather than written straight to disk) because their
     * region was displayed below full resolution at the time.
     */
    private void flushPendingRegions() {
        regions.values().forEach(RegionImages::flushPendingChunks);
    }

    /**
     * Regularly shrink regions that have fallen outside the current view back down, since drawAll()
     * only visits regions that are currently visible.
     */
    private void shrinkOffscreenRegions() {
        Bounds bounds = this.lastBounds;
        if (bounds == null) {
            return;
        }

        regions.forEach((coordinate, images) -> {
            if (bounds.overlaps(coordinate)) {
                return;
            }

            if (images.updateSize(false, imageMode, lastBlocksPerPixel)) {
                images.allowResample();
            }
        });
    }

    public static ImageMode getOverrideMode() {
        return overrideMode;
    }

    public static void setOverrideMode(ImageMode imageMode) {
        overrideMode = imageMode;
    }

    public void clear() {
        unload();
        attemptQuiet(() -> FileUtils.deleteDirectory(Paths.get(Config.getWorldOutputDir(), CACHE_PATH).toFile()));
    }

    public void drawChunk(CoordinateDim2D coordinate, Map<ImageMode, Image> imageMap, Boolean isSaved) {
        if (!coordinate.getDimension().equals(activeDimension)) {
            return;
        }

        Coordinate2D region = coordinate.chunkToRegion();
        RegionImages images = regions.computeIfAbsent(region,
            coordinate2D -> RegionImages.loadRegion(activeDimension, region)
        );

        Coordinate2D local = coordinate.toRegionLocal();

        drawExecutor.execute(() -> imageMap.forEach((mode, image) -> images.getImage(mode).drawChunk(local, image)));

        setChunkState(images, local, ChunkImageState.isSaved(isSaved));
    }

    public void setChunkState(Coordinate2D coords, ChunkImageState state) {
        Coordinate2D region = coords.chunkToRegion();

        RegionImages images = regions.get(region);
        if (images == null) {
            return;
        }

        setChunkState(images, coords.toRegionLocal(), state);
    }

    private void setChunkState(RegionImages image, Coordinate2D local, ChunkImageState state) {
        image.colourChunk(local, state.getColor());
    }


    public void markChunkSaved(CoordinateDim2D coordinate) {
        setChunkState(coordinate, ChunkImageState.SAVED);
    }

    private void save(Map<Coordinate2D, RegionImages> regions, Dimension dim) {
        // if shutdown is called, wait for saving to complete
        if (isSaving) {
            if (ioExecutor != null) {
                attempt(() -> ioExecutor.awaitTermination(10, TimeUnit.SECONDS));
            }
            return;
        }
        isSaving = true;

        for (ImageMode mode : ImageMode.values()) {
            attempt(() -> Files.createDirectories(dimensionPath(dim, mode)));
        }

        regions.forEach((coordinate, image) -> {
            attempt(image::save);
        });

        isSaving = false;
    }

    public void save() {
        save(this.regions, this.activeDimension);
    }

    private void unload() {
        this.regions = new ConcurrentHashMap<>();
    }

    /**
     * Searches for all region files in a directory to load them in.
     */
    private void loadFromFile() {
        new Thread(() -> attemptQuiet(() -> {
            // walk one of the modes, check for the others if we find one
            Files.walk(dimensionPath(this.activeDimension, ImageMode.NORMAL), 1)
                .limit(32000)
                .forEach(image -> attempt(() -> loadFromFile(regions, this.activeDimension, image)));
        })).start();
    }

    private static void loadFromFile(Map<Coordinate2D, RegionImages> regions, Dimension dim, Path image) {
        if (!image.toString().toLowerCase().endsWith("png") || image.getFileName().startsWith(SMALL_PREFIX)) {
            return;
        }

        String[] parts = image.getFileName().toString().split("\\.");

        int x = Integer.parseInt(parts[1]);
        int z = Integer.parseInt(parts[2]);
        Coordinate2D regionCoordinate = new Coordinate2D(x, z);

        regions.computeIfAbsent(regionCoordinate, coord -> RegionImages.loadRegion(dim, coord));
    }

    public void setDimension(Dimension dimension) {
        if (this.activeDimension == dimension) {
            return;
        }

        if (this.activeDimension != null) {
            save();
            unload();
        }

        this.activeDimension = dimension;
        loadFromFile();
    }

    static Path dimensionPath(Dimension dim) {
        return Paths.get(Config.getWorldOutputDir(), CACHE_PATH, dim.getPath());
    }

    static Path dimensionPath(Dimension dim, ImageMode mode) {
        return Paths.get(Config.getWorldOutputDir(), CACHE_PATH, mode.path(), dim.getPath());
    }

    private void updateRenderMode() {
        boolean isNether = WorldManager.getInstance().getDimension().isNether();

        if (overrideMode != null) {
            imageMode = isNether ? overrideMode.other() : overrideMode;
            return;
        }

        if (Config.enableCaveRenderMode()) {
            imageMode = WorldManager.getInstance().isBelowGround() ? ImageMode.CAVES : ImageMode.NORMAL;
        } else {
            imageMode = isNether ? ImageMode.CAVES : ImageMode.NORMAL;
        }
    }

    /**
     * Draws only the regions currently overlapping the view. Previously this walked every region ever
     * loaded this session on every single animation frame (60/sec) just to test visibility - on a large
     * world with thousands of regions loaded, that fixed per-frame cost alone was enough to make the
     * preview stutter, on top of everything else. Instead, compute the (small) rectangle of region
     * coordinates the view can possibly overlap and look those up directly.
     */
    public void drawAll(Bounds bounds, double blocksPerPixel, BiConsumer<Coordinate2D, Image> drawRegion) {
        updateRenderMode();

        this.lastBounds = bounds;
        this.lastBlocksPerPixel = blocksPerPixel;

        int minRegionX = (int) Math.floor(bounds.getMinX() / REGION_BLOCK_SIZE) - 1;
        int maxRegionX = (int) Math.floor(bounds.getMaxX() / REGION_BLOCK_SIZE) + 1;
        int minRegionZ = (int) Math.floor(bounds.getMinZ() / REGION_BLOCK_SIZE) - 1;
        int maxRegionZ = (int) Math.floor(bounds.getMaxZ() / REGION_BLOCK_SIZE) + 1;

        for (int x = minRegionX; x <= maxRegionX; x++) {
            for (int z = minRegionZ; z <= maxRegionZ; z++) {
                Coordinate2D coordinate = new Coordinate2D(x, z);
                if (!bounds.overlaps(coordinate)) {
                    continue;
                }

                RegionImages images = regions.get(coordinate);
                if (images == null) {
                    continue;
                }

                if (images.updateSize(true, imageMode, blocksPerPixel)) {
                    ioExecutor.execute(images::allowResample);
                }

                RegionImage image = images.getImage(imageMode);
                if (image == null) {
                    continue;
                }

                drawRegion.accept(coordinate, image.getImage());
                drawRegion.accept(coordinate, image.getChunkOverlay());
            }
        }
    }

    public String stats() {
        int size = regions.size() * 2;

        Map<Integer, Integer> counts = new HashMap<>();
        regions.forEach((k, v) -> {
            int sizeNormal = v.normal.getSize();;
            int sizeCave = v.caves.getSize();;

            counts.put(sizeNormal, counts.getOrDefault(sizeNormal, 0) + 1);
            counts.put(sizeCave, counts.getOrDefault(sizeCave, 0) + 1);
        });

        String stats = counts.entrySet().stream().sorted(Comparator.comparing(Map.Entry::getKey))
            .map(e -> e.getValue() + "x" + e.getKey() + "px")
            .collect(Collectors.joining(", "));

        return size + " (" + stats + ")";
    }

    public void shutdown() {
        if (drawExecutor != null) {
            drawExecutor.shutdown();
        }
        if (ioExecutor != null) {
            ioExecutor.shutdown();
        }
    }

    public void resetRegion(Coordinate2D region) {
        attemptQuiet(() -> FileUtils.delete(RegionImage.getFile(dimensionPath(this.activeDimension, ImageMode.NORMAL), NORMAL_PREFIX, region)));
        attemptQuiet(() -> FileUtils.delete(RegionImage.getFile(dimensionPath(this.activeDimension, ImageMode.CAVES), NORMAL_PREFIX, region)));

        attemptQuiet(() -> FileUtils.delete(RegionImage.getFile(dimensionPath(this.activeDimension, ImageMode.NORMAL), SMALL_PREFIX, region)));
        attemptQuiet(() -> FileUtils.delete(RegionImage.getFile(dimensionPath(this.activeDimension, ImageMode.CAVES), SMALL_PREFIX, region)));
        regions.remove(region);
    }
}

class RegionImages {
    final RegionImage normal;
    final RegionImage caves;

    public RegionImages(Coordinate2D coordinate, RegionImage normal, RegionImage caves) {
        this.normal = normal;
        this.caves = caves;
    }

    public static RegionImages loadRegion(Dimension dimension, Coordinate2D coordinate) {
        RegionImage normal = RegionImage.of(RegionImageHandler.dimensionPath(dimension, ImageMode.NORMAL), coordinate);
        RegionImage caves = RegionImage.of(RegionImageHandler.dimensionPath(dimension, ImageMode.CAVES), coordinate);

        return new RegionImages(coordinate, normal, caves);
    }

    public RegionImage getImage(ImageMode mode) {
        return switch (mode) {
            case NORMAL -> normal;
            case CAVES -> caves;
        };
    }

    public void colourChunk(Coordinate2D local, Color color) {
        normal.colourChunk(local, color);
        if (caves != null) {
            caves.colourChunk(local, color);
        }
    }

    public void save() throws IOException {
        normal.save();
        caves.save();
    }

    public void flushPendingChunks() {
        normal.flushPendingChunks();
        if (caves != null) {
            caves.flushPendingChunks();
        }
    }

    public boolean updateSize(boolean isVisible, ImageMode mode, double blocksPerPixel) {
        boolean shouldResize = caves.setTargetSize(isVisible && mode == ImageMode.CAVES, blocksPerPixel);
        shouldResize |= normal.setTargetSize(isVisible && mode == ImageMode.NORMAL, blocksPerPixel);

        return shouldResize;
    }

    public void allowResample() {
        caves.allowResample();
        normal.allowResample();
    }
}

