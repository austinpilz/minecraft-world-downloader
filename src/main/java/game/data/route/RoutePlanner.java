package game.data.route;

import gui.overlay.ChunkCoverage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongPredicate;

/**
 * Turns a set of target chunks into an ordered list of hover points.
 * <p>
 * The player loads a square of (2r+1)² chunks around itself, r being the server's view distance. Stops are picked by
 * laying a grid of such squares over the targets and keeping every grid cell that contains a target; the grid offset
 * with the fewest cells wins. The stops are then ordered from the start point with nearest-neighbour, and the tour is
 * improved with 2-opt and Or-opt until it stops getting shorter or the time budget runs out.
 * <p>
 * Everything is in actual world coordinates. Chunk keys are {@link ChunkCoverage#key} values.
 */
public class RoutePlanner {
    public static final int MIN_RADIUS = 1;
    public static final int MAX_RADIUS = 32;
    public static final long DEFAULT_OPTIMISE_MILLIS = 20_000;

    // spatial grid used for nearest-neighbour searches, in blocks
    private static final int GRID_BLOCKS = 1000;
    private static final int NEIGHBOURS = 10;
    private static final double EPSILON = 1e-6;

    public record Result(List<RouteStop> stops, double lengthBlocks, int targetChunks, int radius) { }

    private RoutePlanner() { }

    public static int chunkX(long key) {
        return (int) (key >> 32);
    }

    public static int chunkZ(long key) {
        return (int) key;
    }

    /**
     * Sort and remove duplicates.
     */
    public static long[] normalise(long[] chunks) {
        long[] sorted = chunks.clone();
        Arrays.sort(sorted);
        int n = 0;
        for (int i = 0; i < sorted.length; i++) {
            if (i == 0 || sorted[i] != sorted[i - 1]) {
                sorted[n++] = sorted[i];
            }
        }
        return Arrays.copyOf(sorted, n);
    }

    /**
     * Grow the target set by the given number of chunks in every direction, so the surroundings of a shape are
     * captured as well.
     */
    public static long[] dilate(long[] chunks, int buffer) {
        if (buffer <= 0) {
            return normalise(chunks);
        }
        Set<Long> grown = new HashSet<>(chunks.length * (2 * buffer + 1));
        for (long chunk : chunks) {
            int x = chunkX(chunk);
            int z = chunkZ(chunk);
            for (int dx = -buffer; dx <= buffer; dx++) {
                for (int dz = -buffer; dz <= buffer; dz++) {
                    grown.add(ChunkCoverage.key(x + dx, z + dz));
                }
            }
        }
        long[] res = new long[grown.size()];
        int i = 0;
        for (long chunk : grown) {
            res[i++] = chunk;
        }
        Arrays.sort(res);
        return res;
    }

    /**
     * Drop the chunks that are already saved.
     */
    public static long[] removeCaptured(long[] chunks, LongPredicate captured) {
        long[] res = new long[chunks.length];
        int n = 0;
        for (long chunk : chunks) {
            if (!captured.test(chunk)) {
                res[n++] = chunk;
            }
        }
        return Arrays.copyOf(res, n);
    }

    /**
     * @param targets        chunks that have to be loaded, sorted and unique (see {@link #normalise})
     * @param radius         chunks loaded in every direction around the player
     * @param startX         block position the route starts from
     * @param optimiseMillis how long to spend improving the order, at most
     */
    public static Result plan(long[] targets, int radius, double startX, double startZ, long optimiseMillis) {
        if (radius < MIN_RADIUS || radius > MAX_RADIUS) {
            throw new IllegalArgumentException("radius must be between " + MIN_RADIUS + " and " + MAX_RADIUS);
        }
        if (targets.length == 0) {
            return new Result(List.of(), 0, 0, radius);
        }

        int side = 2 * radius + 1;
        int[] offset = bestOffset(targets, side);
        int ox = offset[0];
        int oz = offset[1];

        // group the targets by the grid cell they fall in
        Map<Long, LongList> cells = new HashMap<>();
        for (long chunk : targets) {
            long cell = cellOf(chunk, ox, oz, side);
            cells.computeIfAbsent(cell, c -> new LongList()).add(chunk);
        }

        List<RouteStop> stops = new ArrayList<>(cells.size());
        for (Map.Entry<Long, LongList> entry : cells.entrySet()) {
            int cellX = chunkX(entry.getKey());
            int cellZ = chunkZ(entry.getKey());
            // the chunk at the centre of the cell, so the loaded square is exactly the cell
            stops.add(new RouteStop(cellX * side - ox + radius, cellZ * side - oz + radius, entry.getValue().toArray()));
        }

        List<RouteStop> ordered = order(stops, startX, startZ, optimiseMillis);
        return new Result(ordered, length(ordered, startX, startZ), targets.length, radius);
    }

    /**
     * Try a few grid offsets and keep the one that needs the fewest cells to cover every target.
     */
    private static int[] bestOffset(long[] targets, int side) {
        int step = Math.max(1, side / 6);
        int bestCount = Integer.MAX_VALUE;
        int[] best = {0, 0};
        Set<Long> seen = new HashSet<>();
        for (int ox = 0; ox < side; ox += step) {
            for (int oz = 0; oz < side; oz += step) {
                seen.clear();
                for (long chunk : targets) {
                    seen.add(cellOf(chunk, ox, oz, side));
                    if (seen.size() >= bestCount) {
                        break;
                    }
                }
                if (seen.size() < bestCount) {
                    bestCount = seen.size();
                    best = new int[]{ox, oz};
                }
            }
        }
        return best;
    }

    private static long cellOf(long chunk, int ox, int oz, int side) {
        return ChunkCoverage.key(Math.floorDiv(chunkX(chunk) + ox, side), Math.floorDiv(chunkZ(chunk) + oz, side));
    }

    public static double length(List<RouteStop> stops, double startX, double startZ) {
        double length = 0;
        double x = startX;
        double z = startZ;
        for (RouteStop stop : stops) {
            length += stop.distanceTo(x, z);
            x = stop.x();
            z = stop.z();
        }
        return length;
    }

    /**
     * Order the stops into a short open path from the start point: nearest-neighbour, then 2-opt and Or-opt.
     */
    public static List<RouteStop> order(List<RouteStop> stops, double startX, double startZ, long optimiseMillis) {
        int n = stops.size();
        if (n <= 1) {
            return new ArrayList<>(stops);
        }
        double[] xs = new double[n];
        double[] zs = new double[n];
        for (int i = 0; i < n; i++) {
            xs[i] = stops.get(i).x();
            zs[i] = stops.get(i).z();
        }

        int[] order = nearestNeighbour(xs, zs, startX, startZ);
        int[][] neighbours = neighbourLists(xs, zs);
        long deadline = System.currentTimeMillis() + optimiseMillis;

        boolean improved = true;
        while (improved && System.currentTimeMillis() < deadline) {
            improved = twoOpt(xs, zs, order, neighbours, deadline);
            improved |= orOpt(xs, zs, order, neighbours, deadline);
        }

        List<RouteStop> res = new ArrayList<>(n);
        for (int i : order) {
            res.add(stops.get(i));
        }
        return res;
    }

    private static long gridKey(double x, double z) {
        return ChunkCoverage.key((int) Math.floor(x / GRID_BLOCKS), (int) Math.floor(z / GRID_BLOCKS));
    }

    private static Map<Long, List<Integer>> grid(double[] xs, double[] zs) {
        Map<Long, List<Integer>> grid = new HashMap<>();
        for (int i = 0; i < xs.length; i++) {
            grid.computeIfAbsent(gridKey(xs[i], zs[i]), k -> new ArrayList<>()).add(i);
        }
        return grid;
    }

    private static double dist(double[] xs, double[] zs, int a, int b) {
        return Math.hypot(xs[a] - xs[b], zs[a] - zs[b]);
    }

    /**
     * Greedy tour: always fly to the closest stop that hasn't been visited. Uses a grid of 1000 block cells and
     * searches outwards in rings, so it's fast for thousands of stops.
     */
    static int[] nearestNeighbour(double[] xs, double[] zs, double startX, double startZ) {
        int n = xs.length;
        Map<Long, List<Integer>> grid = grid(xs, zs);
        int maxRing = 1;
        for (int i = 0; i < n; i++) {
            maxRing = Math.max(maxRing, (int) (Math.max(Math.abs(xs[i] - startX), Math.abs(zs[i] - startZ)) / GRID_BLOCKS) + 2);
        }
        maxRing *= 2;

        int[] order = new int[n];
        double curX = startX;
        double curZ = startZ;
        for (int step = 0; step < n; step++) {
            int gx = (int) Math.floor(curX / GRID_BLOCKS);
            int gz = (int) Math.floor(curZ / GRID_BLOCKS);
            int best = -1;
            double bestDist = Double.MAX_VALUE;
            for (int ring = 0; ring <= maxRing; ring++) {
                for (int dx = -ring; dx <= ring; dx++) {
                    for (int dz = -ring; dz <= ring; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                            continue;
                        }
                        List<Integer> cell = grid.get(ChunkCoverage.key(gx + dx, gz + dz));
                        if (cell == null) {
                            continue;
                        }
                        for (int i : cell) {
                            double d = Math.hypot(xs[i] - curX, zs[i] - curZ);
                            if (d < bestDist) {
                                bestDist = d;
                                best = i;
                            }
                        }
                    }
                }
                // anything in a further ring is at least ring * GRID_BLOCKS away
                if (best >= 0 && bestDist <= ring * GRID_BLOCKS) {
                    break;
                }
            }
            if (best < 0) {
                throw new IllegalStateException("nearest neighbour search ran out of stops");
            }
            order[step] = best;
            grid.get(gridKey(xs[best], zs[best])).remove(Integer.valueOf(best));
            curX = xs[best];
            curZ = zs[best];
        }
        return order;
    }

    /**
     * The closest few stops to every stop, the candidates the local search tries.
     */
    private static int[][] neighbourLists(double[] xs, double[] zs) {
        int n = xs.length;
        Map<Long, List<Integer>> grid = grid(xs, zs);
        int[][] res = new int[n][];
        for (int i = 0; i < n; i++) {
            int gx = (int) Math.floor(xs[i] / GRID_BLOCKS);
            int gz = (int) Math.floor(zs[i] / GRID_BLOCKS);
            List<Integer> candidates = new ArrayList<>();
            for (int ring = 1; candidates.size() < NEIGHBOURS && ring <= 8; ring++) {
                candidates.clear();
                for (int dx = -ring; dx <= ring; dx++) {
                    for (int dz = -ring; dz <= ring; dz++) {
                        List<Integer> cell = grid.get(ChunkCoverage.key(gx + dx, gz + dz));
                        if (cell != null) {
                            candidates.addAll(cell);
                        }
                    }
                }
                candidates.remove(Integer.valueOf(i));
            }
            final int from = i;
            candidates.sort((a, b) -> Double.compare(dist(xs, zs, from, a), dist(xs, zs, from, b)));
            res[i] = candidates.stream().limit(NEIGHBOURS).mapToInt(Integer::intValue).toArray();
        }
        return res;
    }

    /**
     * 2-opt on an open path with the first stop fixed: reverse a section if that shortens the path. Returns whether
     * anything improved.
     */
    private static boolean twoOpt(double[] xs, double[] zs, int[] order, int[][] neighbours, long deadline) {
        int n = order.length;
        int[] pos = new int[n];
        for (int i = 0; i < n; i++) {
            pos[order[i]] = i;
        }
        boolean improvedAny = false;
        boolean improved = true;
        while (improved && System.currentTimeMillis() < deadline) {
            improved = false;
            for (int i = 0; i < n - 2; i++) {
                int a = order[i];
                int b = order[i + 1];
                double dab = dist(xs, zs, a, b);
                for (int c : neighbours[a]) {
                    int j = pos[c];
                    if (j <= i + 1) {
                        continue;
                    }
                    // path a-b ... c-d becomes a-c ... b-d; at the end of the path there is no d
                    double delta = dist(xs, zs, a, c) - dab;
                    if (j < n - 1) {
                        int d = order[j + 1];
                        delta += dist(xs, zs, b, d) - dist(xs, zs, c, d);
                    }
                    if (delta < -EPSILON) {
                        reverse(order, pos, i + 1, j);
                        improved = true;
                        improvedAny = true;
                        break;
                    }
                }
            }
        }
        return improvedAny;
    }

    private static void reverse(int[] order, int[] pos, int from, int to) {
        while (from < to) {
            int tmp = order[from];
            order[from] = order[to];
            order[to] = tmp;
            pos[order[from]] = from;
            pos[order[to]] = to;
            from++;
            to--;
        }
    }

    /**
     * Or-opt: move a run of one to three stops to a better place in the path, possibly reversed. Returns whether
     * anything improved.
     */
    private static boolean orOpt(double[] xs, double[] zs, int[] order, int[][] neighbours, long deadline) {
        int n = order.length;
        boolean improvedAny = false;
        boolean improved = true;
        while (improved && System.currentTimeMillis() < deadline) {
            improved = false;
            int[] pos = new int[n];
            for (int i = 0; i < n; i++) {
                pos[order[i]] = i;
            }
            search:
            for (int len = 1; len <= 3; len++) {
                for (int i = 1; i + len <= n; i++) {
                    int prev = order[i - 1];
                    int first = order[i];
                    int last = order[i + len - 1];
                    boolean hasNext = i + len < n;
                    int next = hasNext ? order[i + len] : -1;
                    double removeGain = dist(xs, zs, prev, first)
                        + (hasNext ? dist(xs, zs, last, next) - dist(xs, zs, prev, next) : 0);

                    for (int end = 0; end < 2; end++) {
                        for (int c : neighbours[end == 0 ? first : last]) {
                            int j = pos[c];
                            if (j >= i - 1 && j <= i + len - 1) {
                                continue;
                            }
                            // insert the run between c and the stop after it
                            boolean hasAfter = j + 1 < n;
                            int after = hasAfter ? order[j + 1] : -1;
                            double base = hasAfter ? dist(xs, zs, c, after) : 0;
                            double forward = dist(xs, zs, c, first) + (hasAfter ? dist(xs, zs, last, after) : 0) - base;
                            double backward = dist(xs, zs, c, last) + (hasAfter ? dist(xs, zs, first, after) : 0) - base;
                            boolean reversed = backward < forward;
                            double insertCost = reversed ? backward : forward;
                            if (removeGain - insertCost > EPSILON) {
                                move(order, i, len, j, reversed);
                                improved = true;
                                improvedAny = true;
                                break search;
                            }
                        }
                    }
                }
            }
        }
        return improvedAny;
    }

    /**
     * Move the run order[i..i+len-1] so it comes right after the stop currently at index j.
     */
    private static void move(int[] order, int i, int len, int j, boolean reversed) {
        int[] run = Arrays.copyOfRange(order, i, i + len);
        if (reversed) {
            for (int a = 0, b = len - 1; a < b; a++, b--) {
                int tmp = run[a];
                run[a] = run[b];
                run[b] = tmp;
            }
        }
        List<Integer> rest = new ArrayList<>(order.length);
        int target = -1;
        for (int k = 0; k < order.length; k++) {
            if (k >= i && k < i + len) {
                continue;
            }
            rest.add(order[k]);
            if (k == j) {
                target = rest.size();
            }
        }
        List<Integer> result = new ArrayList<>(rest.subList(0, target));
        for (int r : run) {
            result.add(r);
        }
        result.addAll(rest.subList(target, rest.size()));
        for (int k = 0; k < order.length; k++) {
            order[k] = result.get(k);
        }
    }

    private static class LongList {
        private long[] values = new long[16];
        private int size;

        void add(long value) {
            if (size == values.length) {
                values = Arrays.copyOf(values, size * 2);
            }
            values[size++] = value;
        }

        long[] toArray() {
            long[] res = Arrays.copyOf(values, size);
            Arrays.sort(res);
            return res;
        }
    }
}
