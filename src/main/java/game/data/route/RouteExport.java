package game.data.route;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Writes a route as an overlay file in the downloader's own shape format, so it can be imported and looked at on the
 * map before anyone flies it: one line through all the stops, and a numbered point per stop.
 */
public class RouteExport {
    public static final String SET = "Route";

    private RouteExport() { }

    public static String toJson(Route route) {
        JsonArray shapes = new JsonArray();

        JsonArray points = new JsonArray();
        points.add(pair(route.startX, route.startZ));
        for (RouteStop stop : route.stops) {
            points.add(pair(stop.x(), stop.z()));
        }
        JsonObject line = new JsonObject();
        line.addProperty("type", "line");
        line.addProperty("label", "Route (" + route.stops.size() + " stops)");
        line.addProperty("set", SET);
        line.addProperty("color", "#ff8800");
        line.addProperty("width", 2);
        line.add("points", points);
        shapes.add(line);

        JsonObject start = new JsonObject();
        start.addProperty("type", "point");
        start.addProperty("label", "Start");
        start.addProperty("set", SET);
        start.addProperty("color", "#ffffff");
        start.add("at", pair(route.startX, route.startZ));
        shapes.add(start);

        for (int i = 0; i < route.stops.size(); i++) {
            RouteStop stop = route.stops.get(i);
            JsonObject point = new JsonObject();
            point.addProperty("type", "point");
            point.addProperty("label", String.valueOf(i + 1));
            point.addProperty("set", SET);
            point.addProperty("color", stop.status == RouteStop.Status.DONE ? "#33dd55" : "#ff8800");
            point.add("at", pair(stop.x(), stop.z()));
            shapes.add(point);
        }

        JsonObject root = new JsonObject();
        root.add("shapes", shapes);
        return new GsonBuilder().setPrettyPrinting().create().toJson(root);
    }

    public static void write(Route route, Path path) throws IOException {
        Files.writeString(path, toJson(route));
    }

    private static JsonArray pair(double x, double z) {
        JsonArray pair = new JsonArray();
        pair.add(x);
        pair.add(z);
        return pair;
    }
}
