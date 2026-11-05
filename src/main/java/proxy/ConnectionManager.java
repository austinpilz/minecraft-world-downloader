package proxy;

import config.Config;
import game.NetworkMode;
import game.data.PlayerSession;
import game.data.WorldManager;
import game.protocol.ConfigurationProtocol;
import game.protocol.HandshakeProtocol;
import game.protocol.LoginProtocol;
import game.protocol.Protocol;
import game.protocol.StatusProtocol;
import java.io.IOException;
import packets.DataReader;
import packets.handler.*;
import proxy.accounts.AccountStore;
import proxy.accounts.AccountCommands;
import proxy.accounts.BotLogin;
import proxy.accounts.ExitChecker;
import proxy.accounts.LoginPackets;

/**
 * Class to manage the connection status.
 */
public class ConnectionManager {
    private DataReader serverBoundDataReader;
    private DataReader clientBoundDataReader;
    private EncryptionManager encryptionManager;
    private CompressionManager compressionManager;

    private NetworkMode mode = NetworkMode.STATUS;
    private volatile Protocol protocol = new HandshakeProtocol();

    // proxy-held accounts only
    private ServerConnector serverConnector;
    private BotLogin botLogin;
    private volatile BotLogin.Session botSession;
    private int pendingProtocolVersion;
    private int pendingNextMode;
    private String pendingHostExtension = "";
    private String pendingRefusal;

    // fleet mode only: this connection is one of several, with its own session once the bot has logged in
    private final boolean fleet;
    private volatile PlayerSession session;

    public ConnectionManager() {
        this.fleet = false;
    }

    private ConnectionManager(BotLogin botLogin) {
        this.fleet = true;
        this.botLogin = botLogin;
        init();
    }

    /**
     * Opens the connection to the remote server once we know which bot is connecting, since with proxy-held
     * accounts that decides the exit it goes out through.
     */
    public interface ServerConnector {
        void connect(SocksProxyDetails exit) throws IOException;

        void disconnectClient();
    }

    public NetworkMode getMode() {
        return mode;
    }

    public void setMode(NetworkMode mode) {
        this.mode = mode;

        switch (mode) {
            case STATUS:
                protocol = new StatusProtocol();
                serverBoundDataReader.setPacketHandler(new ServerBoundStatusPacketHandler(this));
                clientBoundDataReader.setPacketHandler(new ClientBoundStatusPacketHandler(this));
                break;
            case LOGIN:
                protocol = new LoginProtocol();
                serverBoundDataReader.setPacketHandler(new ServerBoundLoginPacketHandler(this));
                clientBoundDataReader.setPacketHandler(new ClientBoundLoginPacketHandler(this));
                break;
            case GAME:
                protocol = Config.getGameProtocol();
                serverBoundDataReader.setPacketHandler(new ServerBoundGamePacketHandler(this));
                clientBoundDataReader.setPacketHandler(ClientBoundGamePacketHandler.of(this));
                break;
            case HANDSHAKE:
                protocol = new HandshakeProtocol();
                serverBoundDataReader.setPacketHandler(new ServerBoundHandshakePacketHandler(this));
                clientBoundDataReader.setPacketHandler(new ClientBoundHandshakePacketHandler(this));
                break;
            case CONFIGURATION:
                protocol = new ConfigurationProtocol();
                serverBoundDataReader.setPacketHandler(new ServerBoundConfigurationPacketHandler(this));
                clientBoundDataReader.setPacketHandler(ClientBoundConfigurationPacketHandler.of(this));
                break;
        }
    }

    /**
     * The protocol packets are read with, which depends on the connection's current mode.
     */
    public Protocol getProtocol() {
        return protocol;
    }

    private void init() {
        compressionManager = new CompressionManager();
        encryptionManager = new EncryptionManager(compressionManager);
        serverBoundDataReader = DataReader.serverBound(encryptionManager);
        clientBoundDataReader = DataReader.clientBound(encryptionManager);

        setMode(NetworkMode.HANDSHAKE);
    }

    /**
     * Starts the proxy.
     */
    public void startProxy() {
        init();

        if (Config.proxyAccounts()) {
            AccountStore store = AccountStore.get();
            if (!Config.socksPoolFile().isEmpty()) {
                AccountCommands.loadPoolFile(store, Config.socksPoolFile(), Config.socksProxyUsername(), Config.socksProxyPassword());
            }
            botLogin = new BotLogin(store, new ExitChecker());
            System.out.println("Proxy-held accounts: " + store.getBots().size() + " bots. Clients connect offline with a bot's name.");
        }
        if (Config.isFleet()) {
            System.out.println("Fleet mode: every bot gets its own connection, and they all save into " + Config.getWorldOutputDir() + ".");
        }

        ProxyServer proxy = new ProxyServer(this, Config.getConnectionDetails());
        proxy.runServer(serverBoundDataReader, clientBoundDataReader);

        Config.registerPacketInjector(this.getEncryptionManager().getPacketInjector());
    }

    /**
     * A new connection for one bot of the fleet, sharing this one's accounts.
     */
    public ConnectionManager newFleetConnection() {
        return new ConnectionManager(botLogin);
    }

    /**
     * Reset the connection when its lost. A connection of the fleet only ends its bot's session, the world and the
     * other bots carry on.
     */
    public void reset() {
        if (botLogin != null) {
            botLogin.release(botSession);
        }
        botSession = null;
        encryptionManager.reset();
        compressionManager.reset();
        serverBoundDataReader.reset();
        clientBoundDataReader.reset();
        setMode(NetworkMode.HANDSHAKE);

        if (fleet) {
            PlayerSession ended = session;
            session = null;
            if (ended != null) {
                WorldManager.getInstance().endFleetSession(ended);
                System.out.println("Bot " + ended + " disconnected, " + WorldManager.getInstance().getFleetSessions().size() + " still online.");
            }
        } else {
            WorldManager.getInstance().resetConnection();
        }
    }

    public boolean isProxyAccounts() {
        return botLogin != null;
    }

    public boolean isFleet() {
        return fleet;
    }

    /**
     * The session of this connection's bot in fleet mode, null before it has logged in.
     */
    public PlayerSession getSession() {
        return session;
    }

    public DataReader getServerBoundDataReader() {
        return serverBoundDataReader;
    }

    public DataReader getClientBoundDataReader() {
        return clientBoundDataReader;
    }

    /**
     * Refuse the client once it tries to log in, for example because it uses another version than the fleet.
     */
    public void refuseLogin(String reason) {
        this.pendingRefusal = reason;
    }

    public void setServerConnector(ServerConnector serverConnector) {
        this.serverConnector = serverConnector;
    }

    public BotLogin.Session getBotSession() {
        return botSession;
    }

    /**
     * With proxy-held accounts the handshake is kept until the bot has logged in, since there is no server
     * connection to send it to yet.
     */
    public void rememberHandshake(int protocolVersion, int nextMode, String hostExtension) {
        this.pendingProtocolVersion = protocolVersion;
        this.pendingNextMode = nextMode;
        this.pendingHostExtension = hostExtension;
    }

    /**
     * Log the connecting client in as the bot with the given name: connect to the server through the bot's exit and
     * log in there with the bot's account. A client that isn't a bot is disconnected.
     */
    public void loginAsBot(String clientName) {
        if (pendingRefusal != null) {
            refuseClient(pendingRefusal);
            return;
        }
        if (fleet && !WorldManager.getInstance().isAcceptingBots()) {
            refuseClient("The fleet isn't taking new bots right now.");
            return;
        }

        BotLogin.Session session;
        try {
            session = botLogin.login(clientName);
        } catch (BotLogin.Refused ex) {
            refuseClient(ex.getMessage());
            return;
        }
        botSession = session;

        if (fleet) {
            startFleetSession(session);
        }

        System.out.println("Bot " + session.botName() + " logs in as " + session.account().getUsername()
            + (session.exit() == null ? " connecting directly." : " through " + session.exit().getFriendlyHost() + " (exit IP " + session.exitIp() + ")."));

        try {
            serverConnector.connect(session.exit());
            encryptionManager.sendMaskedHandshake(pendingProtocolVersion, pendingNextMode, pendingHostExtension);
            encryptionManager.streamToServer(LoginPackets.loginStart(
                Config.getProtocolVersion(), session.account().getUsername(), session.account().getUuid()).build());
        } catch (IOException ex) {
            botLogin.release(session);
            botSession = null;
            refuseClient("Cannot connect to " + Config.getConnectionDetails().getFriendlyHost() + ": " + ex.getMessage());
        }
    }

    /**
     * Give the bot its own session. This runs on the thread reading from the client, the thread reading from the
     * server binds it when it starts.
     */
    private void startFleetSession(BotLogin.Session login) {
        PlayerSession started = PlayerSession.fleet(login.botName());
        started.setInjector(encryptionManager.getPacketInjector());
        started.setExitIp(login.exitIp());
        started.setExit(login.exit() == null ? "direct" : login.exit().getFriendlyHost());
        started.setAccountName(login.account().getUsername());
        started.setKick(() -> serverConnector.disconnectClient());
        PlayerSession.bind(started);

        session = started;
        WorldManager.getInstance().addFleetSession(started);
    }

    /**
     * Tell the client why it can't log in, then close its connection.
     */
    public void refuseClient(String reason) {
        System.out.println("Refused login: " + reason);
        try {
            encryptionManager.streamToClient(LoginPackets.loginDisconnect(reason).build());
        } catch (IOException ex) {
            // the client is gone already
        }
        serverConnector.disconnectClient();
    }

    public EncryptionManager getEncryptionManager() {
        return encryptionManager;
    }

    public CompressionManager getCompressionManager() {
        return compressionManager;
    }
}
