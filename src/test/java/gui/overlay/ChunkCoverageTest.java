package gui.overlay;

import game.data.coordinates.Coordinate2D;
import game.data.dimension.Dimension;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChunkCoverageTest {

    private static OverlayLayer layer(Dimension dimension, OverlayShape... shapes) {
        OverlaySet set = new OverlaySet("test", "Test");
        set.getShapes().addAll(List.of(shapes));
        return new OverlayLayer(Path.of("test.json"), dimension.toString(), List.of(set), true, Set.of());
    }

    private static void saveArea(ChunkCoverage coverage, Dimension dimension, int minX, int minZ, int maxX, int maxZ) {
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                coverage.chunkSaved(dimension, new Coordinate2D(x, z));
            }
        }
    }

    @Test
    void shapeIsCapturedOnceAllChunksAreSaved() {
        // two chunks wide, one chunk high
        OverlayShape shape = OverlayShape.rectangle(0, 0, 32, 16);
        List<OverlayLayer> layers = List.of(layer(Dimension.OVERWORLD, shape));

        ChunkCoverage coverage = new ChunkCoverage();
        coverage.reset(Dimension.OVERWORLD);
        coverage.update(layers, 0);
        assertThat(shape.getCoverageText()).isEqualTo("0/2 chunks");
        assertThat(shape.isCaptured()).isFalse();

        coverage.chunkSaved(Dimension.OVERWORLD, new Coordinate2D(0, 0));
        coverage.chunkSaved(Dimension.OVERWORLD, new Coordinate2D(0, 0));
        coverage.chunkSaved(Dimension.OVERWORLD, new Coordinate2D(5, 5));
        coverage.update(layers, 0);
        assertThat(shape.getCoverageText()).isEqualTo("1/2 chunks");
        assertThat(shape.isCaptured()).isFalse();

        coverage.chunkSaved(Dimension.OVERWORLD, new Coordinate2D(1, 0));
        coverage.update(layers, 0);
        assertThat(shape.isCaptured()).isTrue();
    }

    @Test
    void otherDimensionsAreIgnored() {
        OverlayShape overworldShape = OverlayShape.rectangle(0, 0, 16, 16);
        OverlayShape netherShape = OverlayShape.rectangle(0, 0, 16, 16);
        List<OverlayLayer> layers = List.of(layer(Dimension.OVERWORLD, overworldShape), layer(Dimension.NETHER, netherShape));

        ChunkCoverage coverage = new ChunkCoverage();
        coverage.reset(Dimension.OVERWORLD);
        coverage.chunkSaved(Dimension.NETHER, new Coordinate2D(0, 0));
        coverage.update(layers, 0);
        assertThat(overworldShape.isCaptured()).isFalse();

        coverage.chunkSaved(Dimension.OVERWORLD, new Coordinate2D(0, 0));
        coverage.update(layers, 0);
        assertThat(overworldShape.isCaptured()).isTrue();
        assertThat(netherShape.isCaptured()).isFalse();

        // switching dimension forgets the chunks of the previous one
        coverage.reset(Dimension.NETHER);
        coverage.update(layers, 0);
        assertThat(netherShape.isCaptured()).isFalse();
        coverage.chunkSaved(Dimension.NETHER, new Coordinate2D(0, 0));
        coverage.update(layers, 0);
        assertThat(netherShape.isCaptured()).isTrue();
    }

    @Test
    void layersAddedLaterCountChunksSavedBefore() {
        ChunkCoverage coverage = new ChunkCoverage();
        coverage.reset(Dimension.OVERWORLD);
        saveArea(coverage, Dimension.OVERWORLD, -2, -2, 1, 1);

        List<OverlayLayer> layers = new ArrayList<>();
        coverage.update(layers, 0);

        OverlayShape shape = OverlayShape.rectangle(-32, -32, 32, 32);
        layers.add(layer(Dimension.OVERWORLD, shape));
        coverage.update(layers, 1);
        assertThat(shape.getCoverageText()).isEqualTo("16/16 chunks");
        assertThat(shape.isCaptured()).isTrue();
    }

    /**
     * The hirdarth WorldGuard region from the MegaCityCraft BlueMap fixture: X 6640..6841, Z 7690..7891 covers chunks
     * 415..427 by 480..493.
     */
    @Test
    void megaCityCraftRegion() throws IOException {
        OverlayParser.Result result;
        try (Reader reader = new InputStreamReader(
                getClass().getResourceAsStream("/overlays/bluemap-megacitycraft-world.json"), StandardCharsets.UTF_8)) {
            result = OverlayParser.parse(reader);
        }
        OverlayLayer layer = new OverlayLayer(Path.of("markers.json"), Dimension.OVERWORLD.toString(), result.getSets(), true, Set.of());
        OverlayShape hirdarth = result.getSets().get(0).getShapes().stream()
            .filter(shape -> shape.getLabel().equals("hirdarth")).findFirst().orElseThrow();
        OverlayShape border = result.getSets().get(1).getShapes().get(0);

        ChunkCoverage coverage = new ChunkCoverage();
        coverage.reset(Dimension.OVERWORLD);
        saveArea(coverage, Dimension.OVERWORLD, 415, 480, 427, 492);
        coverage.update(List.of(layer), 0);
        assertThat(hirdarth.getCoverageText()).isEqualTo("169/182 chunks");
        assertThat(hirdarth.isCaptured()).isFalse();

        saveArea(coverage, Dimension.OVERWORLD, 415, 493, 427, 493);
        coverage.update(List.of(layer), 0);
        assertThat(hirdarth.isCaptured()).isTrue();

        // the world border is far too large to track
        assertThat(border.isCoverageTracked()).isFalse();
        assertThat(border.isCaptured()).isFalse();
        assertThat(border.getCoverageText()).isEmpty();
    }

    @Test
    void chunksInRegionHeader() {
        byte[] header = new byte[4096];
        // local chunk 0,0 and local chunk 1,1 (index 33) are present, local chunk 2,0 has an offset but no size
        header[3] = 1;
        header[33 * 4 + 3] = 2;
        header[2 * 4 + 2] = 5;

        assertThat(ChunkCoverage.chunksInHeader(header, -1, 2))
            .containsExactly(ChunkCoverage.key(-32, 64), ChunkCoverage.key(-31, 65));
        assertThat(ChunkCoverage.chunksInHeader(new byte[0], 0, 0)).isEmpty();
    }
}
