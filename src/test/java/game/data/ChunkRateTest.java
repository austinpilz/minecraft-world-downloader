package game.data;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChunkRateTest {
    @Test
    void countsOnlyTheLastMinute() {
        AtomicLong now = new AtomicLong(1_000_000);
        ChunkRate rate = new ChunkRate(now::get);

        rate.add();
        rate.add();
        now.addAndGet(30_000);
        rate.add();
        assertThat(rate.perMinute()).isEqualTo(3);

        now.addAndGet(31_000);
        assertThat(rate.perMinute()).isEqualTo(1);

        now.addAndGet(30_000);
        assertThat(rate.perMinute()).isZero();
    }

    @Test
    void bucketFromAnEarlierMinuteStartsOver() {
        AtomicLong now = new AtomicLong(5_000);
        ChunkRate rate = new ChunkRate(now::get);

        rate.add();
        rate.add();
        // the same bucket a minute later
        now.addAndGet(60_000);
        rate.add();

        assertThat(rate.perMinute()).isEqualTo(1);
    }
}
