package proxy.accounts;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A minimal SOCKS5 server with username/password authentication, for tests. It records who logged in and where
 * they asked to connect.
 */
class TestSocks5Server implements Closeable {
    private final ServerSocket server;
    private final String username;
    private final String password;
    final List<String> targets = new CopyOnWriteArrayList<>();
    final List<String> loggedInUsers = new CopyOnWriteArrayList<>();
    final List<String> rejectedUsers = new CopyOnWriteArrayList<>();

    TestSocks5Server(String username, String password) throws IOException {
        this.username = username;
        this.password = password;
        this.server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());

        Thread acceptor = new Thread(this::acceptLoop, "test-socks-accept");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    int port() {
        return server.getLocalPort();
    }

    private void acceptLoop() {
        while (!server.isClosed()) {
            try {
                Socket client = server.accept();
                Thread t = new Thread(() -> handle(client), "test-socks-client");
                t.setDaemon(true);
                t.start();
            } catch (IOException ex) {
                return;
            }
        }
    }

    private void handle(Socket client) {
        try (client) {
            DataInputStream in = new DataInputStream(client.getInputStream());
            OutputStream out = client.getOutputStream();

            in.readByte(); // version
            int methods = in.readUnsignedByte();
            in.readNBytes(methods);
            out.write(new byte[] {5, 2});

            in.readByte(); // auth version
            String user = new String(in.readNBytes(in.readUnsignedByte()), StandardCharsets.UTF_8);
            String pass = new String(in.readNBytes(in.readUnsignedByte()), StandardCharsets.UTF_8);
            if (!user.equals(username) || !pass.equals(password)) {
                rejectedUsers.add(user);
                out.write(new byte[] {1, 1});
                return;
            }
            loggedInUsers.add(user);
            out.write(new byte[] {1, 0});

            in.readNBytes(3); // version, command, reserved
            int type = in.readUnsignedByte();
            String host;
            if (type == 1) {
                host = InetAddress.getByAddress(in.readNBytes(4)).getHostAddress();
            } else if (type == 3) {
                host = new String(in.readNBytes(in.readUnsignedByte()), StandardCharsets.UTF_8);
            } else {
                return;
            }
            int port = in.readUnsignedShort();
            targets.add(host + ":" + port);

            Socket target = new Socket(host, port);
            out.write(new byte[] {5, 0, 0, 1, 0, 0, 0, 0, 0, 0});

            Thread back = new Thread(() -> pipe(target, client), "test-socks-pipe");
            back.setDaemon(true);
            back.start();
            pipe(client, target);
        } catch (IOException ex) {
            // the test closed a connection
        }
    }

    private static void pipe(Socket from, Socket to) {
        try {
            InputStream in = from.getInputStream();
            OutputStream out = to.getOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                out.flush();
            }
        } catch (IOException ex) {
            // closed
        } finally {
            try { to.close(); } catch (IOException ignored) { }
        }
    }

    @Override
    public void close() throws IOException {
        server.close();
    }
}
