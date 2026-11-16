package game.data.route;

import com.google.gson.Gson;
import fi.iki.elonen.NanoHTTPD;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Small JSON API on localhost for the flight addon. Off unless {@code --route-api-port} is given. Only binds to
 * 127.0.0.1, nothing is ever sent towards the game server.
 * <pre>
 * GET  /route/next?player=NAME   where to fly next, and whether to hold there
 * GET  /route/status             progress of the whole route
 * POST /route/plan?radius=8&buffer=0&skipCaptured=true   plan over the shown overlays
 * POST /route/start | /route/pause | /route/resume | /route/skip | /route/replan | /route/clear
 * </pre>
 * The player name is accepted so the API stays the same once several bots share a proxy; today there is one route.
 */
public class RouteApiServer extends NanoHTTPD {
    public static final String HOST = "127.0.0.1";
    private static final String MIME_JSON = "application/json";
    private static final long API_OPTIMISE_MILLIS = 10_000;

    /** plans a route; separate so the server can be tested without the world manager */
    public interface Planner {
        RoutePlanner.Result plan(int radius, int buffer, boolean skipCaptured);
    }

    private final RouteSession session;
    private final Planner planner;
    private final Gson gson = new Gson();

    public RouteApiServer(int port, RouteSession session, Planner planner) {
        super(HOST, port);
        this.session = session;
        this.planner = planner;
    }

    /**
     * Start serving. Logs the address, or the reason it couldn't start.
     */
    public static RouteApiServer start(int port, RouteSession session, Planner planner) {
        RouteApiServer server = new RouteApiServer(port, session, planner);
        try {
            server.start();
            System.out.println("Route API listening on http://" + HOST + ":" + server.getListeningPort() + "/route/status");
            return server;
        } catch (IOException ex) {
            System.out.println("Could not start the route API on port " + port + ": " + ex.getMessage());
            return null;
        }
    }

    @Override
    public Response serve(IHTTPSession http) {
        String uri = http.getUri();
        Method method = http.getMethod();
        Map<String, String> params = http.getParms();
        try {
            if (method == Method.GET) {
                return switch (uri) {
                    case "/route/next" -> json(Response.Status.OK, withPlayer(session.next(), params));
                    case "/route/status" -> json(Response.Status.OK, session.status());
                    default -> error(Response.Status.NOT_FOUND, "unknown path " + uri);
                };
            }
            if (method == Method.POST) {
                return switch (uri) {
                    case "/route/plan" -> plan(params);
                    case "/route/start", "/route/resume" -> session.start()
                        ? json(Response.Status.OK, session.status())
                        : error(Response.Status.CONFLICT, session.getMessage().isEmpty() ? "nothing to start" : session.getMessage());
                    case "/route/pause" -> {
                        session.pause();
                        yield json(Response.Status.OK, session.status());
                    }
                    case "/route/skip" -> session.skip()
                        ? json(Response.Status.OK, session.next())
                        : error(Response.Status.CONFLICT, "no current stop");
                    case "/route/replan" -> session.replan()
                        ? json(Response.Status.OK, session.status())
                        : error(Response.Status.CONFLICT, "no route");
                    case "/route/clear" -> {
                        session.clear();
                        yield json(Response.Status.OK, session.status());
                    }
                    default -> error(Response.Status.NOT_FOUND, "unknown path " + uri);
                };
            }
            return error(Response.Status.METHOD_NOT_ALLOWED, "use GET or POST");
        } catch (Exception ex) {
            return error(Response.Status.INTERNAL_ERROR, ex.getMessage() == null ? ex.toString() : ex.getMessage());
        }
    }

    private Response plan(Map<String, String> params) {
        if (planner == null) {
            return error(Response.Status.NOT_IMPLEMENTED, "planning is not available");
        }
        int radius = intParam(params, "radius", 0);
        int buffer = intParam(params, "buffer", 0);
        boolean skipCaptured = !"false".equalsIgnoreCase(params.getOrDefault("skipCaptured", "true"));
        try {
            RoutePlanner.Result result = planner.plan(radius, buffer, skipCaptured);
            Map<String, Object> res = new LinkedHashMap<>();
            res.put("stops", result.stops().size());
            res.put("lengthBlocks", Math.round(result.lengthBlocks()));
            res.put("targetChunks", result.targetChunks());
            res.put("radius", result.radius());
            res.put("status", session.status());
            return json(Response.Status.OK, res);
        } catch (IllegalStateException | IllegalArgumentException ex) {
            return error(Response.Status.CONFLICT, ex.getMessage());
        }
    }

    private static int intParam(Map<String, String> params, String name, int fallback) {
        String value = params.get(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(name + " must be a number");
        }
    }

    private static Map<String, Object> withPlayer(Map<String, Object> body, Map<String, String> params) {
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("player", params.getOrDefault("player", ""));
        res.putAll(body);
        return res;
    }

    private Response json(Response.Status status, Object body) {
        Response response = newFixedLengthResponse(status, MIME_JSON, gson.toJson(body));
        response.addHeader("Cache-Control", "no-store");
        return response;
    }

    private Response error(Response.Status status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        return json(status, body);
    }
}
