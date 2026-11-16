package game.data;

import static game.data.SessionStats.RATE_WINDOW_MILLIS;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionStatsTest {
    private final AtomicLong time = new AtomicLong(100_000);
    private final SessionStats stats = new SessionStats(time::get);

    @Test
    void chunkRateIsAveragedOverTheWindow() {
        for (int i = 0; i < 50; i++) {
            stats.chunkReceived(false);
        }
        assertThat(stats.chunksPerSecond()).isEqualTo(50 * 1000.0 / RATE_WINDOW_MILLIS);

        time.addAndGet(RATE_WINDOW_MILLIS - 1);
        stats.chunkReceived(false);
        assertThat(stats.chunksPerSecond()).isEqualTo(51 * 1000.0 / RATE_WINDOW_MILLIS);

        // the first 50 chunks are now too old to count
        time.addAndGet(1);
        assertThat(stats.chunksPerSecond()).isEqualTo(1 * 1000.0 / RATE_WINDOW_MILLIS);

        time.addAndGet(RATE_WINDOW_MILLIS);
        assertThat(stats.chunksPerSecond()).isZero();
    }

    @Test
    void sessionStartsWithTheFirstChunk() {
        assertThat(stats.sessionMillis()).isEqualTo(-1);
        time.addAndGet(10_000);
        assertThat(stats.sessionMillis()).isEqualTo(-1);

        stats.chunkReceived(false);
        time.addAndGet(3_000);
        stats.chunkReceived(false);
        assertThat(stats.sessionMillis()).isEqualTo(3_000);
    }

    @Test
    void chunksWhilePausedDontStartASession() {
        stats.chunkReceived(true);
        assertThat(stats.sessionMillis()).isEqualTo(-1);
        assertThat(stats.chunksPerSecond()).isPositive();
    }

    @Test
    void pausingEndsTheSessionAndResumingStartsANewOne() {
        stats.chunkReceived(false);
        time.addAndGet(60_000);

        stats.endSession();
        assertThat(stats.sessionMillis()).isEqualTo(-1);
        stats.chunkReceived(true);
        assertThat(stats.sessionMillis()).isEqualTo(-1);

        time.addAndGet(5_000);
        stats.startSession();
        time.addAndGet(2_000);
        stats.chunkReceived(false);
        assertThat(stats.sessionMillis()).isEqualTo(2_000);
    }

    @Test
    void directorySizeCountsNestedFiles(@TempDir Path dir) throws IOException {
        Files.write(dir.resolve("level.dat"), new byte[100]);
        Files.createDirectories(dir.resolve("region"));
        Files.write(dir.resolve("region").resolve("r.0.0.mca"), new byte[4096]);

        assertThat(SessionStats.directorySize(dir)).isEqualTo(4196);
        assertThat(SessionStats.directorySize(dir.resolve("missing"))).isZero();
    }

    @Test
    void formatting() {
        assertThat(SessionStats.formatSize(0)).isEqualTo(String.format("%.1f MB", 0.0));
        assertThat(SessionStats.formatSize(512L * 1024 * 1024)).isEqualTo(String.format("%.1f MB", 512.0));
        assertThat(SessionStats.formatSize(3L * 1024 * 1024 * 1024 / 2)).isEqualTo(String.format("%.2f GB", 1.5));

        assertThat(SessionStats.formatDuration(0)).isEqualTo("0:00:00");
        assertThat(SessionStats.formatDuration(65_999)).isEqualTo("0:01:05");
        assertThat(SessionStats.formatDuration((26 * 3600 + 3 * 60 + 7) * 1000L)).isEqualTo("26:03:07");
    }
}
