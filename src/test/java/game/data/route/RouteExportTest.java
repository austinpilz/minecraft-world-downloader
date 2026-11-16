package game.data.route;

import gui.overlay.ChunkCoverage;
import gui.overlay.OverlayParser;
import gui.overlay.OverlayShape;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RouteExportTest {

    @Test
    void exportedRouteReadsBackAsOverlay() {
        RouteStop first = new RouteStop(10, 10, new long[]{ChunkCoverage.key(10, 10)});
        first.status = RouteStop.Status.DONE;
        RouteStop second = new RouteStop(20, -5, new long[]{ChunkCoverage.key(20, -5)});
        Route route = new Route("minecraft:overworld", 8, 100, 200, "test", 2, List.of(first, second));

        OverlayParser.Result result = OverlayParser.parse(RouteExport.toJson(route));
        assertThat(result.getFormat()).isEqualTo(OverlayParser.Format.NATIVE);
        assertThat(result.getSkipped()).isZero();
        assertThat(result.getSets()).hasSize(1);
        assertThat(result.getSets().get(0).getLabel()).isEqualTo(RouteExport.SET);

        List<OverlayShape> shapes = result.getSets().get(0).getShapes();
        assertThat(shapes).hasSize(4);
        OverlayShape line = shapes.get(0);
        assertThat(line.getKind()).isEqualTo(OverlayShape.Kind.POLYLINE);
        assertThat(line.getX()).containsExactly(100, 168, 328);
        assertThat(line.getZ()).containsExactly(200, 168, -72);
        assertThat(line.getLabel()).isEqualTo("Route (2 stops)");

        assertThat(shapes.get(1).getKind()).isEqualTo(OverlayShape.Kind.POINT);
        assertThat(shapes.get(1).getLabel()).isEqualTo("Start");
        assertThat(shapes.get(2).getLabel()).isEqualTo("1");
        assertThat(shapes.get(3).getLabel()).isEqualTo("2");
        assertThat(shapes.get(3).getX()[0]).isEqualTo(328);
    }
}
