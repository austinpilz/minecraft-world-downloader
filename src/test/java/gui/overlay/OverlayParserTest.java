package gui.overlay;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javafx.scene.paint.Color;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class OverlayParserTest {

    private static OverlayParser.Result parseResource(String name) throws IOException {
        try (Reader reader = new InputStreamReader(
                OverlayParserTest.class.getResourceAsStream("/overlays/" + name), StandardCharsets.UTF_8)) {
            return OverlayParser.parse(reader);
        }
    }

    private static OverlayShape find(OverlayParser.Result result, String label) {
        return result.getSets().stream()
            .flatMap(set -> set.getShapes().stream())
            .filter(shape -> shape.getLabel().equals(label))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no shape labelled " + label));
    }

    /**
     * Trimmed copy of maps/world/live/markers.json from a BlueMap 5 server (MegaCityCraft) with the BlueBridge
     * WorldGuard and world border addons.
     */
    @Test
    void bluemap() throws IOException {
        OverlayParser.Result result = parseResource("bluemap-megacitycraft-world.json");

        assertThat(result.getFormat()).isEqualTo(OverlayParser.Format.BLUEMAP);
        assertThat(result.getSkipped()).isZero();
        assertThat(result.getSets()).extracting(OverlaySet::getLabel)
            .containsExactly("WorldGuard Regions", "World borders");
        assertThat(result.countShapes()).isEqualTo(5);

        OverlayShape hirdarth = find(result, "hirdarth");
        assertThat(hirdarth.getKind()).isEqualTo(OverlayShape.Kind.POLYGON);
        assertThat(hirdarth.getX()).containsExactly(6640, 6841, 6841, 6640);
        assertThat(hirdarth.getZ()).containsExactly(7690, 7690, 7891, 7891);
        assertThat(hirdarth.getLabelX()).isEqualTo(6740.5);
        assertThat(hirdarth.getLabelZ()).isEqualTo(7790.5);
        assertThat(hirdarth.getLineWidth()).isEqualTo(2);
        assertThat(hirdarth.getStroke()).isEqualTo(Color.rgb(0, 96, 255, 1));
        assertThat(hirdarth.getFill().getOpacity()).isCloseTo(0.588, within(0.001));
        assertThat(hirdarth.getFill().getBlue()).isEqualTo(1.0);

        // the world border is drawn as an outline only
        OverlaySet borders = result.getSets().get(1);
        OverlayShape border = borders.getShapes().get(0);
        assertThat(border.getMinX()).isEqualTo(-18000);
        assertThat(border.getMaxZ()).isEqualTo(18000);
        assertThat(border.getFill().getOpacity()).isZero();
    }

    /**
     * Trimmed copy of tiles/_markers_/marker_new.json from the MRT server's Dynmap.
     */
    @Test
    void dynmap() throws IOException {
        OverlayParser.Result result = parseResource("dynmap-mrt-new.json");

        assertThat(result.getFormat()).isEqualTo(OverlayParser.Format.DYNMAP);
        assertThat(result.getSkipped()).isZero();
        assertThat(result.getSets()).extracting(OverlaySet::getLabel)
            .containsExactly("Spawn Locations", "[X] MRT Expo Line", "World Border");
        assertThat(result.countShapes()).isEqualTo(5);

        OverlayShape spawn = find(result, "Spawn");
        assertThat(spawn.getKind()).isEqualTo(OverlayShape.Kind.POINT);
        assertThat(spawn.getX()).containsExactly(0);

        OverlayShape station = find(result, "XE47 Station");
        assertThat(station.getX()).containsExactly(20457);
        assertThat(station.getZ()).containsExactly(-3147);

        OverlayShape line = find(result, "MRT Expo Line West - Extension 1 (XWX1)");
        assertThat(line.getKind()).isEqualTo(OverlayShape.Kind.POLYLINE);
        assertThat(line.getX()).hasSize(6);
        assertThat(line.getZ()).startsWith(-2521, -2566);
        assertThat(line.getStroke().getOpacity()).isCloseTo(0.8, within(0.01));
        assertThat(line.getFill()).isEqualTo(Color.TRANSPARENT);
        assertThat(line.getLineWidth()).isEqualTo(3);

        OverlayShape border = result.getSets().get(2).getShapes().get(0);
        assertThat(border.getKind()).isEqualTo(OverlayShape.Kind.POLYGON);
        assertThat(border.getStroke()).isEqualTo(Color.web("#FF0000", 1.0));
        assertThat(border.getFill().getOpacity()).isZero();
        assertThat(border.getMinX()).isEqualTo(-29999.5);
        assertThat(border.getMaxX()).isEqualTo(30000.5);
    }

    @Test
    void dynmapDefaultsAndRectangles() {
        OverlayParser.Result result = OverlayParser.parse("""
            {"sets": {"claims": {"label": "Claims", "areas": {
                "a": {"x": [0, 32], "z": [0, 48], "label": "<b>Town</b> hall"}
            }}}}
            """);

        OverlayShape area = result.getSets().get(0).getShapes().get(0);
        assertThat(area.getLabel()).isEqualTo("Town hall");
        assertThat(area.getX()).hasSize(4);
        assertThat(area.getMaxX()).isEqualTo(32);
        assertThat(area.getMaxZ()).isEqualTo(48);
        assertThat(area.getStroke()).isEqualTo(Color.web("#FF0000", 0.8));
        assertThat(area.getFill().getOpacity()).isCloseTo(0.35, within(0.01));
        assertThat(area.getLineWidth()).isEqualTo(3);
    }

    @Test
    void nativeFormat() {
        OverlayParser.Result result = OverlayParser.parse("""
            {"shapes": [
                {"type": "polygon", "points": [[0, 0], [100, 0], [50, 80]], "label": "Triangle"},
                {"type": "rect", "from": [-64, -64], "to": [63, 63], "set": "Spawn", "color": "#00ff00"},
                {"type": "line", "points": [[0, 0], [500, 0]], "label": "Rail"},
                {"type": "circle", "center": [1000, 1000], "radius": 200, "fill": "#0000ff40"},
                {"type": "point", "at": [5, 7], "label": "Here &amp; there"},
                {"type": "polygon", "points": [[0, 0], [1, 1]]},
                {"type": "triangle"},
                "not a shape"
            ]}
            """);

        assertThat(result.getFormat()).isEqualTo(OverlayParser.Format.NATIVE);
        assertThat(result.getSets()).extracting(OverlaySet::getLabel).containsExactly("Shapes", "Spawn");
        assertThat(result.countShapes()).isEqualTo(5);
        assertThat(result.getSkipped()).isEqualTo(3);

        OverlayShape rect = result.getSets().get(1).getShapes().get(0);
        assertThat(rect.getMinX()).isEqualTo(-64);
        assertThat(rect.getMaxZ()).isEqualTo(63);
        assertThat(rect.getStroke()).isEqualTo(Color.web("#00ff00"));
        assertThat(rect.getFill()).isEqualTo(Color.color(0, 1, 0, 0.15));

        List<OverlayShape> shapes = result.getSets().get(0).getShapes();
        assertThat(shapes).extracting(OverlayShape::getKind).containsExactly(
            OverlayShape.Kind.POLYGON, OverlayShape.Kind.POLYLINE, OverlayShape.Kind.CIRCLE, OverlayShape.Kind.POINT);
        assertThat(shapes.get(1).getFill()).isEqualTo(Color.TRANSPARENT);
        assertThat(shapes.get(2).getRadiusX()).isEqualTo(200);
        assertThat(shapes.get(2).getFill().getOpacity()).isCloseTo(0.25, within(0.01));
        assertThat(shapes.get(3).getLabel()).isEqualTo("Here & there");
    }

    @Test
    void bluemapHolesAndLines() {
        OverlayParser.Result result = OverlayParser.parse("""
            {"regions": {"label": "Regions", "markers": {
                "ring": {"type": "shape", "label": "Ring",
                         "shape": [{"x": 0, "z": 0}, {"x": 100, "z": 0}, {"x": 100, "z": 100}, {"x": 0, "z": 100}],
                         "holes": [[{"x": 40, "z": 40}, {"x": 60, "z": 40}, {"x": 60, "z": 60}, {"x": 40, "z": 60}]]},
                "road": {"type": "line", "label": "Road", "line": [{"x": 0, "y": 64, "z": 0}, {"x": 10, "y": 64, "z": 0}]},
                "shop": {"type": "poi", "label": "Shop", "position": {"x": 3, "y": 70, "z": 4}},
                "bad": {"type": "shape", "shape": [{"x": 0, "z": 0}]}
            }}}
            """);

        assertThat(result.getFormat()).isEqualTo(OverlayParser.Format.BLUEMAP);
        assertThat(result.countShapes()).isEqualTo(3);
        assertThat(result.getSkipped()).isEqualTo(1);

        OverlayShape ring = find(result, "Ring");
        assertThat(ring.getHoles()).hasSize(1);
        assertThat(ring.contains(10, 10, 0)).isTrue();
        assertThat(ring.contains(50, 50, 0)).isFalse();

        assertThat(find(result, "Road").getKind()).isEqualTo(OverlayShape.Kind.POLYLINE);
        assertThat(find(result, "Shop").getX()).containsExactly(3);
    }

    @Test
    void unknownFormat() {
        assertThatThrownBy(() -> OverlayParser.parse("{\"foo\": 1}"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OverlayParser.parse("[1, 2, 3]"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OverlayParser.parse("{}"))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
