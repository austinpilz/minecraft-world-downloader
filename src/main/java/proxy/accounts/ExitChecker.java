package proxy.accounts;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;
import proxy.SocksProxyDetails;

/**
 * Finds the IP a SOCKS proxy sends traffic out from, by asking an IP echo service through it. This is the IP the
 * Minecraft server will see, so it doubles as a check that the proxy works at all.
 */
public class ExitChecker {
    public static final List<String> DEFAULT_ECHO_URLS = List.of(
        "https://api.ipify.org",
        "https://checkip.amazonaws.com"
    );

    private static final int TIMEOUT_MS = 10_000;
    private static final Pattern IP = Pattern.compile("^[0-9.]+$|^[0-9a-fA-F:]+$");

    // the exit IP reported for bots that don't go through a proxy
    public static final String DIRECT = "direct";

    private final List<String> echoUrls;

    public ExitChecker() {
        this(DEFAULT_ECHO_URLS);
    }

    public ExitChecker(List<String> echoUrls) {
        this.echoUrls = echoUrls;
    }

    public record Result(String ip, long latencyMs, String error) {
        public boolean ok() {
            return error == null;
        }
    }

    /**
     * Get the exit IP of the given proxy. A null proxy means connecting directly, which isn't checked.
     */
    public Result check(SocksProxyDetails exit) {
        if (exit == null) {
            return new Result(DIRECT, 0, null);
        }

        String error = "no IP echo service configured";
        for (String url : echoUrls) {
            long start = System.nanoTime();
            try {
                String ip = fetch(url, exit.toProxy());
                long latency = (System.nanoTime() - start) / 1_000_000;

                if (!IP.matcher(ip).matches()) {
                    error = url + " did not reply with an IP";
                    continue;
                }
                return new Result(ip, latency, null);
            } catch (IOException ex) {
                error = describe(ex);
            }
        }
        return new Result(null, 0, error);
    }

    private static String fetch(String url, Proxy proxy) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection(proxy);
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        conn.setRequestProperty("User-Agent", "minecraft-world-downloader");
        try {
            int status = conn.getResponseCode();
            if (status != 200) {
                throw new IOException(url + " replied with status " + status);
            }
            try (InputStream in = conn.getInputStream()) {
                return new String(in.readNBytes(256), StandardCharsets.US_ASCII).strip();
            }
        } finally {
            conn.disconnect();
        }
    }

    private static String describe(IOException ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return ex.getClass().getSimpleName();
        }
        return message;
    }
}
