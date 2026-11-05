package proxy.accounts;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A tiny HTTP server for tests: GET returns a fixed body, POST bodies are recorded and answered with a fixed status.
 */
class TestHttpServer implements AutoCloseable {
    private final HttpServer server;
    final List<String> postBodies = new CopyOnWriteArrayList<>();

    TestHttpServer(String getBody, int postStatus) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            if (exchange.getRequestMethod().equals("POST")) {
                postBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                exchange.sendResponseHeaders(postStatus, -1);
            } else {
                byte[] body = getBody.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
    }

    int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
