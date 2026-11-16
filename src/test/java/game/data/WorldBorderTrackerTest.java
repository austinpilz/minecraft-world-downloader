package game.data;

import static org.assertj.core.api.Assertions.assertThat;

import game.data.WorldBorderTracker.Border;
import game.data.dimension.Dimension;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorldBorderTrackerTest {
    @TempDir
    Path dir;

    private WorldBorderTracker tracker() {
        return new WorldBorderTracker(dim -> dir.resolve(dim.getName().replace(':', '_') + ".txt"));
    }

    @Test
    void borderIsRestoredInTheNextSession() {
        WorldBorderTracker first = tracker();
        assertThat(first.get(Dimension.OVERWORLD)).isNull();
        first.setBorder(Dimension.OVERWORLD, 0.5, -100, 28000);

        Border border = tracker().get(Dimension.OVERWORLD);
        assertThat(border).isEqualTo(new Border(0.5, -100, 28000));
        assertThat(border.minX()).isEqualTo(-13999.5);
        assertThat(border.maxZ()).isEqualTo(13900);
    }

    @Test
    void centerAndSizeUpdateTheKnownBorder() {
        WorldBorderTracker first = tracker();

        // nothing to update without a border
        first.setCenter(Dimension.OVERWORLD, 5, 5);
        first.setSize(Dimension.OVERWORLD, 100);
        assertThat(first.get(Dimension.OVERWORLD)).isNull();

        first.setBorder(Dimension.OVERWORLD, 0, 0, 1000);
        first.setCenter(Dimension.OVERWORLD, 50, -50);
        first.setSize(Dimension.OVERWORLD, 200);

        assertThat(tracker().get(Dimension.OVERWORLD)).isEqualTo(new Border(50, -50, 200));
    }

    @Test
    void dimensionsAreKeptSeparate() {
        WorldBorderTracker tracker = tracker();
        tracker.setBorder(Dimension.OVERWORLD, 0, 0, 1000);
        tracker.setBorder(Dimension.NETHER, 10, 10, 125);

        WorldBorderTracker next = tracker();
        assertThat(next.get(Dimension.OVERWORLD).size()).isEqualTo(1000);
        assertThat(next.get(Dimension.NETHER).size()).isEqualTo(125);
        assertThat(next.get(Dimension.END)).isNull();
    }

    @Test
    void invalidBordersAreIgnored() throws IOException {
        WorldBorderTracker tracker = tracker();
        tracker.setBorder(Dimension.OVERWORLD, 0, 0, 0);
        tracker.setBorder(Dimension.OVERWORLD, Double.NaN, 0, 100);
        assertThat(tracker.get(Dimension.OVERWORLD)).isNull();

        Files.writeString(dir.resolve("minecraft_the_nether.txt"), "# comment\nnot a border\n1 2\n3 4 500\n");
        assertThat(tracker().get(Dimension.NETHER)).isEqualTo(new Border(3, 4, 500));
    }
}
