package game.data.route;

import java.util.function.LongPredicate;

/**
 * One hover point of a route. The player loads a square of chunks around it (the server's view distance in every
 * direction); the stop is finished once every target chunk inside that square has been saved by the proxy.
 * <p>
 * Plain fields so the stop can be written to the route file with Gson.
 */
public class RouteStop {
    public enum Status {
        /** not visited yet */
        PENDING,
        /** every target chunk was saved */
        DONE,
        /** visited but timed out, will be tried again after the other stops */
        RETRY,
        /** given up on, by the user or after too many retries */
        SKIPPED
    }

    public int chunkX;
    public int chunkZ;
    /** chunk keys (see {@link gui.overlay.ChunkCoverage#key}) that have to be saved for this stop to count as done */
    public long[] targets;
    public Status status = Status.PENDING;
    public int attempts;

    public RouteStop() { }

    public RouteStop(int chunkX, int chunkZ, long[] targets) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.targets = targets;
    }

    /** Block X of the centre of the stop's chunk. */
    public double x() {
        return chunkX * 16 + 8;
    }

    /** Block Z of the centre of the stop's chunk. */
    public double z() {
        return chunkZ * 16 + 8;
    }

    public int missing(LongPredicate captured) {
        int missing = 0;
        for (long chunk : targets) {
            if (!captured.test(chunk)) {
                missing++;
            }
        }
        return missing;
    }

    /** Whether the stop still has to be flown to. */
    public boolean isOpen() {
        return status == Status.PENDING || status == Status.RETRY;
    }

    public double distanceTo(double x, double z) {
        return Math.hypot(x() - x, z() - z);
    }

    public double distanceTo(RouteStop other) {
        return distanceTo(other.x(), other.z());
    }
}
