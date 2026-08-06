package proxy.socks;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Minimal SOCKS5 client (RFC 1928, username/password auth per RFC 1929) used to route the
 * outbound connection to the Minecraft server through a SOCKS5 proxy. Implemented by hand
 * rather than relying on java.net.Proxy/Authenticator, since those only support username/password
 * auth via global JVM system properties rather than per-connection credentials.
 */
public class Socks5Client {
    private static final int VERSION = 0x05;

    private static final int METHOD_NO_AUTH = 0x00;
    private static final int METHOD_USER_PASS = 0x02;
    private static final int METHOD_NO_ACCEPTABLE = 0xFF;

    private static final int CMD_CONNECT = 0x01;
    private static final int ATYP_DOMAINNAME = 0x03;
    private static final int ATYP_IPV4 = 0x01;
    private static final int ATYP_IPV6 = 0x04;

    private static final int AUTH_VERSION = 0x01;
    private static final int AUTH_SUCCESS = 0x00;
    private static final int REPLY_SUCCEEDED = 0x00;

    private Socks5Client() {
    }

    /**
     * Opens a socket to the SOCKS5 proxy and negotiates a CONNECT tunnel to the given destination.
     * The returned socket, once connected, behaves like a plain socket to the destination.
     */
    public static Socket connect(SocksProxyConfig proxyConfig, String destHost, int destPort, int connectTimeoutMs) throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(proxyConfig.getHost(), proxyConfig.getPort()), connectTimeoutMs);
            negotiate(socket, proxyConfig);
            requestConnect(socket, destHost, destPort);
            return socket;
        } catch (IOException ex) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            throw ex;
        }
    }

    private static void negotiate(Socket socket, SocksProxyConfig proxyConfig) throws IOException {
        OutputStream out = socket.getOutputStream();
        InputStream in = socket.getInputStream();

        boolean offerUserPass = proxyConfig.hasCredentials();
        int[] methods = offerUserPass
            ? new int[] {METHOD_USER_PASS, METHOD_NO_AUTH}
            : new int[] {METHOD_NO_AUTH};

        byte[] greeting = new byte[2 + methods.length];
        greeting[0] = (byte) VERSION;
        greeting[1] = (byte) methods.length;
        for (int i = 0; i < methods.length; i++) {
            greeting[2 + i] = (byte) methods[i];
        }
        out.write(greeting);
        out.flush();

        int replyVersion = readByte(in);
        int chosenMethod = readByte(in);
        if (replyVersion != VERSION) {
            throw new IOException("Unexpected SOCKS version in method reply: " + replyVersion);
        }
        if (chosenMethod == METHOD_NO_ACCEPTABLE) {
            throw new IOException("SOCKS5 proxy did not accept any of the offered authentication methods.");
        }

        if (chosenMethod == METHOD_USER_PASS) {
            authenticateUserPass(socket, proxyConfig);
        } else if (chosenMethod != METHOD_NO_AUTH) {
            throw new IOException("SOCKS5 proxy selected an unsupported authentication method: " + chosenMethod);
        }
    }

    private static void authenticateUserPass(Socket socket, SocksProxyConfig proxyConfig) throws IOException {
        OutputStream out = socket.getOutputStream();
        InputStream in = socket.getInputStream();

        byte[] username = proxyConfig.getUsername().getBytes(StandardCharsets.UTF_8);
        byte[] password = proxyConfig.getPassword() == null
            ? new byte[0]
            : proxyConfig.getPassword().getBytes(StandardCharsets.UTF_8);

        if (username.length > 255 || password.length > 255) {
            throw new IOException("SOCKS5 username/password must each be at most 255 bytes.");
        }

        byte[] request = new byte[3 + username.length + password.length];
        int i = 0;
        request[i++] = (byte) AUTH_VERSION;
        request[i++] = (byte) username.length;
        System.arraycopy(username, 0, request, i, username.length);
        i += username.length;
        request[i++] = (byte) password.length;
        System.arraycopy(password, 0, request, i, password.length);

        out.write(request);
        out.flush();

        readByte(in); // auth subnegotiation version, not validated further
        int status = readByte(in);
        if (status != AUTH_SUCCESS) {
            throw new IOException("SOCKS5 proxy rejected the provided username/password.");
        }
    }

    private static void requestConnect(Socket socket, String destHost, int destPort) throws IOException {
        OutputStream out = socket.getOutputStream();
        InputStream in = socket.getInputStream();

        byte[] hostBytes = destHost.getBytes(StandardCharsets.UTF_8);
        if (hostBytes.length > 255) {
            throw new IOException("Destination hostname is too long for SOCKS5 (max 255 bytes).");
        }

        byte[] request = new byte[7 + hostBytes.length];
        int i = 0;
        request[i++] = (byte) VERSION;
        request[i++] = (byte) CMD_CONNECT;
        request[i++] = 0x00; // reserved
        request[i++] = (byte) ATYP_DOMAINNAME;
        request[i++] = (byte) hostBytes.length;
        System.arraycopy(hostBytes, 0, request, i, hostBytes.length);
        i += hostBytes.length;
        request[i++] = (byte) ((destPort >> 8) & 0xFF);
        request[i] = (byte) (destPort & 0xFF);

        out.write(request);
        out.flush();

        int version = readByte(in);
        int reply = readByte(in);
        readByte(in); // reserved
        int addressType = readByte(in);

        if (version != VERSION) {
            throw new IOException("Unexpected SOCKS version in connect reply: " + version);
        }
        if (reply != REPLY_SUCCEEDED) {
            throw new IOException("SOCKS5 proxy refused the connection: " + describeReply(reply));
        }

        // consume the bound address/port so the stream is left positioned at the start of the tunnel
        switch (addressType) {
            case ATYP_IPV4 -> skip(in, 4 + 2);
            case ATYP_IPV6 -> skip(in, 16 + 2);
            case ATYP_DOMAINNAME -> {
                int len = readByte(in);
                skip(in, len + 2);
            }
            default -> throw new IOException("Unknown SOCKS5 address type in connect reply: " + addressType);
        }
    }

    private static int readByte(InputStream in) throws IOException {
        int value = in.read();
        if (value < 0) {
            throw new IOException("SOCKS5 proxy closed the connection unexpectedly.");
        }
        return value;
    }

    private static void skip(InputStream in, int count) throws IOException {
        for (int i = 0; i < count; i++) {
            readByte(in);
        }
    }

    private static String describeReply(int reply) {
        return switch (reply) {
            case 0x01 -> "general SOCKS server failure";
            case 0x02 -> "connection not allowed by ruleset";
            case 0x03 -> "network unreachable";
            case 0x04 -> "host unreachable";
            case 0x05 -> "connection refused";
            case 0x06 -> "TTL expired";
            case 0x07 -> "command not supported";
            case 0x08 -> "address type not supported";
            default -> "unknown error code " + reply;
        };
    }
}
