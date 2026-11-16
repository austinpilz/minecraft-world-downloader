package game.data.route;

import gui.overlay.ChunkCoverage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class RouteSessionTest {
    private final Set<Long> saved = new HashSet<>();
    private double[] position;
    private double chunksPerSecond;
    private long now = 1_000_000;

    private RouteSession session(Path file) {
        return new RouteSession(saved::contains, () -> position, () -> chunksPerSecond, () -> file, () -> now);
    }

    private static Route route(int... chunkXs) {
        List<RouteStop> stops = new ArrayList<>();
        for (int chunkX : chunkXs) {
            stops.add(new RouteStop(chunkX, 0, new long[]{ChunkCoverage.key(chunkX, 0)}));
        }
        return new Route("minecraft:overworld", 8, 0, 0, "test", chunkXs.length, stops);
    }

    private static double[] at(RouteStop stop) {
        return new double[]{stop.x(), stop.z()};
    }

    @Test
    void stopsFinishWhenTheirChunksAreSavedNotWhenThePlayerArrives() {
        RouteSession session = session(null);
        assertThat(session.getState()).isEqualTo(RouteSession.State.IDLE);
        assertThat(session.next()).containsEntry("state", "IDLE").containsEntry("stop", 0);
        assertThat(session.start()).isFalse();

        session.setRoute(route(0, 100, 200));
        assertThat(session.getState()).isEqualTo(RouteSession.State.PLANNED);
        assertThat(session.next()).containsEntry("stop", 1).containsEntry("x", 8.0).containsEntry("missing", 1);

        assertThat(session.start()).isTrue();
        assertThat(session.getState()).isEqualTo(RouteSession.State.RUNNING);

        // standing on the stop isn't enough
        position = at(session.getCurrentStop());
        now += 1000;
        session.tick();
        assertThat(session.getCurrentIndex()).isEqualTo(0);

        saved.add(ChunkCoverage.key(0, 0));
        session.tick();
        assertThat(session.getRoute().stops.get(0).status).isEqualTo(RouteStop.Status.DONE);
        assertThat(session.getCurrentIndex()).isEqualTo(1);
        assertThat(session.next()).containsEntry("stop", 2).containsEntry("stopsLeft", 2);
    }

    @Test
    void timedOutStopsAreRetriedAfterTheOthers() {
        RouteSession session = session(null);
        session.setRoute(route(0, 100, 200));
        session.setStopTimeoutMillis(10_000);
        session.start();

        saved.add(ChunkCoverage.key(0, 0));
        session.tick();
        assertThat(session.getCurrentStop().chunkX).isEqualTo(100);

        // wait at stop 2 for longer than the timeout without its chunk arriving
        position = at(session.getCurrentStop());
        session.tick();
        now += 10_001;
        session.tick();
        assertThat(session.getRoute().stops.get(1).status).isEqualTo(RouteStop.Status.RETRY);
        assertThat(session.getCurrentStop().chunkX).isEqualTo(200);
        assertThat(session.getMessage()).contains("retry");

        saved.add(ChunkCoverage.key(200, 0));
        session.tick();
        // the retry is next, as the only open stop
        assertThat(session.getState()).isEqualTo(RouteSession.State.RUNNING);
        assertThat(session.getCurrentStop().chunkX).isEqualTo(100);
        assertThat(session.getCurrentStop().status).isEqualTo(RouteStop.Status.RETRY);

        // a second timeout gives up on it
        position = at(session.getCurrentStop());
        session.tick();
        now += 10_001;
        session.tick();
        assertThat(session.getState()).isEqualTo(RouteSession.State.DONE);
        assertThat(session.getRoute().count(RouteStop.Status.SKIPPED)).isEqualTo(1);
        assertThat(session.getRoute().count(RouteStop.Status.DONE)).isEqualTo(2);
        assertThat(session.next()).containsEntry("state", "DONE").containsEntry("stop", 0);
    }

    @Test
    void leavingAStopResetsItsTimeout() {
        RouteSession session = session(null);
        session.setRoute(route(0, 100));
        session.setStopTimeoutMillis(10_000);
        session.start();

        position = at(session.getCurrentStop());
        session.tick();
        now += 8000;
        session.tick();
        position = new double[]{5000, 5000};
        session.tick();
        position = at(session.getCurrentStop());
        now += 8000;
        session.tick();
        assertThat(session.getCurrentStop().status).isEqualTo(RouteStop.Status.PENDING);
        assertThat(session.getCurrentIndex()).isEqualTo(0);
    }

    @Test
    void skipPauseAndClear() {
        RouteSession session = session(null);
        session.setRoute(route(0, 100));
        session.start();

        assertThat(session.skip()).isTrue();
        assertThat(session.getRoute().stops.get(0).status).isEqualTo(RouteStop.Status.SKIPPED);
        assertThat(session.getCurrentStop().chunkX).isEqualTo(100);

        session.pause();
        assertThat(session.getState()).isEqualTo(RouteSession.State.PAUSED);
        assertThat(session.summary()).isEqualTo("route 1/2 (paused)");
        saved.add(ChunkCoverage.key(100, 0));
        session.tick();
        // nothing moves while paused
        assertThat(session.getCurrentStop().status).isEqualTo(RouteStop.Status.PENDING);

        session.start();
        session.tick();
        assertThat(session.getState()).isEqualTo(RouteSession.State.DONE);

        session.clear();
        assertThat(session.getRoute()).isNull();
        assertThat(session.getState()).isEqualTo(RouteSession.State.IDLE);
        assertThat(session.summary()).isEmpty();
    }

    @Test
    void chunkBudgetTellsTheAddonToHold() {
        RouteSession session = session(null);
        session.setRoute(route(0));
        session.setChunkBudgetPerMinute(600);
        session.start();

        chunksPerSecond = 5;
        session.tick();
        assertThat(session.isHolding()).isFalse();

        chunksPerSecond = 20;
        session.tick();
        assertThat(session.isHolding()).isTrue();
        assertThat(session.next()).containsEntry("hold", true);
        assertThat(session.status()).containsEntry("chunksPerMinute", 1200L).containsEntry("chunkBudgetPerMinute", 600);

        session.pause();
        session.tick();
        assertThat(session.isHolding()).isFalse();
    }

    @Test
    void speedAndEtaComeFromMeasuredMovement() {
        RouteSession session = session(null);
        session.setRoute(route(0, 1000));
        session.start();
        assertThat(session.etaSeconds()).isEqualTo(-1);

        // fly 10 blocks per second for 10 seconds
        for (int i = 0; i <= 10; i++) {
            position = new double[]{i * 10, 0};
            session.tick();
            now += 1000;
        }
        assertThat(session.blocksPerSecond()).isCloseTo(10, org.assertj.core.data.Offset.offset(0.5));
        // 16,000 blocks to the far stop (minus what's flown), about 1,600 s plus the default hold per stop
        assertThat(session.etaSeconds()).isBetween(1500L, 1700L);
        assertThat(session.summary()).startsWith("route 0/2 · ").endsWith(" left");
    }

    @Test
    void progressIsSavedAndLoaded(@TempDir Path dir) {
        Path file = dir.resolve("route.json");
        RouteSession session = session(file);
        session.setRoute(route(0, 100, 200));
        session.setChunkBudgetPerMinute(300);
        session.start();
        saved.add(ChunkCoverage.key(0, 0));
        session.tick();
        session.save();
        assertThat(Files.exists(file)).isTrue();

        RouteSession loaded = session(file);
        assertThat(loaded.getRoute()).isNotNull();
        assertThat(loaded.getRoute().stops).hasSize(3);
        assertThat(loaded.getRoute().stops.get(0).status).isEqualTo(RouteStop.Status.DONE);
        assertThat(loaded.getRoute().stops.get(0).targets).containsExactly(ChunkCoverage.key(0, 0));
        // a running route is never resumed on its own
        assertThat(loaded.getState()).isEqualTo(RouteSession.State.PAUSED);
        assertThat(loaded.getCurrentStop().chunkX).isEqualTo(100);
        assertThat(loaded.getChunkBudgetPerMinute()).isEqualTo(300);

        loaded.clear();
        assertThat(Files.exists(file)).isFalse();
    }

    @Test
    void replanOrdersOpenStopsFromThePlayer() {
        RouteSession session = session(null);
        session.setRoute(route(0, 100, 200));
        session.start();
        saved.add(ChunkCoverage.key(0, 0));
        session.tick();

        position = new double[]{200 * 16 + 8, 8};
        assertThat(session.replan()).isTrue();
        List<Integer> order = session.getRoute().stops.stream().map(stop -> stop.chunkX).toList();
        assertThat(order).containsExactly(0, 200, 100);
        assertThat(session.getCurrentStop().chunkX).isEqualTo(200);

        Map<String, Object> status = session.status();
        assertThat(status).containsEntry("done", 1).containsEntry("pending", 2).containsEntry("current", 2);
    }

    @Test
    void formatEta() {
        assertThat(RouteSession.formatEta(45)).isEqualTo("45 s");
        assertThat(RouteSession.formatEta(150)).isEqualTo("3 min");
        assertThat(RouteSession.formatEta(5400)).isEqualTo("1.5 h");
    }
}
