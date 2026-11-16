package gui;

import config.Config;
import game.data.route.Route;
import game.data.route.RouteSession;
import game.data.route.RouteStop;
import java.util.List;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;

/**
 * Draws the planned route on top of the map: the path through the stops, finished legs in green, the current stop
 * with the square of chunks that has to be loaded there.
 */
public class RouteRenderer {
    private static final Color OPEN_COLOR = Color.web("#ffaa00", 0.85);
    private static final Color DONE_COLOR = Color.web("#33dd55", 0.6);
    private static final Color SKIPPED_COLOR = Color.rgb(160, 160, 160, 0.6);
    private static final Color CURRENT_COLOR = Color.WHITE;
    private static final Color SQUARE_COLOR = Color.web("#ffffff", 0.35);
    private static final double STOP_SIZE = 4;

    private final RouteSession session;

    public RouteRenderer(RouteSession session) {
        this.session = session;
    }

    public void draw(GraphicsContext graphics, Bounds bounds, double blocksPerPixel) {
        if (!Config.showRoute()) {
            return;
        }
        Route route = session.getRoute();
        if (route == null) {
            return;
        }
        // the stop list is replaced on re-plan, not edited in place
        List<RouteStop> stops = route.stops;
        int current = session.getCurrentIndex();

        graphics.save();
        graphics.setLineWidth(1.5);

        double prevX = screenX(route.startX, bounds, blocksPerPixel);
        double prevZ = screenZ(route.startZ, bounds, blocksPerPixel);
        for (int i = 0; i < stops.size(); i++) {
            RouteStop stop = stops.get(i);
            double x = screenX(stop.x(), bounds, blocksPerPixel);
            double z = screenZ(stop.z(), bounds, blocksPerPixel);
            if (onScreen(prevX, prevZ, bounds, blocksPerPixel) || onScreen(x, z, bounds, blocksPerPixel)) {
                graphics.setStroke(colorFor(stop, i == current));
                graphics.strokeLine(prevX, prevZ, x, z);
            }
            prevX = x;
            prevZ = z;
        }

        double size = blocksPerPixel > 32 ? 2 : STOP_SIZE;
        for (int i = 0; i < stops.size(); i++) {
            RouteStop stop = stops.get(i);
            double x = screenX(stop.x(), bounds, blocksPerPixel);
            double z = screenZ(stop.z(), bounds, blocksPerPixel);
            if (!onScreen(x, z, bounds, blocksPerPixel)) {
                continue;
            }
            graphics.setFill(colorFor(stop, i == current));
            graphics.fillOval(x - size / 2, z - size / 2, size, size);
        }

        if (current >= 0 && current < stops.size()) {
            RouteStop stop = stops.get(current);
            double side = (2 * route.radius + 1) * 16 / blocksPerPixel;
            double left = screenX(stop.chunkX * 16 - route.radius * 16, bounds, blocksPerPixel);
            double top = screenZ(stop.chunkZ * 16 - route.radius * 16, bounds, blocksPerPixel);
            graphics.setStroke(SQUARE_COLOR);
            graphics.setLineDashes(6, 6);
            graphics.strokeRect(left, top, side, side);
            graphics.setLineDashes();

            double x = screenX(stop.x(), bounds, blocksPerPixel);
            double z = screenZ(stop.z(), bounds, blocksPerPixel);
            graphics.setStroke(CURRENT_COLOR);
            graphics.setLineWidth(2);
            graphics.strokeOval(x - 8, z - 8, 16, 16);
        }
        graphics.restore();
    }

    private static Color colorFor(RouteStop stop, boolean current) {
        if (current) {
            return CURRENT_COLOR;
        }
        return switch (stop.status) {
            case DONE -> DONE_COLOR;
            case SKIPPED -> SKIPPED_COLOR;
            default -> OPEN_COLOR;
        };
    }

    private static boolean onScreen(double screenX, double screenZ, Bounds bounds, double blocksPerPixel) {
        double width = (bounds.getMaxX() - bounds.getMinX()) / blocksPerPixel;
        double height = (bounds.getMaxZ() - bounds.getMinZ()) / blocksPerPixel;
        return screenX >= -50 && screenX <= width + 50 && screenZ >= -50 && screenZ <= height + 50;
    }

    private static double screenX(double worldX, Bounds bounds, double blocksPerPixel) {
        return (worldX - bounds.getMinX()) / blocksPerPixel;
    }

    private static double screenZ(double worldZ, Bounds bounds, double blocksPerPixel) {
        return (worldZ - bounds.getMinZ()) / blocksPerPixel;
    }
}
