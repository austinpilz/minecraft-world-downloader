package game.data.region;

import game.data.coordinates.CoordinateDim2D;
import game.data.dimension.Dimension;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * Holds the loaded regions of every dimension. There is at most one {@link Region} per region file at any time, and
 * it is created, changed, written and removed while holding that region's lock. This means a region is never read
 * from disk while an older copy of it still has chunks waiting to be written, which used to overwrite those chunks.
 */
public class RegionStore {
    // locks are striped since a region's lock is needed before the region exists
    private static final int LOCK_STRIPES = 256;

    private final Map<Dimension, Map<CoordinateDim2D, Region>> dimensions = new ConcurrentHashMap<>();
    private final ReentrantLock[] locks = new ReentrantLock[LOCK_STRIPES];

    public RegionStore() {
        for (int i = 0; i < locks.length; i++) {
            locks[i] = new ReentrantLock();
        }
    }

    ReentrantLock lock(CoordinateDim2D regionCoordinates) {
        return locks[Math.floorMod(regionCoordinates.hashCode(), LOCK_STRIPES)];
    }

    private Map<CoordinateDim2D, Region> regionsOf(Dimension dimension) {
        return dimensions.computeIfAbsent(dimension, d -> new ConcurrentHashMap<>());
    }

    public Region get(CoordinateDim2D regionCoordinates) {
        Map<CoordinateDim2D, Region> regions = dimensions.get(regionCoordinates.getDimension());
        return regions == null ? null : regions.get(regionCoordinates);
    }

    /**
     * Run the given action on the region, creating it (and reading its file) if it isn't loaded yet. The region
     * can't be removed until the action is done.
     */
    public void withRegion(CoordinateDim2D regionCoordinates, Consumer<Region> action) {
        ReentrantLock lock = lock(regionCoordinates);
        lock.lock();
        try {
            Map<CoordinateDim2D, Region> regions = regionsOf(regionCoordinates.getDimension());
            Region region = regions.get(regionCoordinates);
            if (region == null) {
                region = new Region(regionCoordinates, lock);
                regions.put(regionCoordinates, region);
            }
            action.accept(region);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Forget the region if it no longer has any chunks in it.
     */
    public void removeIfEmpty(CoordinateDim2D regionCoordinates) {
        ReentrantLock lock = lock(regionCoordinates);
        lock.lock();
        try {
            Map<CoordinateDim2D, Region> regions = dimensions.get(regionCoordinates.getDimension());
            if (regions == null) {
                return;
            }
            Region region = regions.get(regionCoordinates);
            if (region != null && region.canRemove()) {
                regions.remove(regionCoordinates);
            }
        } finally {
            lock.unlock();
        }
    }

    public Set<Dimension> dimensions() {
        return dimensions.keySet();
    }

    public Collection<Region> regions(Dimension dimension) {
        Map<CoordinateDim2D, Region> regions = dimensions.get(dimension);
        return regions == null ? List.of() : List.copyOf(regions.values());
    }

    public Collection<Region> allRegions() {
        return dimensions.values().stream().flatMap(el -> el.values().stream()).toList();
    }

    /**
     * Drop the dimension's regions without saving them.
     */
    public void clear(Dimension dimension) {
        dimensions.remove(dimension);
    }

    public int countRegions() {
        return dimensions.values().stream().mapToInt(Map::size).sum();
    }
}
