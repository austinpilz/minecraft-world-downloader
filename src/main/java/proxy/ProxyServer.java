package proxy;

import config.Config;
import game.NetworkMode;
import game.data.PlayerSession;
import packets.DataReader;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static util.ExceptionHandling.attempt;

/**
 * Proxy server class, handles receiving of data and forwarding it to the right places.
 */
public class ProxyServer extends Thread {
    private final ConnectionDetails connectionDetails;
    private final ConnectionManager connectionManager;

    private DataReader onServerBoundPacket;
    private DataReader onClientBoundPacket;
    private final AtomicInteger fleetClients = new AtomicInteger();

    public ProxyServer(ConnectionManager connectionManager, ConnectionDetails connectionDetails) {
        this.connectionDetails = connectionDetails;
        this.connectionManager = connectionManager;
    }

    /**
     * Run the proxy server. This method does not return.
     * @param onServerBoundPacket data reader for client -> server traffic
     * @param onClientBoundPacket data reader for server -> client traffic
     */
    public void runServer(DataReader onServerBoundPacket, DataReader onClientBoundPacket) {
        this.onClientBoundPacket = onClientBoundPacket;
        this.onServerBoundPacket = onServerBoundPacket;
        this.start();
        this.setPriority(10);
    }

    @Override
    public void run() {
        setName("Proxy");
        
        String friendlyHost = connectionDetails.getFriendlyHost();
        System.out.println("Starting proxy for " + friendlyHost + ". Make sure to connect to localhost:" + connectionDetails.getPortLocal() + " instead of the regular server address.");

        SocksProxyDetails socksProxy = connectionDetails.getSocksProxy();
        String connectionFailureHint = socksProxy == null
            ? "The server may be down or on a different address."
            : "The server may be down, or the SOCKS proxy at " + socksProxy.getFriendlyHost() + " may be unreachable or rejecting the connection.";

        // Create a ServerSocket to listen for connections with
        AtomicReference<ServerSocket> ss = new AtomicReference<>();
        attempt(() -> ss.set(connectionDetails.getServerSocket()), (ex) -> {
            ex.printStackTrace();
            System.exit(1);
        });

        final byte[] request = new byte[4096];
        final byte[] reply = new byte[4096];

        while (true) {
            AtomicReference<Socket> client = new AtomicReference<>();
            AtomicReference<Socket> server = new AtomicReference<>();

            attempt(() -> {
                // Wait for a connection on the local port
                client.set(ss.get().accept());

                if (Config.isFleet()) {
                    runFleetClient(client.get());
                    return;
                }

                if (connectionManager.isProxyAccounts()) {
                    runBotSession(connectionManager, client.get(), onServerBoundPacket, onClientBoundPacket);
                    return;
                }

                final InputStream streamFromClient = client.get().getInputStream();
                final OutputStream streamToClient = client.get().getOutputStream();
                connectionManager.getEncryptionManager().setStreamToClient(streamToClient);

                // If the server cannot connect, close client connection
                attempt(() -> server.set(connectionDetails.getClientSocket()), (ex) -> {
                    System.err.println("Cannot connect to " + friendlyHost + ". " + connectionFailureHint + " (" + ex.getClass().getCanonicalName() + ")");
                    attempt(client.get()::close);
                });

                // the connection above failed and the client has been closed, so wait for a new connection
                // instead of carrying on with a server socket that was never set
                if (server.get() == null) {
                    return;
                }

                final InputStream streamFromServer = server.get().getInputStream();
                final OutputStream streamToServer = server.get().getOutputStream();
                connectionManager.getEncryptionManager().setStreamToServer(streamToServer);

                // start client listener thread
                Thread clientListener = new Thread(() -> {
                    connectionManager.setMode(NetworkMode.HANDSHAKE);
                    attempt(() -> {
                        int bytesRead;
                        while ((bytesRead = streamFromClient.read(request)) != -1) {
                            onServerBoundPacket.pushData(request, bytesRead);
                        }
                    }, (ex) -> {
                        Throwable cause = ex.getCause();
                        if (cause != null) {
                            cause.printStackTrace();
                        }
                        System.out.println("Server probably disconnected. Waiting for new connection...");
                        connectionManager.reset();
                    });
                    // the client closed the connection to us, so close our connection to the server.
                    attempt(streamToServer::close);
                }, "Proxy Client Listener");
                clientListener.start();
                clientListener.setPriority(10);

                // listen to messages from server
                attempt(() -> {
                    int bytesRead;
                    while ((bytesRead = streamFromServer.read(reply)) != -1) {
                        onClientBoundPacket.pushData(reply, bytesRead);
                    }
                }, (ex) -> {
                    Throwable cause = ex.getCause();
                    if (cause != null) {
                        cause.printStackTrace();
                    }
                    System.out.println("Client probably disconnected. Waiting for new connection...");
                    connectionManager.reset();
                });

                // The server closed its connection to us, so we close our connection to our client.
                streamToClient.close();
            }, (ex) -> {
                if (server.get() != null) { attempt(server.get()::close); }
                if (client.get() != null) { attempt(client.get()::close); }
            });
        }
    }

    /**
     * Handle a bot of the fleet on its own thread and connection, so the proxy can accept the next one right away.
     */
    private void runFleetClient(Socket client) {
        ConnectionManager fleetConnection = connectionManager.newFleetConnection();
        Thread thread = new Thread(() -> {
            attempt(() -> runBotSession(
                fleetConnection, client,
                fleetConnection.getServerBoundDataReader(), fleetConnection.getClientBoundDataReader()
            ), (ex) -> attempt(client::close));
            PlayerSession.bind(null);
        }, "Fleet Client " + fleetClients.incrementAndGet());
        thread.setPriority(10);
        thread.start();
    }

    /**
     * Handle one client with proxy-held accounts. The client connects offline, and the connection to the server is
     * only opened once the bot has logged in, since that decides which exit it goes through. In fleet mode, the
     * bot's session is bound to both threads reading its packets once it has logged in.
     */
    private void runBotSession(ConnectionManager connectionManager, Socket client,
                               DataReader onServerBoundPacket, DataReader onClientBoundPacket) throws java.io.IOException {
        final InputStream streamFromClient = client.getInputStream();
        final OutputStream streamToClient = client.getOutputStream();
        connectionManager.getEncryptionManager().setStreamToClient(streamToClient);

        AtomicReference<Socket> server = new AtomicReference<>();
        AtomicBoolean ended = new AtomicBoolean(false);

        // either side closing ends the session, and only the first to notice resets the connection: a late reset
        // from the old session's server thread would otherwise hit the next bot to connect
        Runnable end = () -> {
            if (!ended.compareAndSet(false, true)) {
                return;
            }
            attempt(client::close);
            Socket serverSocket = server.get();
            if (serverSocket != null) {
                attempt(serverSocket::close);
            }
            connectionManager.reset();
        };

        connectionManager.setServerConnector(new ConnectionManager.ServerConnector() {
            @Override
            public void connect(SocksProxyDetails exit) throws java.io.IOException {
                Socket socket = connectionDetails.getClientSocket(exit);
                server.set(socket);
                connectionManager.getEncryptionManager().setStreamToServer(socket.getOutputStream());

                // the session, if there is one, was just started on the client's thread that is connecting
                PlayerSession session = connectionManager.getSession();
                Thread serverListener = new Thread(() -> {
                    PlayerSession.bind(session);
                    final byte[] reply = new byte[4096];
                    attempt(() -> {
                        InputStream streamFromServer = socket.getInputStream();
                        int bytesRead;
                        while ((bytesRead = streamFromServer.read(reply)) != -1) {
                            if (session != null) {
                                session.notePacket();
                            }
                            onClientBoundPacket.pushData(reply, bytesRead);
                        }
                    }, (ex) -> {
                        if (!ended.get()) {
                            ex.printStackTrace();
                        }
                    });
                    System.out.println(session == null ? "Server closed the connection." : "Server closed the connection of " + session + ".");
                    end.run();
                    PlayerSession.bind(null);
                }, session == null ? "Proxy Server Listener" : "Proxy Server Listener " + session);
                serverListener.setPriority(10);
                serverListener.start();
            }

            @Override
            public void disconnectClient() {
                // the client's read loop notices and ends the session, outside of the packet handler that asked for this
                attempt(client::close);
            }
        });

        connectionManager.setMode(NetworkMode.HANDSHAKE);

        final byte[] request = new byte[4096];
        attempt(() -> {
            int bytesRead;
            while ((bytesRead = streamFromClient.read(request)) != -1) {
                onServerBoundPacket.pushData(request, bytesRead);
            }
        }, (ex) -> {
            if (!ended.get()) {
                ex.printStackTrace();
            }
        });
        if (connectionManager.isFleet()) {
            System.out.println("Client of " + (connectionManager.getSession() == null ? "a bot" : connectionManager.getSession()) + " closed the connection.");
        } else {
            System.out.println("Client closed the connection. Waiting for new connection...");
        }
        end.run();
    }
}
