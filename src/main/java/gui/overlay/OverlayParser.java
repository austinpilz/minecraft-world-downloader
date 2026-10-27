package gui.overlay;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import javafx.scene.paint.Color;

/**
 * Reads overlay files into shapes. Supported formats are BlueMap's markers.json, Dynmap's marker_[world].json and a
 * minimal format for hand-written files:
 * <pre>
 * {"shapes": [
 *   {"type": "polygon", "label": "Spawn", "points": [[x, z], ...], "color": "#ff0000", "fill": "#ff000040"},
 *   {"type": "rect", "from": [x, z], "to": [x, z]},
 *   {"type": "line", "points": [[x, z], ...], "width": 3},
 *   {"type": "circle", "center": [x, z], "radius": r},
 *   {"type": "point", "at": [x, z], "label": "..."}
 * ]}
 * </pre>
 * Entries that can't be read are skipped and counted, rather than failing the whole file.
 */
public class OverlayParser {
    public enum Format {
        BLUEMAP("BlueMap"), DYNMAP("Dynmap"), NATIVE("overlay shapes");

        private final String name;

        Format(String name) {
            this.name = name;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    public static class Result {
        private final Format format;
        private final List<OverlaySet> sets;
        private final int skipped;

        Result(Format format, List<OverlaySet> sets, int skipped) {
            this.format = format;
            this.sets = sets;
            this.skipped = skipped;
        }

        public Format getFormat() {
            return format;
        }

        public List<OverlaySet> getSets() {
            return sets;
        }

        public int getSkipped() {
            return skipped;
        }

        public int countShapes() {
            return sets.stream().mapToInt(set -> set.getShapes().size()).sum();
        }
    }

    // Dynmap's own defaults for shapes that don't specify a style
    private static final String DYNMAP_COLOR = "#FF0000";
    private static final double DYNMAP_OPACITY = 0.8;
    private static final double DYNMAP_FILL_OPACITY = 0.35;
    private static final double DYNMAP_WEIGHT = 3;

    private int skipped;

    private OverlayParser() { }

    public static Result parse(String json) {
        return parse(new StringReader(json));
    }

    /**
     * @throws IllegalArgumentException if the file is not in any of the supported formats
     * @throws com.google.gson.JsonParseException if the file is not valid JSON
     */
    public static Result parse(Reader reader) {
        JsonElement root = JsonParser.parseReader(reader);
        if (!root.isJsonObject()) {
            throw new IllegalArgumentException("Not a BlueMap, Dynmap or overlay shapes file");
        }
        JsonObject obj = root.getAsJsonObject();

        OverlayParser parser = new OverlayParser();
        if (obj.has("shapes") && obj.get("shapes").isJsonArray()) {
            return new Result(Format.NATIVE, parser.parseNative(obj.getAsJsonArray("shapes")), parser.skipped);
        }
        if (obj.has("sets") && obj.get("sets").isJsonObject()) {
            return new Result(Format.DYNMAP, parser.parseDynmap(obj.getAsJsonObject("sets")), parser.skipped);
        }
        if (isBlueMap(obj)) {
            return new Result(Format.BLUEMAP, parser.parseBlueMap(obj), parser.skipped);
        }
        throw new IllegalArgumentException("Not a BlueMap, Dynmap or overlay shapes file");
    }

    private static boolean isBlueMap(JsonObject obj) {
        if (obj.size() == 0) {
            return false;
        }
        for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
            JsonElement set = entry.getValue();
            if (!set.isJsonObject() || !set.getAsJsonObject().has("markers")) {
                return false;
            }
        }
        return true;
    }

    /**
     * BlueMap: {setId: {label, markers: {markerId: {type, label, position, shape, holes, line, lineColor, ...}}}}
     */
    private List<OverlaySet> parseBlueMap(JsonObject root) {
        List<OverlaySet> sets = new ArrayList<>();
        for (Map.Entry<String, JsonElement> setEntry : root.entrySet()) {
            JsonObject setObj = setEntry.getValue().getAsJsonObject();
            OverlaySet set = new OverlaySet(setEntry.getKey(), stripHtml(string(setObj, "label")));

            JsonElement markers = setObj.get("markers");
            if (markers == null || !markers.isJsonObject()) {
                skipped++;
                continue;
            }
            for (Map.Entry<String, JsonElement> markerEntry : markers.getAsJsonObject().entrySet()) {
                add(set, () -> parseBlueMapMarker(markerEntry.getValue().getAsJsonObject()));
            }
            sets.add(set);
        }
        return sets;
    }

    private OverlayShape parseBlueMapMarker(JsonObject marker) {
        String type = string(marker, "type");
        OverlayShape shape = switch (type == null ? "" : type) {
            case "shape", "extrude" -> {
                double[][] outline = blueMapPoints(marker.getAsJsonArray("shape"));
                List<double[][]> holes = new ArrayList<>();
                if (marker.has("holes")) {
                    for (JsonElement hole : marker.getAsJsonArray("holes")) {
                        holes.add(blueMapPoints(hole.getAsJsonArray()));
                    }
                }
                yield OverlayShape.polygon(outline[0], outline[1], holes)
                    .withStyle(blueMapColor(marker, "lineColor", OverlayShape.DEFAULT_STROKE),
                               blueMapColor(marker, "fillColor", OverlayShape.DEFAULT_FILL),
                               number(marker, "lineWidth", 2));
            }
            case "line" -> {
                double[][] points = blueMapPoints(marker.getAsJsonArray("line"));
                yield OverlayShape.polyline(points[0], points[1])
                    .withStyle(blueMapColor(marker, "lineColor", OverlayShape.DEFAULT_STROKE),
                               Color.TRANSPARENT, number(marker, "lineWidth", 2));
            }
            case "poi", "html" -> {
                JsonObject pos = marker.getAsJsonObject("position");
                yield OverlayShape.point(number(pos, "x"), number(pos, "z"));
            }
            default -> throw new IllegalArgumentException("unknown BlueMap marker type " + type);
        };

        if (marker.has("position") && marker.get("position").isJsonObject()) {
            JsonObject pos = marker.getAsJsonObject("position");
            shape.withLabelPosition(number(pos, "x", Double.NaN), number(pos, "z", Double.NaN));
        }
        return shape.withLabel(stripHtml(string(marker, "label")));
    }

    private static double[][] blueMapPoints(JsonArray arr) {
        double[] x = new double[arr.size()];
        double[] z = new double[arr.size()];
        for (int i = 0; i < arr.size(); i++) {
            JsonObject point = arr.get(i).getAsJsonObject();
            x[i] = number(point, "x");
            z[i] = number(point, "z");
        }
        return new double[][]{x, z};
    }

    /**
     * BlueMap colours are {r, g, b, a} with RGB in 0-255 and alpha in 0-1.
     */
    private static Color blueMapColor(JsonObject marker, String key, Color fallback) {
        if (!marker.has(key) || !marker.get(key).isJsonObject()) {
            return fallback;
        }
        JsonObject c = marker.getAsJsonObject(key);
        return Color.rgb(
            (int) clamp(number(c, "r", 0), 0, 255),
            (int) clamp(number(c, "g", 0), 0, 255),
            (int) clamp(number(c, "b", 0), 0, 255),
            clamp(number(c, "a", 1), 0, 1)
        );
    }

    /**
     * Dynmap: {sets: {setId: {label, markers: {..}, areas: {..}, lines: {..}, circles: {..}}}}. Areas and lines use
     * parallel x[] and z[] arrays.
     */
    private List<OverlaySet> parseDynmap(JsonObject root) {
        List<OverlaySet> sets = new ArrayList<>();
        for (Map.Entry<String, JsonElement> setEntry : root.entrySet()) {
            if (!setEntry.getValue().isJsonObject()) {
                skipped++;
                continue;
            }
            JsonObject setObj = setEntry.getValue().getAsJsonObject();
            OverlaySet set = new OverlaySet(setEntry.getKey(), stripHtml(string(setObj, "label")));

            forEachEntry(setObj, "areas", area -> {
                double[] x = numbers(area.getAsJsonArray("x"));
                double[] z = numbers(area.getAsJsonArray("z"));

                // two corners describe a rectangle
                OverlayShape shape = x.length == 2 && z.length == 2
                    ? OverlayShape.rectangle(x[0], z[0], x[1], z[1])
                    : OverlayShape.polygon(x, z, List.of());
                return shape.withStyle(dynmapStroke(area), dynmapFill(area), number(area, "weight", DYNMAP_WEIGHT));
            }, set);

            forEachEntry(setObj, "lines", line -> OverlayShape
                .polyline(numbers(line.getAsJsonArray("x")), numbers(line.getAsJsonArray("z")))
                .withStyle(dynmapStroke(line), Color.TRANSPARENT, number(line, "weight", DYNMAP_WEIGHT)), set);

            forEachEntry(setObj, "circles", circle -> OverlayShape
                .circle(number(circle, "x"), number(circle, "z"), number(circle, "xr"), number(circle, "zr"))
                .withStyle(dynmapStroke(circle), dynmapFill(circle), number(circle, "weight", DYNMAP_WEIGHT)), set);

            forEachEntry(setObj, "markers", marker -> OverlayShape
                .point(number(marker, "x"), number(marker, "z")), set);

            sets.add(set);
        }
        return sets;
    }

    private void forEachEntry(JsonObject setObj, String key, Function<JsonObject, OverlayShape> reader, OverlaySet set) {
        JsonElement entries = setObj.get(key);
        if (entries == null || !entries.isJsonObject()) {
            return;
        }
        for (Map.Entry<String, JsonElement> entry : entries.getAsJsonObject().entrySet()) {
            add(set, () -> {
                JsonObject obj = entry.getValue().getAsJsonObject();
                return reader.apply(obj).withLabel(stripHtml(string(obj, "label")));
            });
        }
    }

    private static Color dynmapStroke(JsonObject obj) {
        String color = obj.has("color") ? string(obj, "color") : DYNMAP_COLOR;
        return Color.web(color, clamp(number(obj, "opacity", DYNMAP_OPACITY), 0, 1));
    }

    private static Color dynmapFill(JsonObject obj) {
        String color = obj.has("fillcolor") ? string(obj, "fillcolor") : DYNMAP_COLOR;
        return Color.web(color, clamp(number(obj, "fillopacity", DYNMAP_FILL_OPACITY), 0, 1));
    }

    private List<OverlaySet> parseNative(JsonArray shapes) {
        Map<String, OverlaySet> sets = new LinkedHashMap<>();
        for (JsonElement el : shapes) {
            String setName = el.isJsonObject() ? string(el.getAsJsonObject(), "set") : null;
            if (setName == null || setName.isBlank()) {
                setName = "Shapes";
            }
            OverlaySet set = sets.computeIfAbsent(setName, name -> new OverlaySet(name, name));
            add(set, () -> parseNativeShape(el.getAsJsonObject()));
        }
        return new ArrayList<>(sets.values());
    }

    private OverlayShape parseNativeShape(JsonObject obj) {
        String type = string(obj, "type");
        OverlayShape shape = switch (type == null ? "" : type) {
            case "polygon" -> {
                double[][] points = pairs(obj.getAsJsonArray("points"));
                yield OverlayShape.polygon(points[0], points[1], List.of());
            }
            case "rect" -> {
                double[] from = pair(obj.getAsJsonArray("from"));
                double[] to = pair(obj.getAsJsonArray("to"));
                yield OverlayShape.rectangle(from[0], from[1], to[0], to[1]);
            }
            case "line" -> {
                double[][] points = pairs(obj.getAsJsonArray("points"));
                yield OverlayShape.polyline(points[0], points[1]);
            }
            case "circle" -> {
                double[] center = pair(obj.getAsJsonArray("center"));
                double radius = number(obj, "radius");
                yield OverlayShape.circle(center[0], center[1], radius, radius);
            }
            case "point" -> {
                double[] at = pair(obj.getAsJsonArray("at"));
                yield OverlayShape.point(at[0], at[1]);
            }
            default -> throw new IllegalArgumentException("unknown shape type " + type);
        };

        Color stroke = obj.has("color") ? Color.web(string(obj, "color")) : OverlayShape.DEFAULT_STROKE;
        Color fill;
        if (obj.has("fill")) {
            fill = Color.web(string(obj, "fill"));
        } else if (shape.getKind() == OverlayShape.Kind.POLYLINE) {
            fill = Color.TRANSPARENT;
        } else {
            fill = Color.color(stroke.getRed(), stroke.getGreen(), stroke.getBlue(), 0.15);
        }
        return shape.withStyle(stroke, fill, number(obj, "width", 2)).withLabel(stripHtml(string(obj, "label")));
    }

    private static double[][] pairs(JsonArray arr) {
        double[] x = new double[arr.size()];
        double[] z = new double[arr.size()];
        for (int i = 0; i < arr.size(); i++) {
            double[] pair = pair(arr.get(i).getAsJsonArray());
            x[i] = pair[0];
            z[i] = pair[1];
        }
        return new double[][]{x, z};
    }

    private static double[] pair(JsonArray arr) {
        if (arr.size() != 2) {
            throw new IllegalArgumentException("coordinate should be [x, z]");
        }
        return new double[]{arr.get(0).getAsDouble(), arr.get(1).getAsDouble()};
    }

    /**
     * Read a single shape, counting it as skipped if anything about it is malformed.
     */
    private void add(OverlaySet set, Supplier<OverlayShape> reader) {
        try {
            set.getShapes().add(reader.get());
        } catch (RuntimeException ex) {
            skipped++;
        }
    }

    private static double[] numbers(JsonArray arr) {
        double[] res = new double[arr.size()];
        for (int i = 0; i < res.length; i++) {
            res[i] = arr.get(i).getAsDouble();
        }
        return res;
    }

    private static double number(JsonObject obj, String key) {
        double val = obj.get(key).getAsDouble();
        if (!Double.isFinite(val)) {
            throw new IllegalArgumentException(key + " is not a number");
        }
        return val;
    }

    private static double number(JsonObject obj, String key, double fallback) {
        JsonElement el = obj.get(key);
        if (el == null || !el.isJsonPrimitive() || !el.getAsJsonPrimitive().isNumber()) {
            return fallback;
        }
        return el.getAsDouble();
    }

    private static String string(JsonObject obj, String key) {
        JsonElement el = obj.get(key);
        if (el == null || !el.isJsonPrimitive()) {
            return null;
        }
        return el.getAsString();
    }

    private static double clamp(double val, double min, double max) {
        return Math.max(min, Math.min(val, max));
    }

    /**
     * Map plugins allow HTML in labels. We only draw plain text, so remove tags and decode the common entities.
     */
    static String stripHtml(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("(?i)<br\\s*/?>", " ")
            .replaceAll("<[^>]*>", "")
            .replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&amp;", "&")
            .replaceAll("\\s+", " ")
            .trim();
    }
}
