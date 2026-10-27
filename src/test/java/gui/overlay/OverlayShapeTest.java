package gui.overlay;

import game.data.dimension.Dimension;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OverlayShapeTest {

    @Test
    void polygonContains() {
        OverlayShape shape = OverlayShape.polygon(
            new double[]{6640, 6841, 6841, 6640}, new double[]{7690, 7690, 7891, 7891}, List.of());

        assertThat(shape.contains(6700, 7700, 0)).isTrue();
        assertThat(shape.contains(6639, 7700, 0)).isFalse();
        assertThat(shape.contains(6700, 7900, 0)).isFalse();
        assertThat(shape.getLabelX()).isEqualTo(6740.5);
        assertThat(shape.boundingArea()).isEqualTo(201 * 201);
    }

    @Test
    void polygonWithHole() {
        double[][] hole = {{4, 6, 6, 4}, {4, 4, 6, 6}};
        OverlayShape shape = OverlayShape.polygon(new double[]{0, 10, 10, 0}, new double[]{0, 0, 10, 10}, Collections.singletonList(hole));

        assertThat(shape.contains(2, 2, 0)).isTrue();
        assertThat(shape.contains(5, 5, 0)).isFalse();
    }

    @Test
    void circleContains() {
        OverlayShape shape = OverlayShape.circle(100, 100, 50, 20);

        assertThat(shape.contains(140, 100, 0)).isTrue();
        assertThat(shape.contains(100, 125, 0)).isFalse();
        assertThat(shape.getMinX()).isEqualTo(50);
        assertThat(shape.getMaxZ()).isEqualTo(120);
    }

    @Test
    void lineAndPointUseTolerance() {
        OverlayShape line = OverlayShape.polyline(new double[]{0, 100}, new double[]{0, 0});
        assertThat(line.contains(50, 3, 4)).isTrue();
        assertThat(line.contains(50, 5, 4)).isFalse();
        assertThat(line.contains(103, 0, 4)).isTrue();

        OverlayShape point = OverlayShape.point(10, 10);
        assertThat(point.contains(12, 12, 4)).isTrue();
        assertThat(point.contains(14, 14, 4)).isFalse();
    }

    @Test
    void rejectsInvalidShapes() {
        assertThatThrownBy(() -> OverlayShape.polygon(new double[]{0, 1}, new double[]{0, 1}, List.of()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OverlayShape.polyline(new double[]{0, 1}, new double[]{0}))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OverlayShape.circle(0, 0, 0, 5))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OverlayShape.point(Double.NaN, 0))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void screenProjectionMatchesChunkMap() {
        // the chunk map draws a block at (world - bounds.min) / blocksPerPixel
        assertThat(OverlayRenderer.toScreen(6640, 6400, 2)).isEqualTo(120);
        assertThat(OverlayRenderer.toScreen(-16, -32, 0.5)).isEqualTo(32);
    }

    @Test
    void guessDimensionFromFileName() {
        assertThat(OverlayImportDialog.guessDimension("marker_world_nether.json", Dimension.OVERWORLD))
            .isEqualTo(Dimension.NETHER);
        assertThat(OverlayImportDialog.guessDimension("marker_world_the_end.json", Dimension.OVERWORLD))
            .isEqualTo(Dimension.END);
        assertThat(OverlayImportDialog.guessDimension("end.json", null)).isEqualTo(Dimension.END);
        // "end" inside another word doesn't count
        assertThat(OverlayImportDialog.guessDimension("legendary.json", Dimension.NETHER))
            .isEqualTo(Dimension.NETHER);
        assertThat(OverlayImportDialog.guessDimension("markers.json", null)).isEqualTo(Dimension.OVERWORLD);
    }

    @Test
    void layerDimension() {
        OverlayLayer layer = new OverlayLayer(java.nio.file.Path.of("x.json"), "minecraft:the_nether",
            List.of(), true, java.util.Set.of());
        assertThat(layer.appliesTo(Dimension.NETHER)).isTrue();
        assertThat(layer.appliesTo(Dimension.OVERWORLD)).isFalse();
        assertThat(OverlayLayer.dimensionLabel("minecraft:the_nether")).isEqualTo("Nether");
        assertThat(OverlayLayer.dimensionLabel("custom:mining")).isEqualTo("custom:mining");
    }

    private static long[] chunks(int... xz) {
        long[] res = new long[xz.length / 2];
        for (int i = 0; i < res.length; i++) {
            res[i] = ChunkCoverage.key(xz[i * 2], xz[i * 2 + 1]);
        }
        java.util.Arrays.sort(res);
        return res;
    }

    @Test
    void edgesOnChunkBordersDontClaimNeighbours() {
        assertThat(OverlayShape.rectangle(0, 0, 32, 16).getChunks()).containsExactly(chunks(0, 0, 1, 0));

        // the hypotenuse only touches the corner of chunk 1,1
        OverlayShape triangle = OverlayShape.polygon(new double[]{0, 32, 0}, new double[]{0, 0, 32}, List.of());
        assertThat(triangle.getChunks()).containsExactly(chunks(0, 0, 1, 0, 0, 1));
    }

    @Test
    void chunksInsideHolesAreExcluded() {
        double[][] hole = {{16, 32, 32, 16}, {16, 16, 32, 32}};
        OverlayShape shape = OverlayShape.polygon(new double[]{0, 48, 48, 0}, new double[]{0, 0, 48, 48}, Collections.singletonList(hole));

        assertThat(shape.getChunks()).hasSize(8).doesNotContain(ChunkCoverage.key(1, 1));
    }

    @Test
    void chunksOfOtherShapes() {
        assertThat(OverlayShape.circle(8, 8, 10, 10).getChunks()).containsExactly(chunks(0, 0, -1, 0, 1, 0, 0, -1, 0, 1));
        assertThat(OverlayShape.polyline(new double[]{0, 40}, new double[]{8, 8}).getChunks()).containsExactly(chunks(0, 0, 1, 0, 2, 0));
        assertThat(OverlayShape.point(-1, -1).getChunks()).containsExactly(chunks(-1, -1));
        assertThat(OverlayShape.point(-16, 0).getChunks()).containsExactly(chunks(-1, 0));
    }

    @Test
    void largeShapesAreNotTracked() {
        assertThat(OverlayShape.rectangle(0, 0, 16 * 256, 16 * 256).isCoverageTracked()).isTrue();
        assertThat(OverlayShape.rectangle(0, 0, 16 * 257, 16 * 257).isCoverageTracked()).isFalse();
    }
}
