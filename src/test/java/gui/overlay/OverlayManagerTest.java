package gui.overlay;

import game.data.WorldBorderTracker.Border;
import game.data.coordinates.Coordinate2D;
import game.data.dimension.Dimension;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OverlayManagerTest {

    @Test
    void serverBorderBecomesWorldBorderShapeWithAxes() {
        OverlayShape shape = OverlayManager.borderShape(new Border(100, -50, 2000));

        assertThat(shape.getKind()).isEqualTo(OverlayShape.Kind.POLYGON);
        assertThat(shape.getLabel()).isEqualTo("World border");
        assertThat(shape.isShowAxes()).isTrue();
        assertThat(shape.getMinX()).isEqualTo(-900);
        assertThat(shape.getMaxX()).isEqualTo(1100);
        assertThat(shape.getMinZ()).isEqualTo(-1050);
        assertThat(shape.getMaxZ()).isEqualTo(950);
    }

    @Test
    void serverBorderShapeFollowsTheBorderAndDimension() {
        Map<Dimension, Border> borders = new HashMap<>();
        OverlayManager manager = new OverlayManager();
        manager.setServerBorders(borders::get);

        assertThat(manager.getServerBorderShape(Dimension.OVERWORLD)).isNull();
        assertThat(manager.getServerBorderShape(null)).isNull();

        borders.put(Dimension.OVERWORLD, new Border(0, 0, 1000));
        OverlayShape first = manager.getServerBorderShape(Dimension.OVERWORLD);
        assertThat(first.getMaxX()).isEqualTo(500);
        // the same border keeps the same shape, so its chunk count isn't lost
        assertThat(manager.getServerBorderShape(Dimension.OVERWORLD)).isSameAs(first);

        borders.put(Dimension.OVERWORLD, new Border(0, 0, 400));
        assertThat(manager.getServerBorderShape(Dimension.OVERWORLD).getMaxX()).isEqualTo(200);

        assertThat(manager.getServerBorderShape(Dimension.NETHER)).isNull();
    }

    @Test
    void serverBorderCountsSavedChunks() {
        Map<Dimension, Border> borders = new HashMap<>();
        OverlayManager manager = new OverlayManager();
        manager.setServerBorders(borders::get);
        manager.getCoverage().reset(Dimension.OVERWORLD);

        // 2 by 2 chunks
        borders.put(Dimension.OVERWORLD, new Border(0, 0, 32));
        manager.getCoverage().chunkSaved(Dimension.OVERWORLD, new Coordinate2D(0, 0));
        manager.updateCoverage(Dimension.OVERWORLD);
        assertThat(manager.getServerBorderShape(Dimension.OVERWORLD).getCoverageText()).isEqualTo("1/4 chunks");

        // a changed border is counted again
        borders.put(Dimension.OVERWORLD, new Border(8, 8, 16));
        manager.updateCoverage(Dimension.OVERWORLD);
        assertThat(manager.getServerBorderShape(Dimension.OVERWORLD).getCoverageText()).isEqualTo("1/1 chunks");
        assertThat(manager.getServerBorderShape(Dimension.OVERWORLD).isCaptured()).isTrue();
    }
}
