package game.data.route;

import gui.overlay.ChunkCoverage;
import gui.overlay.OverlayParser;
import gui.overlay.OverlaySet;
import gui.overlay.OverlayShape;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RoutePlannerTest {

    private static long[] square(int minX, int minZ, int maxX, int maxZ) {
        List<Long> res = new ArrayList<>();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                res.add(ChunkCoverage.key(x, z));
            }
        }
        return RoutePlanner.normalise(res.stream().mapToLong(Long::longValue).toArray());
    }

    /**
     * Every target chunk is inside the loaded square of the stop it was assigned to, and every target was assigned.
     */
    private static void assertCovers(RoutePlanner.Result result, long[] targets) {
        Set<Long> assigned = new HashSet<>();
        for (RouteStop stop : result.stops()) {
            for (long chunk : stop.targets) {
                assertThat(Math.abs(RoutePlanner.chunkX(chunk) - stop.chunkX)).isLessThanOrEqualTo(result.radius());
                assertThat(Math.abs(RoutePlanner.chunkZ(chunk) - stop.chunkZ)).isLessThanOrEqualTo(result.radius());
                assertThat(assigned.add(chunk)).as("chunk assigned twice").isTrue();
            }
        }
        assertThat(assigned).hasSize(targets.length);
    }

    @Test
    void smallBlockNeedsOneStopAtItsCentre() {
        long[] targets = square(10, 20, 12, 22);
        RoutePlanner.Result result = RoutePlanner.plan(targets, 1, 0, 0, 100);

        assertThat(result.stops()).hasSize(1);
        RouteStop stop = result.stops().get(0);
        assertThat(stop.chunkX).isEqualTo(11);
        assertThat(stop.chunkZ).isEqualTo(21);
        assertThat(stop.targets).hasSize(9);
        assertThat(stop.x()).isEqualTo(11 * 16 + 8);
        assertCovers(result, targets);
        assertThat(result.lengthBlocks()).isCloseTo(Math.hypot(stop.x(), stop.z()), org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    void emptyTargetsGiveEmptyRoute() {
        RoutePlanner.Result result = RoutePlanner.plan(new long[0], 8, 0, 0, 100);
        assertThat(result.stops()).isEmpty();
        assertThat(result.lengthBlocks()).isZero();
    }

    @Test
    void nearestClusterComesFirst() {
        long[] near = square(0, 0, 4, 4);
        long[] far = square(600, 600, 604, 604);
        long[] targets = new long[near.length + far.length];
        System.arraycopy(near, 0, targets, 0, near.length);
        System.arraycopy(far, 0, targets, near.length, far.length);
        targets = RoutePlanner.normalise(targets);

        RoutePlanner.Result result = RoutePlanner.plan(targets, 2, 0, 0, 100);
        assertCovers(result, targets);
        assertThat(result.stops().get(0).chunkX).isLessThan(100);
        assertThat(result.stops().get(result.stops().size() - 1).chunkX).isGreaterThan(500);
        // the two clusters are visited one after the other, so the route crosses the gap exactly once
        int crossings = 0;
        boolean inFar = false;
        for (RouteStop stop : result.stops()) {
            boolean far2 = stop.chunkX > 300;
            if (far2 != inFar) {
                crossings++;
                inFar = far2;
            }
        }
        assertThat(crossings).isEqualTo(1);
    }

    @Test
    void optimisedOrderIsNotLongerThanGreedy() {
        Random random = new Random(42);
        List<Long> chunks = new ArrayList<>();
        for (int cluster = 0; cluster < 30; cluster++) {
            int cx = random.nextInt(2000) - 1000;
            int cz = random.nextInt(2000) - 1000;
            for (int i = 0; i < 40; i++) {
                chunks.add(ChunkCoverage.key(cx + random.nextInt(20), cz + random.nextInt(20)));
            }
        }
        long[] targets = RoutePlanner.normalise(chunks.stream().mapToLong(Long::longValue).toArray());

        RoutePlanner.Result greedy = RoutePlanner.plan(targets, 4, 0, 0, 0);
        RoutePlanner.Result optimised = RoutePlanner.plan(targets, 4, 0, 0, 5000);
        assertCovers(greedy, targets);
        assertCovers(optimised, targets);
        assertThat(optimised.stops()).hasSameSizeAs(greedy.stops());
        assertThat(optimised.lengthBlocks()).isLessThanOrEqualTo(greedy.lengthBlocks());
        assertThat(RoutePlanner.length(optimised.stops(), 0, 0)).isCloseTo(optimised.lengthBlocks(), org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    void dilateGrowsInEveryDirection() {
        long[] grown = RoutePlanner.dilate(new long[]{ChunkCoverage.key(0, 0)}, 2);
        assertThat(grown).hasSize(25).contains(ChunkCoverage.key(-2, -2), ChunkCoverage.key(2, 2), ChunkCoverage.key(0, 0));
        assertThat(RoutePlanner.dilate(new long[]{ChunkCoverage.key(3, 3), ChunkCoverage.key(3, 3)}, 0)).containsExactly(ChunkCoverage.key(3, 3));
    }

    @Test
    void removeCapturedDropsSavedChunks() {
        long[] targets = square(0, 0, 1, 1);
        long[] left = RoutePlanner.removeCaptured(targets, chunk -> RoutePlanner.chunkX(chunk) == 0);
        assertThat(left).containsExactly(ChunkCoverage.key(1, 0), ChunkCoverage.key(1, 1));
    }

    @Test
    void negativeCoordinatesAreHandled() {
        long[] targets = square(-40, -40, -30, -30);
        RoutePlanner.Result result = RoutePlanner.plan(targets, 3, 0, 0, 100);
        assertCovers(result, targets);
        // 11 x 11 chunks with squares of 7: 2 x 2 cells at most
        assertThat(result.stops()).hasSizeLessThanOrEqualTo(4);
    }

    /**
     * The MegaCityCraft WorldGuard regions (Dynmap marker file of 2026-11-16): 745 regions inside the 18,000 block
     * border touch about 120k chunks. At view distance 8 the sizing model gave 1,329 stops and 638 km from spawn.
     */
    @Test
    void megaCityCraftRegionsAtViewDistance8() throws IOException {
        OverlayParser.Result parsed;
        try (Reader reader = new InputStreamReader(
                getClass().getResourceAsStream("/overlays/dynmap-megacitycraft-world.json"), StandardCharsets.UTF_8)) {
            parsed = OverlayParser.parse(reader);
        }
        OverlaySet worldGuard = parsed.getSets().stream()
            .filter(set -> set.getId().equals("mccmap.worldguard")).findFirst().orElseThrow();

        List<Long> chunks = new ArrayList<>();
        int regions = 0;
        for (OverlayShape shape : worldGuard.getShapes()) {
            if (Math.max(Math.max(Math.abs(shape.getMinX()), Math.abs(shape.getMaxX())),
                         Math.max(Math.abs(shape.getMinZ()), Math.abs(shape.getMaxZ()))) > 18000) {
                continue;
            }
            regions++;
            shape.forEachChunk(chunks::add);
        }
        long[] targets = RoutePlanner.normalise(chunks.stream().mapToLong(Long::longValue).toArray());
        assertThat(regions).isEqualTo(745);
        assertThat(targets.length).isBetween(115_000, 125_000);

        RoutePlanner.Result result = RoutePlanner.plan(targets, 8, -5887, 4352, 3000);
        assertCovers(result, targets);
        assertThat(result.stops().size()).isBetween(1200, 1450);
        assertThat(result.lengthBlocks()).isLessThan(750_000);
    }
}
