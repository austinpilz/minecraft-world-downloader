package proxy;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.UnknownHostException;

/**
 * Details of the SOCKS5 proxy that the connection to the remote server is routed through, if the user
 * configured one. The outbound game connection uses this, as does the Mojang session join (so both come from
 * the same IP). Other Mojang/Microsoft HTTP calls are unaffected and still go out directly.
 */
public class SocksProxyDetails {
    private static final int DEFAULT_PORT = 1080;

    private final String host;
    private final int port;
    private final String username;
    private final String password;

    // set once the host has been looked up, see pin()
    private final InetAddress pinnedAddress;

    public SocksProxyDetails(String host, int port, String username, String password) {
        this(host, port, username, password, null);
    }

    private SocksProxyDetails(String host, int port, String username, String password, InetAddress pinnedAddress) {
        this.host = host;
        this.port = port > 0 ? port : DEFAULT_PORT;
        this.username = username == null ? "" : username;
        this.password = password == null ? "" : password;
        this.pinnedAddress = pinnedAddress;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public String getFriendlyHost() {
        return host + ":" + port;
    }

    public boolean hasCredentials() {
        return !username.isEmpty();
    }

    /**
     * Look the proxy's host up once and keep using that IP. A bot's session join and its game connection then
     * always go out through the same server, even if the host name resolves to several.
     */
    public SocksProxyDetails pin() throws UnknownHostException {
        InetAddress resolved = InetAddress.getByName(host);

        // an address without a host name, otherwise the JDK looks the name up again when it connects
        InetAddress ipOnly = InetAddress.getByAddress(resolved.getAddress());

        SocksProxyDetails pinned = new SocksProxyDetails(host, port, username, password, ipOnly);
        pinned.installAuthenticator();
        return pinned;
    }

    /**
     * The IP this proxy was pinned to, or null if it wasn't.
     */
    public String getPinnedIp() {
        return pinnedAddress == null ? null : pinnedAddress.getHostAddress();
    }

    public Proxy toProxy() {
        InetSocketAddress address = pinnedAddress == null
            ? new InetSocketAddress(host, port)
            : new InetSocketAddress(pinnedAddress, port);
        return new Proxy(Proxy.Type.SOCKS, address);
    }

    /**
     * Register this proxy's credentials with the SOCKS Authenticator, see {@link SocksAuthenticator}.
     */
    public void installAuthenticator() {
        if (!hasCredentials()) {
            return;
        }

        SocksAuthenticator.register(host, port, username, password);
        if (pinnedAddress != null) {
            SocksAuthenticator.register(pinnedAddress.getHostAddress(), port, username, password);
        }
    }
}
