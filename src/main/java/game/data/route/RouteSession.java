package game.data.route;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.DoubleSupplier;
import java.util.function.LongPredicate;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Drives a planned {@link Route}: which stop is next, when a stop counts as finished, what to do when one times out,
 * and the progress numbers shown in the GUI and served to the flight addon.
 * <p>
 * A stop is finished when every one of its target chunks has been saved, not when the player arrives. If the player
 * has been at a stop for longer than the timeout without that happening, the stop is put aside and tried once more
 * after all the others. Progress is written to a file in the world folder so a session can be resumed later.
 * <p>
 * All state is guarded by the session's monitor; the tick runs on its own daemon thread.
 */
public class RouteSession {
    public enum State {
        /** no route */
        IDLE,
        /** route planned, not started */
        PLANNED,
        /** the addon should be flying */
        RUNNING,
        PAUSED,
        /** every stop is done or skipped */
        DONE
    }

    public static final String FILE_NAME = "route.json";
    public static final long DEFAULT_STOP_TIMEOUT_MILLIS = 90_000;
    /** within this many blocks of a stop the player counts as having arrived */
    public static final double ARRIVE_BLOCKS = 24;
    /** after a reconnect or detour further than this from the current stop, the remaining stops are re-ordered */
    static final double REPLAN_DISTANCE = 1000;
    static final int MAX_ATTEMPTS = 2;
    static final long TICK_MILLIS = 500;
    static final long SAVE_INTERVAL_MILLIS = 5000;
    static final long SPEED_WINDOW_MILLIS = 30_000;
    static final long DEFAULT_HOLD_MILLIS = 3000;
    private static final long REORDER_MILLIS = 2000;

    private final LongPredicate captured;
    private final Supplier<double[]> position;
    private final DoubleSupplier chunksPerSecond;
    private final Supplier<Path> file;
    private final LongSupplier clock;

    private Route route;
    private State state = State.IDLE;
    private int current = -1;
    private long stopTimeoutMillis = DEFAULT_STOP_TIMEOUT_MILLIS;
    private int chunkBudgetPerMinute;
    private boolean holding;
    private long arrivedAt = -1;
    private String message = "";

    // recent positions, to measure the flying speed: {time, x, z}
    private final Deque<double[]> track = new ArrayDeque<>();
    private long holdTotalMillis;
    private int holdCount;

    private boolean loaded;
    private boolean dirty;
    private long lastSave;
    private ScheduledExecutorService ticker;

    /**
     * @param captured        whether a chunk (see {@link gui.overlay.ChunkCoverage#key}) has been saved
     * @param position        the player's block position as {x, z}, or null if it isn't known
     * @param chunksPerSecond rate at which chunks are arriving from the server
     * @param file            where progress is saved, may return null to not save
     */
    public RouteSession(LongPredicate captured, Supplier<double[]> position, DoubleSupplier chunksPerSecond, Supplier<Path> file) {
        this(captured, position, chunksPerSecond, file, System::currentTimeMillis);
    }

    RouteSession(LongPredicate captured, Supplier<double[]> position, DoubleSupplier chunksPerSecond, Supplier<Path> file, LongSupplier clock) {
        this.captured = captured;
        this.position = position;
        this.chunksPerSecond = chunksPerSecond;
        this.file = file;
        this.clock = clock;
    }

    // --- control ---

    public synchronized void setRoute(Route route) {
        ensureLoaded();
        this.route = route;
        this.state = route == null || route.stops.isEmpty() ? State.IDLE : State.PLANNED;
        this.current = -1;
        this.arrivedAt = -1;
        this.holding = false;
        this.message = "";
        this.holdTotalMillis = 0;
        this.holdCount = 0;
        if (route != null && !route.stops.isEmpty()) {
            advance();
            state = State.PLANNED;
            startTicker();
        }
        dirty = true;
        saveNow();
    }

    /**
     * Start flying, or continue after a pause. When the player is far from the stop the route was at, the remaining
     * stops are re-ordered from where the player is now.
     */
    public synchronized boolean start() {
        ensureLoaded();
        if (route == null || route.stops.isEmpty()) {
            message = "No route planned";
            return false;
        }
        if (state == State.RUNNING) {
            return true;
        }
        if (current < 0 || !route.stops.get(current).isOpen()) {
            advance();
        }
        if (current < 0) {
            state = State.DONE;
            return false;
        }
        double[] pos = position.get();
        if (pos != null && route.stops.get(current).distanceTo(pos[0], pos[1]) > REPLAN_DISTANCE) {
            reorderRemaining(pos[0], pos[1]);
        }
        state = State.RUNNING;
        arrivedAt = -1;
        message = "";
        startTicker();
        dirty = true;
        saveNow();
        return true;
    }

    public synchronized void pause() {
        if (state == State.RUNNING) {
            state = State.PAUSED;
            arrivedAt = -1;
            dirty = true;
            saveNow();
        }
    }

    /**
     * Give up on the current stop and go to the next one.
     */
    public synchronized boolean skip() {
        ensureLoaded();
        if (route == null || current < 0) {
            return false;
        }
        route.stops.get(current).status = RouteStop.Status.SKIPPED;
        advance();
        dirty = true;
        saveNow();
        return true;
    }

    /**
     * Re-order the stops that are still open from the player's current position.
     */
    public synchronized boolean replan() {
        ensureLoaded();
        if (route == null) {
            return false;
        }
        double[] pos = position.get();
        if (pos == null) {
            if (current >= 0) {
                RouteStop stop = route.stops.get(current);
                pos = new double[]{stop.x(), stop.z()};
            } else {
                pos = new double[]{route.startX, route.startZ};
            }
        }
        reorderRemaining(pos[0], pos[1]);
        dirty = true;
        saveNow();
        return true;
    }

    public synchronized void clear() {
        ensureLoaded();
        route = null;
        state = State.IDLE;
        current = -1;
        arrivedAt = -1;
        holding = false;
        message = "";
        stopTicker();
        Path path = file.get();
        if (path != null) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException ex) {
                System.out.println("Could not delete " + path + ": " + ex.getMessage());
            }
        }
        dirty = false;
    }

    public synchronized void setStopTimeoutMillis(long stopTimeoutMillis) {
        this.stopTimeoutMillis = stopTimeoutMillis;
    }

    /**
     * @param chunkBudgetPerMinute at most this many chunks per minute, 0 for no limit. The addon is told to hold when
     *                             chunks arrive faster than this.
     */
    public synchronized void setChunkBudgetPerMinute(int chunkBudgetPerMinute) {
        this.chunkBudgetPerMinute = Math.max(0, chunkBudgetPerMinute);
    }

    public synchronized int getChunkBudgetPerMinute() {
        return chunkBudgetPerMinute;
    }

    // --- progress ---

    /**
     * Check the current stop. Called every half second while a route exists.
     */
    public synchronized void tick() {
        ensureLoaded();
        long now = clock.getAsLong();
        recordPosition(now);

        if (state == State.RUNNING && route != null && current >= 0) {
            RouteStop stop = route.stops.get(current);
            if (stop.missing(captured) == 0) {
                finish(stop, now);
                advance();
            } else {
                double[] pos = position.get();
                boolean arrived = pos != null && stop.distanceTo(pos[0], pos[1]) <= ARRIVE_BLOCKS;
                if (!arrived) {
                    arrivedAt = -1;
                } else if (arrivedAt < 0) {
                    arrivedAt = now;
                } else if (now - arrivedAt >= stopTimeoutMillis) {
                    stop.attempts++;
                    stop.status = stop.attempts >= MAX_ATTEMPTS ? RouteStop.Status.SKIPPED : RouteStop.Status.RETRY;
                    message = stop.attempts >= MAX_ATTEMPTS
                        ? "Stop " + (current + 1) + " skipped after " + stop.attempts + " timeouts"
                        : "Stop " + (current + 1) + " timed out, will retry later";
                    advance();
                }
            }
            holding = chunkBudgetPerMinute > 0 && chunksPerSecond.getAsDouble() * 60 > chunkBudgetPerMinute;
        } else {
            holding = false;
        }

        if (dirty && now - lastSave >= SAVE_INTERVAL_MILLIS) {
            saveNow();
        }
    }

    private void finish(RouteStop stop, long now) {
        stop.status = RouteStop.Status.DONE;
        if (arrivedAt >= 0) {
            holdTotalMillis += now - arrivedAt;
            holdCount++;
        }
        message = "";
    }

    /**
     * Move on to the next open stop: the pending ones first, then the ones to retry. Marks the route done when
     * there is none left.
     */
    private void advance() {
        arrivedAt = -1;
        dirty = true;
        int next = find(current + 1, RouteStop.Status.PENDING);
        if (next < 0) {
            next = find(0, RouteStop.Status.RETRY);
        }
        if (next < 0) {
            current = -1;
            if (state != State.IDLE) {
                state = State.DONE;
            }
            return;
        }
        if (next < current && route.stops.get(next).status == RouteStop.Status.RETRY) {
            // second pass: order the retries from where we are
            RouteStop here = route.stops.get(current);
            reorderRemaining(here.x(), here.z());
            return;
        }
        current = next;
    }

    private int find(int from, RouteStop.Status status) {
        for (int i = Math.max(0, from); i < route.stops.size(); i++) {
            if (route.stops.get(i).status == status) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Keep the finished stops in place and re-order the open ones from the given position.
     */
    private void reorderRemaining(double x, double z) {
        List<RouteStop> closed = new ArrayList<>();
        List<RouteStop> open = new ArrayList<>();
        for (RouteStop stop : route.stops) {
            (stop.isOpen() ? open : closed).add(stop);
        }
        if (open.isEmpty()) {
            current = -1;
            return;
        }
        List<RouteStop> ordered = RoutePlanner.order(open, x, z, REORDER_MILLIS);
        route.stops = new ArrayList<>(closed.size() + ordered.size());
        route.stops.addAll(closed);
        route.stops.addAll(ordered);
        current = closed.size();
        arrivedAt = -1;
    }

    private void recordPosition(long now) {
        double[] pos = position.get();
        if (pos == null || state != State.RUNNING) {
            track.clear();
            return;
        }
        track.addLast(new double[]{now, pos[0], pos[1]});
        while (!track.isEmpty() && track.peekFirst()[0] < now - SPEED_WINDOW_MILLIS) {
            track.removeFirst();
        }
    }

    /**
     * Flying speed over the last half minute in blocks per second, or NaN if it isn't known yet.
     */
    public synchronized double blocksPerSecond() {
        if (track.size() < 2) {
            return Double.NaN;
        }
        double[] first = track.peekFirst();
        double[] last = track.peekLast();
        double millis = last[0] - first[0];
        if (millis < 5000) {
            return Double.NaN;
        }
        double distance = 0;
        double[] prev = null;
        for (double[] p : track) {
            if (prev != null) {
                distance += Math.hypot(p[1] - prev[1], p[2] - prev[2]);
            }
            prev = p;
        }
        return distance / (millis / 1000);
    }

    /**
     * Seconds the rest of the route should take at the measured speed, or -1 if the speed isn't known.
     */
    public synchronized long etaSeconds() {
        if (route == null || current < 0) {
            return route != null && state == State.DONE ? 0 : -1;
        }
        double speed = blocksPerSecond();
        if (Double.isNaN(speed) || speed < 0.5) {
            return -1;
        }
        double[] pos = position.get();
        RouteStop stop = route.stops.get(current);
        double x = pos == null ? stop.x() : pos[0];
        double z = pos == null ? stop.z() : pos[1];
        double blocks = route.remainingLength(current, x, z);
        long hold = holdCount > 0 ? holdTotalMillis / holdCount : DEFAULT_HOLD_MILLIS;
        return Math.round(blocks / speed + route.countOpen() * hold / 1000.0);
    }

    // --- views ---

    public synchronized Route getRoute() {
        ensureLoaded();
        return route;
    }

    public synchronized State getState() {
        ensureLoaded();
        return state;
    }

    public synchronized int getCurrentIndex() {
        return current;
    }

    public synchronized RouteStop getCurrentStop() {
        ensureLoaded();
        return route == null || current < 0 ? null : route.stops.get(current);
    }

    public synchronized boolean isHolding() {
        return holding;
    }

    public synchronized String getMessage() {
        return message;
    }

    /**
     * What the addon asks for: where to fly next and whether to wait there.
     */
    public synchronized Map<String, Object> next() {
        ensureLoaded();
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("state", state.name());
        res.put("hold", holding);
        RouteStop stop = getCurrentStop();
        res.put("stop", stop == null ? 0 : current + 1);
        res.put("stopsTotal", route == null ? 0 : route.stops.size());
        res.put("stopsLeft", route == null ? 0 : route.countOpen());
        if (stop != null) {
            res.put("x", stop.x());
            res.put("z", stop.z());
            res.put("chunkX", stop.chunkX);
            res.put("chunkZ", stop.chunkZ);
            res.put("missing", stop.missing(captured));
            res.put("attempt", stop.attempts + 1);
        }
        res.put("radius", route == null ? 0 : route.radius);
        res.put("message", message);
        return res;
    }

    /**
     * Progress of the whole route.
     */
    public synchronized Map<String, Object> status() {
        ensureLoaded();
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("state", state.name());
        res.put("hold", holding);
        res.put("stops", route == null ? 0 : route.stops.size());
        res.put("done", route == null ? 0 : route.count(RouteStop.Status.DONE));
        res.put("pending", route == null ? 0 : route.count(RouteStop.Status.PENDING));
        res.put("retry", route == null ? 0 : route.count(RouteStop.Status.RETRY));
        res.put("skipped", route == null ? 0 : route.count(RouteStop.Status.SKIPPED));
        res.put("current", current < 0 ? 0 : current + 1);
        res.put("radius", route == null ? 0 : route.radius);
        res.put("targetChunks", route == null ? 0 : route.targetChunks);
        res.put("description", route == null ? "" : route.description);
        res.put("lengthBlocks", route == null ? 0 : Math.round(route.length()));
        double[] pos = position.get();
        if (route != null && current >= 0) {
            RouteStop stop = route.stops.get(current);
            res.put("remainingBlocks", Math.round(route.remainingLength(current,
                pos == null ? stop.x() : pos[0], pos == null ? stop.z() : pos[1])));
        } else {
            res.put("remainingBlocks", 0);
        }
        double speed = blocksPerSecond();
        res.put("blocksPerSecond", Double.isNaN(speed) ? -1 : Math.round(speed * 10) / 10.0);
        res.put("etaSeconds", etaSeconds());
        res.put("chunksPerMinute", Math.round(chunksPerSecond.getAsDouble() * 60));
        res.put("chunkBudgetPerMinute", chunkBudgetPerMinute);
        res.put("message", message);
        return res;
    }

    /**
     * One line for the map's status corner, e.g. "route 12/1329 · 3.8 h left".
     */
    public synchronized String summary() {
        ensureLoaded();
        if (route == null) {
            return "";
        }
        int closed = route.stops.size() - route.countOpen();
        String text = "route " + closed + "/" + route.stops.size();
        switch (state) {
            case PAUSED -> text += " (paused)";
            case PLANNED -> text += " (planned)";
            case DONE -> text += " (done)";
            default -> { }
        }
        if (holding) {
            text += " (holding)";
        }
        long eta = etaSeconds();
        if (state == State.RUNNING && eta >= 0) {
            text += " · " + formatEta(eta) + " left";
        }
        return text;
    }

    public static String formatEta(long seconds) {
        if (seconds < 60) {
            return seconds + " s";
        }
        if (seconds < 3600) {
            return Math.round(seconds / 60.0) + " min";
        }
        return String.format("%.1f h", seconds / 3600.0);
    }

    // --- persistence ---

    private static class Saved {
        Route route;
        int current;
        State state;
        int chunkBudgetPerMinute;
    }

    private void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        Path path = file.get();
        if (path == null || !Files.isRegularFile(path)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(path)) {
            Saved saved = new Gson().fromJson(reader, Saved.class);
            if (saved == null || saved.route == null || saved.route.stops == null || saved.route.stops.isEmpty()) {
                return;
            }
            route = saved.route;
            current = saved.current;
            chunkBudgetPerMinute = saved.chunkBudgetPerMinute;
            // never take off on our own after a restart
            state = saved.state == State.RUNNING ? State.PAUSED : saved.state;
            if (state == null) {
                state = State.PAUSED;
            }
            if (current < 0 || current >= route.stops.size() || !route.stops.get(current).isOpen()) {
                current = -1;
                advance();
                if (state != State.DONE) {
                    state = route.countOpen() == 0 ? State.DONE : State.PAUSED;
                }
            }
            startTicker();
            System.out.println("Loaded route from " + path + ": " + summary());
        } catch (Exception ex) {
            System.out.println("Could not read " + path + ": " + ex.getMessage());
        }
    }

    private void saveNow() {
        dirty = false;
        lastSave = clock.getAsLong();
        Path path = file.get();
        if (path == null) {
            return;
        }
        if (route == null) {
            return;
        }
        Saved saved = new Saved();
        saved.route = route;
        saved.current = current;
        saved.state = state;
        saved.chunkBudgetPerMinute = chunkBudgetPerMinute;
        try {
            Files.createDirectories(path.getParent());
            Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
            Files.writeString(tmp, new GsonBuilder().create().toJson(saved));
            Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            System.out.println("Could not save route to " + path + ": " + ex.getMessage());
        }
    }

    /**
     * Write the current state to disk now, e.g. on exit.
     */
    public synchronized void save() {
        if (route != null) {
            saveNow();
        }
    }

    private void startTicker() {
        if (ticker != null) {
            return;
        }
        ticker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "Route Session");
            thread.setDaemon(true);
            return thread;
        });
        ticker.scheduleWithFixedDelay(() -> {
            try {
                tick();
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }, TICK_MILLIS, TICK_MILLIS, TimeUnit.MILLISECONDS);
    }

    private void stopTicker() {
        if (ticker != null) {
            ticker.shutdownNow();
            ticker = null;
        }
    }

    public synchronized void shutdown() {
        save();
        stopTicker();
    }
}
