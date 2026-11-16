package game.data.route;

import gui.overlay.ChunkCoverage;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RouteApiServerTest {
    private RouteSession session;
    private RouteApiServer server;
    private final HttpClient client = HttpClient.newHttpClient();
    private int planned;

    @BeforeEach
    void start() throws IOException {
        session = new RouteSession(chunk -> false, () -> null, () -> 0, () -> null);
        server = new RouteApiServer(0, session, (radius, buffer, skipCaptured) -> {
            planned++;
            List<RouteStop> stops = List.of(new RouteStop(1, 2, new long[]{ChunkCoverage.key(1, 2)}));
            session.setRoute(new Route("minecraft:overworld", radius, 0, 0, "api", 1, stops));
            return new RoutePlanner.Result(stops, 100, 1, radius);
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    private HttpResponse<String> call(String method, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getListeningPort() + path))
            .method(method, HttpRequest.BodyPublishers.noBody()).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void statusAndNextWithoutRoute() throws Exception {
        HttpResponse<String> status = call("GET", "/route/status");
        assertThat(status.statusCode()).isEqualTo(200);
        assertThat(status.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");
        assertThat(status.body()).contains("\"state\":\"IDLE\"").contains("\"stops\":0");

        HttpResponse<String> next = call("GET", "/route/next?player=Bob");
        assertThat(next.body()).contains("\"player\":\"Bob\"").contains("\"stop\":0");

        assertThat(call("POST", "/route/start").statusCode()).isEqualTo(409);
        assertThat(call("GET", "/route/nope").statusCode()).isEqualTo(404);
        assertThat(call("PUT", "/route/status").statusCode()).isEqualTo(405);
    }

    @Test
    void planThenDriveTheRoute() throws Exception {
        HttpResponse<String> plan = call("POST", "/route/plan?radius=6&buffer=1");
        assertThat(plan.statusCode()).isEqualTo(200);
        assertThat(plan.body()).contains("\"stops\":1").contains("\"radius\":6");
        assertThat(planned).isEqualTo(1);

        assertThat(call("POST", "/route/start").statusCode()).isEqualTo(200);
        HttpResponse<String> next = call("GET", "/route/next?player=Bob");
        assertThat(next.body()).contains("\"state\":\"RUNNING\"").contains("\"x\":24.0").contains("\"z\":40.0")
            .contains("\"missing\":1").contains("\"radius\":6");

        assertThat(call("POST", "/route/pause").body()).contains("\"state\":\"PAUSED\"");
        assertThat(call("POST", "/route/resume").body()).contains("\"state\":\"RUNNING\"");
        assertThat(call("POST", "/route/skip").body()).contains("\"state\":\"DONE\"");
        assertThat(call("POST", "/route/clear").body()).contains("\"state\":\"IDLE\"");

        assertThat(call("POST", "/route/plan?radius=x").statusCode()).isEqualTo(500);
    }
}
