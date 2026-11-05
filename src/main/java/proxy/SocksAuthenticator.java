package proxy;

import java.net.Authenticator;
import java.net.PasswordAuthentication;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The JDK's SOCKS implementation only asks for username/password authentication through the default
 * Authenticator, there's no way to hand it credentials directly. Bots can each go through a different SOCKS proxy
 * with its own credentials, so this one Authenticator answers with the credentials registered for whichever proxy
 * is asking.
 *
 * Nothing else in this application uses Authenticator, and the callback below answers nothing but SOCKS requests,
 * so installing it globally won't interfere with the Mojang/Microsoft requests made through Unirest.
 *
 * The requesting protocol is the only thing worth matching on besides the host. SocksSocketImpl asks through the
 * Authenticator.requestPasswordAuthentication overload that reports the type as SERVER rather than PROXY, so
 * filtering on the requestor type would reject every SOCKS request instead.
 */
public final class SocksAuthenticator extends Authenticator {
    private static final Map<String, PasswordAuthentication> credentials = new ConcurrentHashMap<>();
    private static boolean installed = false;

    private SocksAuthenticator() { }

    /**
     * Register the credentials for a proxy. The host must be given the way the proxy's address will report it: the
     * host name, or the IP if the address was created from an IP only.
     */
    public static synchronized void register(String host, int port, String username, String password) {
        credentials.put(key(host, port), new PasswordAuthentication(username, password.toCharArray()));

        if (!installed) {
            Authenticator.setDefault(new SocksAuthenticator());
            installed = true;
        }
    }

    private static String key(String host, int port) {
        return host.toLowerCase(Locale.ROOT) + ":" + port;
    }

    static PasswordAuthentication lookup(String host, int port) {
        if (host == null) {
            return null;
        }
        return credentials.get(key(host, port));
    }

    @Override
    protected PasswordAuthentication getPasswordAuthentication() {
        if (!"SOCKS5".equalsIgnoreCase(getRequestingProtocol())) {
            return null;
        }
        return lookup(getRequestingHost(), getRequestingPort());
    }
}
