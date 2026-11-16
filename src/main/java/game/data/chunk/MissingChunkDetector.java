package game.data.chunk;

import game.data.coordinates.Coordinate2D;
import game.data.coordinates.CoordinateDim2D;
import game.data.dimension.Dimension;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Finds chunks the server never sent while the player was close enough to receive them. Some servers send nothing at
 * all for chunks they haven't generated, so the only sign that a chunk was visited without getting any data is that
 * it didn't arrive.
 * <p>
 * A chunk is reported once it has been within the server's view distance (minus a margin, as the edge of the view is
 * sent last) of the player for a while without a chunk packet for it arriving. If the server was just slow and sends
 * it later, the chunk is drawn as normal and stops being marked as empty.
 * <p>
 * Chunk packets are reported from the network thread, {@link #tick} is only called from a single thread.
 */
public class MissingChunkDetector {
    // the outer chunks of the view distance are sent last and are the first to be dropped when moving, so skip them
    static final int EDGE_MARGIN = 2;
    // how long a chunk has to stay in range without arriving before it's considered missing
    public static final long WAIT_MILLIS = 5000;

    // chunks the server sent this session, of any dimension the player has been in since the last dimension change
    private final Set<CoordinateDim2D> received = ConcurrentHashMap.newKeySet();

    // only used by the ticking thread
    private final Map<Coordinate2D, Long> inRangeSince = new HashMap<>();
    private final Set<Coordinate2D> reported = new HashSet<>();
    private Dimension dimension;

    public void chunkReceived(CoordinateDim2D chunk) {
        received.add(chunk);
    }

    /**
     * The radius around the player, in chunks, in which chunks the server didn't send are considered missing.
     */
    public static int detectionRadius(int serverViewDistance) {
        return Math.max(0, serverViewDistance - EDGE_MARGIN);
    }

    /**
     * Check which chunks around the player have been in range long enough without being sent.
     *
     * @param playerChunk the chunk the player is in
     * @param radius      radius in chunks, see {@link #detectionRadius}
     * @param now         the current time in milliseconds
     * @return the chunks that are newly found to be missing, each chunk is only returned once
     */
    public List<CoordinateDim2D> tick(Dimension dimension, Coordinate2D playerChunk, int radius, long now) {
        if (!dimension.equals(this.dimension)) {
            this.dimension = dimension;
            inRangeSince.clear();
            reported.clear();
            received.removeIf(chunk -> !chunk.getDimension().equals(dimension));
        }

        List<CoordinateDim2D> missing = new ArrayList<>();
        Set<Coordinate2D> inRange = new HashSet<>();
        int radiusSquared = radius * radius;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radiusSquared) {
                    continue;
                }
                Coordinate2D chunk = playerChunk.add(dx, dz);
                if (reported.contains(chunk) || received.contains(new CoordinateDim2D(chunk, dimension))) {
                    continue;
                }

                inRange.add(chunk);
                long since = inRangeSince.computeIfAbsent(chunk, c -> now);
                if (now - since >= WAIT_MILLIS) {
                    reported.add(chunk);
                    missing.add(new CoordinateDim2D(chunk, dimension));
                }
            }
        }

        // chunks that left the range have to wait the full time again when they come back
        inRangeSince.keySet().retainAll(inRange);
        return missing;
    }

    /**
     * Forget chunks that were waiting to be reported, e.g. while saving is paused or the position isn't known.
     */
    public void pause() {
        inRangeSince.clear();
    }
}
