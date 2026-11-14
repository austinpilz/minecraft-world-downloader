package gui.images;

import game.data.coordinates.Coordinate2D;
import game.data.coordinates.CoordinateDim2D;
import game.data.dimension.Dimension;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class EmptyChunkTrackerTest {
    @TempDir
    Path dir;

    private EmptyChunkTracker tracker() {
        return new EmptyChunkTracker(dim -> dir.resolve(dim.getName().replace(':', '_') + ".txt"));
    }

    private static CoordinateDim2D chunk(int x, int z, Dimension dim) {
        return new CoordinateDim2D(x, z, dim);
    }

    @Test
    void emptyChunksAreRestoredInTheNextSession() {
        EmptyChunkTracker first = tracker();
        first.setDimension(Dimension.OVERWORLD);
        first.setEmpty(chunk(3, -40, Dimension.OVERWORLD), true);
        first.setEmpty(chunk(-1, -1, Dimension.OVERWORLD), true);
        first.save();

        EmptyChunkTracker second = tracker();
        second.setDimension(Dimension.OVERWORLD);

        assertThat(second.isEmpty(chunk(3, -40, Dimension.OVERWORLD))).isTrue();
        assertThat(second.isEmpty(chunk(-1, -1, Dimension.OVERWORLD))).isTrue();
        assertThat(second.isEmpty(chunk(0, 0, Dimension.OVERWORLD))).isFalse();
        assertThat(second.count()).isEqualTo(2);
    }

    @Test
    void chunkThatLaterHasBlocksIsNoLongerEmpty() {
        EmptyChunkTracker first = tracker();
        first.setDimension(Dimension.OVERWORLD);
        first.setEmpty(chunk(5, 5, Dimension.OVERWORLD), true);
        first.save();

        assertThat(first.setEmpty(chunk(5, 5, Dimension.OVERWORLD), false)).isTrue();
        assertThat(first.isEmpty(chunk(5, 5, Dimension.OVERWORLD))).isFalse();
        assertThat(first.getRegions()).isEmpty();
        first.save();

        EmptyChunkTracker second = tracker();
        second.setDimension(Dimension.OVERWORLD);
        assertThat(second.count()).isZero();
    }

    @Test
    void chunksAreGroupedByRegion() {
        EmptyChunkTracker tracker = tracker();
        tracker.setDimension(Dimension.OVERWORLD);
        tracker.setEmpty(chunk(0, 0, Dimension.OVERWORLD), true);
        tracker.setEmpty(chunk(31, 31, Dimension.OVERWORLD), true);
        tracker.setEmpty(chunk(-1, 32, Dimension.OVERWORLD), true);

        assertThat(tracker.getRegions()).containsExactlyInAnyOrder(new Coordinate2D(0, 0), new Coordinate2D(-1, 1));
        assertThat(tracker.getChunksInRegion(new Coordinate2D(0, 0)))
            .containsExactlyInAnyOrder(new Coordinate2D(0, 0), new Coordinate2D(31, 31));
        assertThat(tracker.getChunksInRegion(new Coordinate2D(5, 5))).isEmpty();
    }

    @Test
    void dimensionsAreKeptSeparate() {
        EmptyChunkTracker tracker = tracker();
        tracker.setDimension(Dimension.OVERWORLD);
        tracker.setEmpty(chunk(1, 1, Dimension.OVERWORLD), true);

        // chunks of another dimension than the current one are ignored
        assertThat(tracker.setEmpty(chunk(2, 2, Dimension.NETHER), true)).isFalse();

        // switching dimension saves the previous one
        tracker.setDimension(Dimension.NETHER);
        assertThat(tracker.isEmpty(chunk(1, 1, Dimension.NETHER))).isFalse();
        tracker.setEmpty(chunk(2, 2, Dimension.NETHER), true);

        tracker.setDimension(Dimension.OVERWORLD);
        assertThat(tracker.isEmpty(chunk(1, 1, Dimension.OVERWORLD))).isTrue();
        assertThat(tracker.isEmpty(chunk(2, 2, Dimension.OVERWORLD))).isFalse();

        tracker.setDimension(Dimension.NETHER);
        assertThat(tracker.isEmpty(chunk(2, 2, Dimension.NETHER))).isTrue();
    }

    @Test
    void nothingIsWrittenUntilAChunkIsMarked() {
        EmptyChunkTracker tracker = tracker();
        tracker.setDimension(Dimension.OVERWORLD);
        tracker.save();

        assertThat(dir.resolve("minecraft_overworld.txt")).doesNotExist();
    }

    @Test
    void unreadableLinesAreSkipped() throws IOException {
        Files.writeString(dir.resolve("minecraft_overworld.txt"), "# comment\n1 2\nnot a chunk\n3\n\n -4   5 \n");

        EmptyChunkTracker tracker = tracker();
        tracker.setDimension(Dimension.OVERWORLD);

        assertThat(tracker.count()).isEqualTo(2);
        assertThat(tracker.isEmpty(chunk(1, 2, Dimension.OVERWORLD))).isTrue();
        assertThat(tracker.isEmpty(chunk(-4, 5, Dimension.OVERWORLD))).isTrue();
    }
}
