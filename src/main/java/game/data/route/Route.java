package game.data.route;

import java.util.ArrayList;
import java.util.List;

/**
 * A planned tour of hover points over the chunks that still need saving. Stops are in flying order. Plain fields so
 * the route can be written to the route file with Gson.
 */
public class Route {
    public String dimension;
    /** view distance in chunks the route was planned for */
    public int radius;
    public long createdAt;
    public double startX;
    public double startZ;
    /** what the route was planned from, e.g. "2 sets, 745 shapes" */
    public String description = "";
    public int targetChunks;
    public List<RouteStop> stops = new ArrayList<>();

    public Route() { }

    public Route(String dimension, int radius, double startX, double startZ, String description, int targetChunks, List<RouteStop> stops) {
        this.dimension = dimension;
        this.radius = radius;
        this.createdAt = System.currentTimeMillis();
        this.startX = startX;
        this.startZ = startZ;
        this.description = description == null ? "" : description;
        this.targetChunks = targetChunks;
        this.stops = stops;
    }

    public int count(RouteStop.Status status) {
        int count = 0;
        for (RouteStop stop : stops) {
            if (stop.status == status) {
                count++;
            }
        }
        return count;
    }

    public int countOpen() {
        int count = 0;
        for (RouteStop stop : stops) {
            if (stop.isOpen()) {
                count++;
            }
        }
        return count;
    }

    /**
     * Length of the whole route in blocks, from the start point through every stop in order.
     */
    public double length() {
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
     * Blocks still to fly: from the given position to the first open stop at or after {@code fromIndex}, then on
     * through the remaining open stops in order.
     */
    public double remainingLength(int fromIndex, double x, double z) {
        double length = 0;
        for (int i = Math.max(0, fromIndex); i < stops.size(); i++) {
            RouteStop stop = stops.get(i);
            if (!stop.isOpen()) {
                continue;
            }
            length += stop.distanceTo(x, z);
            x = stop.x();
            z = stop.z();
        }
        return length;
    }
}
