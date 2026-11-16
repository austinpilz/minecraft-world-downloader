package game.data.route;

import config.Config;
import game.data.WorldManager;
import game.data.coordinates.Coordinate3D;
import game.data.dimension.Dimension;
import gui.overlay.ChunkCoverage;
import gui.overlay.OverlayManager;

/**
 * Plans a route from the overlay shapes currently shown on the map and hands it to the {@link RouteSession}. Used by
 * the map's right-click menu and by the local API.
 */
public class RouteService {
    public static final int FALLBACK_RADIUS = 8;

    private RouteService() { }

    /**
     * The view distance to plan with when the user hasn't picked one: the configured one, else the one the server
     * reported, else {@link #FALLBACK_RADIUS}.
     */
    public static int defaultRadius() {
        int configured = Config.getRouteRadius();
        if (configured > 0) {
            return configured;
        }
        int server = WorldManager.getInstance().getServerViewDistance();
        return server > 0 ? Math.min(server, RoutePlanner.MAX_RADIUS) : FALLBACK_RADIUS;
    }

    /**
     * Plan a route over the chunks of the shown overlay sets of the current dimension and install it as the current
     * route. Safe to call from any thread; takes a few seconds for thousands of stops.
     *
     * @param radius       chunks loaded in every direction around the player
     * @param buffer       extra chunks to capture around every shape
     * @param skipCaptured leave out chunks that are already saved
     * @throws IllegalStateException if there is nothing to plan over
     */
    public static RoutePlanner.Result plan(int radius, int buffer, boolean skipCaptured, long optimiseMillis) {
        WorldManager manager = WorldManager.getInstance();
        Dimension dimension = manager.getDimension() == null ? Dimension.OVERWORLD : manager.getDimension();
        OverlayManager overlays = OverlayManager.getInstance();

        OverlayManager.Selection selection = overlays.collectVisibleChunks(dimension);
        if (selection.chunks().length == 0) {
            throw new IllegalStateException("No overlay shapes are shown for " + dimension + ". Import or show an overlay first.");
        }

        long[] targets = RoutePlanner.dilate(selection.chunks(), buffer);
        int before = targets.length;
        if (skipCaptured) {
            ChunkCoverage coverage = overlays.getCoverage();
            targets = RoutePlanner.removeCaptured(targets, coverage::isCaptured);
        }
        if (targets.length == 0) {
            throw new IllegalStateException("All " + before + " chunks of the shown shapes are already saved.");
        }

        double startX = 0;
        double startZ = 0;
        if (manager.isPlayerPositionKnown()) {
            Coordinate3D player = manager.getPlayerPosition();
            startX = player.getX();
            startZ = player.getZ();
        }

        RoutePlanner.Result result = RoutePlanner.plan(targets, radius, startX, startZ, optimiseMillis);
        String description = selection.shapes() + " shapes in " + selection.sets() + " sets"
            + (buffer > 0 ? ", " + buffer + " chunk buffer" : "")
            + (skipCaptured && before != targets.length ? ", " + (before - targets.length) + " chunks already saved" : "");
        Route route = new Route(dimension.toString(), radius, startX, startZ, description, targets.length, result.stops());

        RouteSession session = manager.getRouteSession();
        session.setChunkBudgetPerMinute(Config.getRouteChunkBudget());
        session.setRoute(route);
        return result;
    }

    public static String describe(RoutePlanner.Result result) {
        return String.format("Route: %d stops, %.1f km over %d chunks at view distance %d",
            result.stops().size(), result.lengthBlocks() / 1000, result.targetChunks(), result.radius());
    }
}
