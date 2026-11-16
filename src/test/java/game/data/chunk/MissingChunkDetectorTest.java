package game.data.chunk;

import static game.data.chunk.MissingChunkDetector.WAIT_MILLIS;
import static org.assertj.core.api.Assertions.assertThat;

import game.data.coordinates.Coordinate2D;
import game.data.coordinates.CoordinateDim2D;
import game.data.dimension.Dimension;
import java.util.List;
import org.junit.jupiter.api.Test;

class MissingChunkDetectorTest {
    private static final Dimension OVERWORLD = Dimension.OVERWORLD;
    private static final Coordinate2D ORIGIN = new Coordinate2D(0, 0);

    private static CoordinateDim2D chunk(int x, int z) {
        return new CoordinateDim2D(x, z, OVERWORLD);
    }

    @Test
    void chunksAreReportedOnlyAfterWaiting() {
        MissingChunkDetector detector = new MissingChunkDetector();

        assertThat(detector.tick(OVERWORLD, ORIGIN, 1, 0)).isEmpty();
        assertThat(detector.tick(OVERWORLD, ORIGIN, 1, WAIT_MILLIS - 1)).isEmpty();

        // radius 1 is a plus shape: the centre and its 4 neighbours
        assertThat(detector.tick(OVERWORLD, ORIGIN, 1, WAIT_MILLIS))
            .containsExactlyInAnyOrder(chunk(0, 0), chunk(1, 0), chunk(-1, 0), chunk(0, 1), chunk(0, -1));

        // each chunk is only reported once
        assertThat(detector.tick(OVERWORLD, ORIGIN, 1, WAIT_MILLIS * 2)).isEmpty();
    }

    @Test
    void receivedChunksAreNotReported() {
        MissingChunkDetector detector = new MissingChunkDetector();
        detector.chunkReceived(chunk(0, 0));
        detector.chunkReceived(chunk(1, 0));

        detector.tick(OVERWORLD, ORIGIN, 1, 0);
        detector.chunkReceived(chunk(0, 1));

        assertThat(detector.tick(OVERWORLD, ORIGIN, 1, WAIT_MILLIS))
            .containsExactlyInAnyOrder(chunk(-1, 0), chunk(0, -1));
    }

    @Test
    void chunksPassedQuicklyAreNotReported() {
        MissingChunkDetector detector = new MissingChunkDetector();

        detector.tick(OVERWORLD, ORIGIN, 0, 0);
        // the player moved on before the wait was over, and came back later
        detector.tick(OVERWORLD, new Coordinate2D(10, 0), 0, 1000);
        assertThat(detector.tick(OVERWORLD, ORIGIN, 0, WAIT_MILLIS)).isEmpty();
        assertThat(detector.tick(OVERWORLD, ORIGIN, 0, WAIT_MILLIS * 2)).containsExactly(chunk(0, 0));
    }

    @Test
    void pausingRestartsTheWait() {
        MissingChunkDetector detector = new MissingChunkDetector();

        detector.tick(OVERWORLD, ORIGIN, 0, 0);
        detector.pause();
        assertThat(detector.tick(OVERWORLD, ORIGIN, 0, WAIT_MILLIS)).isEmpty();
        assertThat(detector.tick(OVERWORLD, ORIGIN, 0, WAIT_MILLIS * 2)).containsExactly(chunk(0, 0));
    }

    @Test
    void chunksOfOtherDimensionsDontCount() {
        MissingChunkDetector detector = new MissingChunkDetector();
        detector.chunkReceived(new CoordinateDim2D(0, 0, Dimension.NETHER));

        detector.tick(OVERWORLD, ORIGIN, 0, 0);
        List<CoordinateDim2D> missing = detector.tick(OVERWORLD, ORIGIN, 0, WAIT_MILLIS);
        assertThat(missing).containsExactly(chunk(0, 0));
        assertThat(missing.get(0).getDimension()).isEqualTo(OVERWORLD);

        // after changing dimension, chunks can be reported again for the new dimension
        detector.tick(Dimension.NETHER, ORIGIN, 0, WAIT_MILLIS);
        detector.tick(OVERWORLD, ORIGIN, 0, WAIT_MILLIS * 2);
        assertThat(detector.tick(OVERWORLD, ORIGIN, 0, WAIT_MILLIS * 3)).containsExactly(chunk(0, 0));
    }

    @Test
    void edgeOfViewDistanceIsSkipped() {
        assertThat(MissingChunkDetector.detectionRadius(10)).isEqualTo(8);
        assertThat(MissingChunkDetector.detectionRadius(1)).isZero();
    }
}
