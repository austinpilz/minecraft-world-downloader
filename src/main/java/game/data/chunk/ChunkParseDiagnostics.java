package game.data.chunk;

import game.data.coordinates.CoordinateDim2D;
import packets.DataTypeProvider;
import util.PathUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Reports chunks that could not be (fully) read from the network, or that arrived without any blocks, and saves the
 * raw packets of the first few of each kind so they can be decoded by hand.
 * <p>
 * Failures while reading light data leave the blocks intact, and on some servers happen for almost every chunk, so
 * those are summarised. Failures before that point (and empty chunks) can show up as black chunks on the map, so
 * those are always logged.
 */
public final class ChunkParseDiagnostics {
    public static final String STAGE_LIGHT = "light";

    private static final int LOGGED_INDIVIDUALLY = 5;
    private static final int SUMMARY_INTERVAL = 100;
    private static final int DUMPS_PER_KIND = 5;

    private static final Map<String, AtomicInteger> counts = new ConcurrentHashMap<>();

    private ChunkParseDiagnostics() { }

    public static void failure(CoordinateDim2D location, String stage, Exception ex, DataTypeProvider packet) {
        int count = increment("failed-" + stage);
        Path dump = dumpIfWanted("failed-" + stage, count, location, packet);
        String suffix = dump == null ? "" : " Packet saved to " + dump;

        if (!STAGE_LIGHT.equals(stage) || count <= LOGGED_INDIVIDUALLY) {
            System.out.println("Issue parse chunk at " + location + " while reading " + stage + ". Cause: " + ex + suffix);
            if (STAGE_LIGHT.equals(stage) && count == LOGGED_INDIVIDUALLY) {
                System.out.println("Further light parse issues will be summarised every " + SUMMARY_INTERVAL + " occurrences. "
                    + "Block data for these chunks is still saved.");
            }
        } else if (count % SUMMARY_INTERVAL == 0) {
            System.out.println(count + " chunks have had light parse issues so far. Latest at " + location + ". Cause: " + ex);
        }
    }

    /**
     * The server sent a chunk where every section has a block count of zero.
     */
    public static void emptyChunk(CoordinateDim2D location, DataTypeProvider packet) {
        int count = increment("empty");
        Path dump = dumpIfWanted("empty", count, location, packet);
        String suffix = dump == null ? "" : " Packet saved to " + dump;

        if (count <= 20 || count % SUMMARY_INTERVAL == 0) {
            System.out.println("Server sent chunk " + location + " without any blocks (" + count + " so far)." + suffix);
        }
    }

    private static int increment(String kind) {
        return counts.computeIfAbsent(kind, k -> new AtomicInteger()).incrementAndGet();
    }

    private static Path dumpIfWanted(String kind, int count, CoordinateDim2D location, DataTypeProvider packet) {
        if (count > DUMPS_PER_KIND || packet == null) {
            return null;
        }

        try {
            Path dir = PathUtils.toPath("cache", "chunk-dumps");
            Files.createDirectories(dir);
            Path file = dir.resolve(kind + "_" + location.getX() + "_" + location.getZ() + ".bin");
            Files.write(file, packet.debug__getFullArray());
            return file.toAbsolutePath();
        } catch (IOException ex) {
            System.out.println("Could not save chunk packet: " + ex);
            return null;
        }
    }
}
