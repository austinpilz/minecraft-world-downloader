package game.data;

import java.util.function.LongSupplier;

/**
 * Counts chunks over the last minute, in one-second buckets, to show how fast a bot brings in new chunks.
 */
public class ChunkRate {
    private static final int SECONDS = 60;

    private final long[] counts = new long[SECONDS];
    // the second each bucket counts for, a bucket from an older second is stale
    private final long[] seconds = new long[SECONDS];
    private final LongSupplier clock;

    public ChunkRate() {
        this(System::currentTimeMillis);
    }

    ChunkRate(LongSupplier clock) {
        this.clock = clock;
    }

    public synchronized void add() {
        long second = clock.getAsLong() / 1000;
        int bucket = (int) Math.floorMod(second, SECONDS);
        if (seconds[bucket] != second) {
            seconds[bucket] = second;
            counts[bucket] = 0;
        }
        counts[bucket]++;
    }

    /**
     * The number of chunks counted in the last 60 seconds.
     */
    public synchronized long perMinute() {
        long now = clock.getAsLong() / 1000;
        long total = 0;
        for (int i = 0; i < SECONDS; i++) {
            if (now - seconds[i] < SECONDS) {
                total += counts[i];
            }
        }
        return total;
    }
}
