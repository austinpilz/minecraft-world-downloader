package game.data.region;

import config.Config;
import game.data.coordinates.CoordinateDim2D;
import game.data.dimension.Dimension;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class RegionStoreTest {
    @TempDir
    Path worldDir;

    private RegionStore store;

    @BeforeEach
    void setUp() {
        Config config = new Config();
        config.worldOutputDir = worldDir.toString();
        Config.setInstance(config);

        store = new RegionStore();
    }

    @Test
    void sameRegionIsReused() {
        CoordinateDim2D coords = new CoordinateDim2D(1, -3, Dimension.OVERWORLD);
        AtomicReference<Region> first = new AtomicReference<>();
        AtomicReference<Region> second = new AtomicReference<>();

        store.withRegion(coords, first::set);
        store.withRegion(coords, second::set);

        assertThat(second.get()).isSameAs(first.get());
        assertThat(store.get(coords)).isSameAs(first.get());
        assertThat(store.countRegions()).isEqualTo(1);
    }

    @Test
    void dimensionsAreSeparate() {
        CoordinateDim2D overworld = new CoordinateDim2D(0, 0, Dimension.OVERWORLD);
        CoordinateDim2D nether = new CoordinateDim2D(0, 0, Dimension.NETHER);

        store.withRegion(overworld, r -> { });
        store.withRegion(nether, r -> { });

        assertThat(store.get(overworld)).isNotSameAs(store.get(nether));
        assertThat(store.regions(Dimension.NETHER)).containsExactly(store.get(nether));

        store.clear(Dimension.NETHER);
        assertThat(store.get(nether)).isNull();
        assertThat(store.get(overworld)).isNotNull();
    }

    @Test
    void emptyRegionIsRemoved() {
        CoordinateDim2D coords = new CoordinateDim2D(2, 2, Dimension.OVERWORLD);
        store.withRegion(coords, r -> { });

        store.removeIfEmpty(coords);

        assertThat(store.get(coords)).isNull();
        assertThat(store.regions(Dimension.OVERWORLD)).isEmpty();
    }

    @Test
    void savingEmptyRegionWritesNothing() throws Exception {
        CoordinateDim2D coords = new CoordinateDim2D(0, 0, Dimension.OVERWORLD);
        AtomicReference<Region> region = new AtomicReference<>();
        store.withRegion(coords, region::set);

        region.get().save(List.of());

        assertThat(worldDir.resolve("region")).doesNotExist();
        assertThat(region.get().canRemove()).isTrue();
    }

    @Test
    void regionIsNotCreatedWhileItsLockIsHeld() throws Exception {
        CoordinateDim2D coords = new CoordinateDim2D(5, 7, Dimension.OVERWORLD);
        ReentrantLock lock = store.lock(coords);
        CountDownLatch created = new CountDownLatch(1);

        lock.lock();
        try {
            Thread thread = new Thread(() -> store.withRegion(coords, r -> created.countDown()));
            thread.start();

            // e.g. an older copy of the region is still being written
            assertThat(created.await(200, TimeUnit.MILLISECONDS)).isFalse();
            assertThat(store.get(coords)).isNull();
        } finally {
            lock.unlock();
        }

        assertThat(created.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(store.get(coords)).isNotNull();
    }
}
