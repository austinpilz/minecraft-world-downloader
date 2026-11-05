package proxy.accounts;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import proxy.SocksProxyDetails;

/**
 * The pasted list of SOCKS5 proxies bots can be sent through, e.g. NordVPN's. One proxy per line, as host,
 * host:port or user:pass@host:port, optionally starting with socks5://. Blank lines and lines starting with # are
 * skipped. Lines without credentials use the shared username and password, since Nord uses the same service
 * credentials for every server.
 */
public final class SocksPool {
    private static final int DEFAULT_PORT = 1080;

    private SocksPool() { }

    public record Entry(String host, int port, String username, String password) {
        /**
         * The name a bot's exit is saved under.
         */
        public String key() {
            return host.toLowerCase(Locale.ROOT) + ":" + port;
        }

        public SocksProxyDetails toDetails() {
            return new SocksProxyDetails(host, port, username, password);
        }

        @Override
        public String toString() {
            // never include the password, this ends up in logs and the GUI
            return key();
        }
    }

    public record ParseResult(List<Entry> entries, List<String> errors) { }

    public static ParseResult parse(String text, String sharedUsername, String sharedPassword) {
        List<Entry> entries = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        if (text == null) {
            return new ParseResult(entries, errors);
        }

        String[] lines = text.split("\\R");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }

            try {
                Entry entry = parseLine(line, sharedUsername, sharedPassword);
                if (!seen.add(entry.key())) {
                    errors.add("Line " + (i + 1) + ": " + entry.key() + " is listed twice");
                    continue;
                }
                entries.add(entry);
            } catch (IllegalArgumentException ex) {
                errors.add("Line " + (i + 1) + ": " + ex.getMessage());
            }
        }
        return new ParseResult(entries, errors);
    }

    private static Entry parseLine(String line, String sharedUsername, String sharedPassword) {
        String rest = line.replaceFirst("(?i)^socks5h?://", "");

        String username = sharedUsername == null ? "" : sharedUsername;
        String password = sharedPassword == null ? "" : sharedPassword;

        // the password may contain an @, the host can't
        int at = rest.lastIndexOf('@');
        if (at >= 0) {
            String credentials = rest.substring(0, at);
            rest = rest.substring(at + 1);

            int colon = credentials.indexOf(':');
            if (colon < 0) {
                throw new IllegalArgumentException("credentials must be given as user:pass@host");
            }
            username = credentials.substring(0, colon);
            password = credentials.substring(colon + 1);
        }

        String host = rest;
        int port = DEFAULT_PORT;

        if (rest.startsWith("[")) {
            int end = rest.indexOf(']');
            if (end < 0) {
                throw new IllegalArgumentException("missing ] after IPv6 address");
            }
            host = rest.substring(1, end);
            String after = rest.substring(end + 1);
            if (after.startsWith(":")) {
                port = parsePort(after.substring(1));
            } else if (!after.isEmpty()) {
                throw new IllegalArgumentException("unexpected text after IPv6 address");
            }
        } else if (rest.indexOf(':') != rest.lastIndexOf(':')) {
            throw new IllegalArgumentException("IPv6 addresses must be written in brackets, like [::1]:1080");
        } else if (rest.contains(":")) {
            host = rest.substring(0, rest.indexOf(':'));
            port = parsePort(rest.substring(rest.indexOf(':') + 1));
        }

        if (host.isEmpty() || host.contains("/") || host.contains(" ")) {
            throw new IllegalArgumentException("not a host name: " + host);
        }
        return new Entry(host, port, username, password);
    }

    private static int parsePort(String port) {
        try {
            int value = Integer.parseInt(port.strip());
            if (value < 1 || value > 65535) {
                throw new IllegalArgumentException("port out of range: " + port);
            }
            return value;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("not a port number: " + port);
        }
    }
}
