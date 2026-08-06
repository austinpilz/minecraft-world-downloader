package proxy.socks;

/**
 * Settings for routing the outbound connection to the Minecraft server through a SOCKS5 proxy.
 * Only affects the connection this application makes to the destination server, not the local
 * connection the Minecraft client makes to this application.
 */
public class SocksProxyConfig {
    private final boolean enabled;
    private final String host;
    private final int port;
    private final String username;
    private final String password;

    public SocksProxyConfig(boolean enabled, String host, int port, String username, String password) {
        this.enabled = enabled;
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public boolean hasCredentials() {
        return username != null && !username.isEmpty();
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }
}
