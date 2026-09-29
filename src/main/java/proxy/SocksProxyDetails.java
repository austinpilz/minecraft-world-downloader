package proxy;

import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;

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

    public SocksProxyDetails(String host, int port, String username, String password) {
        this.host = host;
        this.port = port > 0 ? port : DEFAULT_PORT;
        this.username = username == null ? "" : username;
        this.password = password == null ? "" : password;
    }

    public String getFriendlyHost() {
        return host + ":" + port;
    }

    public boolean hasCredentials() {
        return !username.isEmpty();
    }

    public Proxy toProxy() {
        return new Proxy(Proxy.Type.SOCKS, new InetSocketAddress(host, port));
    }

    /**
     * The JDK's SOCKS implementation only asks for username/password authentication through the default
     * Authenticator, there's no way to hand it credentials directly. Nothing else in this application uses
     * Authenticator, and the callback below answers nothing but SOCKS requests, so installing it globally
     * won't interfere with the Mojang/Microsoft requests made through Unirest.
     *
     * The requesting protocol is the only thing worth matching on. SocksSocketImpl asks through the
     * Authenticator.requestPasswordAuthentication overload that reports the type as SERVER rather than
     * PROXY, so filtering on the requestor type would reject every SOCKS request instead.
     */
    public void installAuthenticator() {
        if (!hasCredentials()) {
            return;
        }

        Authenticator.setDefault(new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                if (!"SOCKS5".equalsIgnoreCase(getRequestingProtocol())) {
                    return null;
                }
                return new PasswordAuthentication(username, password.toCharArray());
            }
        });
    }
}
