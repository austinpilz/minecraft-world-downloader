package game.data;

import config.Config;
import game.data.coordinates.Coordinate2D;
import game.data.coordinates.CoordinateDim2D;
import game.data.dimension.Dimension;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Several bots in one world: each sees its own position and dimension, and one being moved elsewhere doesn't stop
 * the others.
 */
class FleetSessionTest {
    @TempDir
    Path dir;

    private WorldManager worldManager;
    private PlayerSession a;
    private PlayerSession b;

    @BeforeEach
    void setUp() {
        Config config = new Config();
        config.worldOutputDir = dir.toString();
        Config.setInstance(config);

        worldManager = spy(new WorldManager());
        WorldManager.setInstance(worldManager);
        worldManager.setFleet(true);

        a = PlayerSession.fleet("BotA");
        b = PlayerSession.fleet("BotB");
        worldManager.addFleetSession(a);
        worldManager.addFleetSession(b);
    }

    @AfterEach
    void tearDown() {
        PlayerSession.bind(null);
    }

    private void as(PlayerSession session, Runnable r) {
        PlayerSession.bind(session);
        try {
            r.run();
        } finally {
            PlayerSession.bind(null);
        }
    }

    private void drainFactory() throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        worldManager.getChunkFactory().runOnFactoryThread(done::countDown);
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void positionAndDimensionArePerBot() {
        as(a, () -> {
            worldManager.setDimension(Dimension.OVERWORLD);
            worldManager.setPlayerPosition(160, 64, 320);
        });
        as(b, () -> {
            worldManager.setDimension(Dimension.NETHER);
            worldManager.setPlayerPosition(-32, 70, 16);
        });

        as(a, () -> assertThat(worldManager.getDimension()).isEqualTo(Dimension.OVERWORLD));
        as(b, () -> assertThat(worldManager.getDimension()).isEqualTo(Dimension.NETHER));
        assertThat(a.getChunkPosition()).isEqualTo(new Coordinate2D(10, 20));
        assertThat(b.getChunkPosition()).isEqualTo(new Coordinate2D(-2, 1));

        // the first bot to be somewhere is what the GUI follows, also for threads without a bot
        assertThat(worldManager.focus()).isSameAs(a);
        assertThat(worldManager.getDimension()).isEqualTo(Dimension.OVERWORLD);
    }

    @Test
    void chunksNearAnyBotOfTheDimensionStayLoaded() {
        as(a, () -> {
            worldManager.setDimension(Dimension.OVERWORLD);
            worldManager.setPlayerPosition(0, 64, 0);
        });
        as(b, () -> {
            worldManager.setDimension(Dimension.OVERWORLD);
            worldManager.setPlayerPosition(1600, 64, 0);
        });

        assertThat(worldManager.playerChunks(Dimension.OVERWORLD))
            .containsExactlyInAnyOrder(new Coordinate2D(0, 0), new Coordinate2D(100, 0));
        assertThat(worldManager.playerChunks(Dimension.NETHER)).isEmpty();
    }

    @Test
    void chunkIsOnlyUnloadedOnceNoBotHasIt() throws InterruptedException {
        CoordinateDim2D chunk = new CoordinateDim2D(3, 4, Dimension.OVERWORLD);
        as(a, () -> worldManager.chunkReceived(chunk));
        as(b, () -> worldManager.chunkReceived(chunk));

        as(a, () -> worldManager.forgetChunk(chunk));
        drainFactory();
        verify(worldManager, never()).unloadChunk(chunk);

        as(b, () -> worldManager.forgetChunk(chunk));
        drainFactory();
        verify(worldManager).unloadChunk(chunk);
    }

    @Test
    void disconnectingBotUnloadsOnlyItsOwnChunks() throws InterruptedException {
        CoordinateDim2D shared = new CoordinateDim2D(0, 0, Dimension.OVERWORLD);
        CoordinateDim2D own = new CoordinateDim2D(9, 9, Dimension.OVERWORLD);
        as(a, () -> {
            worldManager.chunkReceived(shared);
            worldManager.chunkReceived(own);
        });
        as(b, () -> worldManager.chunkReceived(shared));

        worldManager.endFleetSession(a);
        drainFactory();

        verify(worldManager).unloadChunk(own);
        verify(worldManager, never()).unloadChunk(shared);
        assertThat(worldManager.getFleetSessions()).containsExactly(b);
    }

    @Test
    void botInAnotherWorldStopsSavingAlone() {
        as(a, () -> worldManager.checkWorldSeed(1234));
        as(b, () -> worldManager.checkWorldSeed(1234));
        as(b, () -> worldManager.checkWorldSeed(9999));

        assertThat(b.isQuarantined()).isTrue();
        assertThat(a.isQuarantined()).isFalse();
        as(a, () -> assertThat(worldManager.acceptsData()).isTrue());
        as(b, () -> assertThat(worldManager.acceptsData()).isFalse());

        b.resume();
        as(b, () -> assertThat(worldManager.acceptsData()).isTrue());
    }

    @Test
    void secondLoginMeansAnotherServer() {
        as(a, worldManager::onLogin);
        assertThat(a.isQuarantined()).isFalse();

        as(a, worldManager::onLogin);
        assertThat(a.isQuarantined()).isTrue();
        assertThat(b.isQuarantined()).isFalse();
    }

    @Test
    void reconfigurationAfterLoginMeansAnotherServer() {
        as(a, worldManager::onReconfiguration);
        assertThat(a.isQuarantined()).isFalse();

        as(a, worldManager::onLogin);
        as(a, worldManager::onReconfiguration);
        assertThat(a.isQuarantined()).isTrue();
    }

    @Test
    void singleUserIsNeverQuarantined() {
        worldManager.setFleet(false);
        worldManager.onLogin();
        worldManager.onLogin();
        worldManager.onReconfiguration();

        assertThat(worldManager.session().isFleet()).isFalse();
        assertThat(worldManager.session().isQuarantined()).isFalse();
        assertThat(worldManager.acceptsData()).isTrue();
    }

    @Test
    void sessionCarriesOverToOtherThreads() throws InterruptedException {
        PlayerSession[] seen = new PlayerSession[1];
        PlayerSession.bind(b);
        Thread thread = new Thread(PlayerSession.capture(() -> seen[0] = PlayerSession.bound()));
        PlayerSession.bind(null);

        thread.start();
        thread.join();
        assertThat(seen[0]).isSameAs(b);
    }
}
