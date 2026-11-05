package proxy;

import config.Config;
import game.NetworkMode;
import game.data.WorldManager;
import game.protocol.ConfigurationProtocol;
import game.protocol.HandshakeProtocol;
import game.protocol.LoginProtocol;
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

    // proxy-held accounts only
    private ServerConnector serverConnector;
    private BotLogin botLogin;
    private volatile BotLogin.Session botSession;
    private int pendingProtocolVersion;
    private int pendingNextMode;
    private String pendingHostExtension = "";

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
                PacketHandler.setProtocol(new StatusProtocol());
                serverBoundDataReader.setPacketHandler(new ServerBoundStatusPacketHandler(this));
                clientBoundDataReader.setPacketHandler(new ClientBoundStatusPacketHandler(this));
                break;
            case LOGIN:
                PacketHandler.setProtocol(new LoginProtocol());
                serverBoundDataReader.setPacketHandler(new ServerBoundLoginPacketHandler(this));
                clientBoundDataReader.setPacketHandler(new ClientBoundLoginPacketHandler(this));
                break;
            case GAME:
                PacketHandler.setProtocol(Config.getGameProtocol());
                serverBoundDataReader.setPacketHandler(new ServerBoundGamePacketHandler(this));
                clientBoundDataReader.setPacketHandler(ClientBoundGamePacketHandler.of(this));
                break;
            case HANDSHAKE:
                PacketHandler.setProtocol(new HandshakeProtocol());
                serverBoundDataReader.setPacketHandler(new ServerBoundHandshakePacketHandler(this));
                clientBoundDataReader.setPacketHandler(new ClientBoundHandshakePacketHandler(this));
                break;
            case CONFIGURATION:
                PacketHandler.setProtocol(new ConfigurationProtocol());
                serverBoundDataReader.setPacketHandler(new ServerBoundConfigurationPacketHandler(this));
                clientBoundDataReader.setPacketHandler(ClientBoundConfigurationPacketHandler.of(this));
                break;
        }
    }

    /**
     * Starts the proxy.
     */
    public void startProxy() {
        compressionManager = new CompressionManager();
        encryptionManager = new EncryptionManager(compressionManager);
        serverBoundDataReader = DataReader.serverBound(encryptionManager);
        clientBoundDataReader = DataReader.clientBound(encryptionManager);

        setMode(NetworkMode.HANDSHAKE);

        if (Config.proxyAccounts()) {
            AccountStore store = AccountStore.get();
            if (!Config.socksPoolFile().isEmpty()) {
                AccountCommands.loadPoolFile(store, Config.socksPoolFile(), Config.socksProxyUsername(), Config.socksProxyPassword());
            }
            botLogin = new BotLogin(store, new ExitChecker());
            System.out.println("Proxy-held accounts: " + store.getBots().size() + " bots. Clients connect offline with a bot's name.");
        }

        ProxyServer proxy = new ProxyServer(this, Config.getConnectionDetails());
        proxy.runServer(serverBoundDataReader, clientBoundDataReader);

        Config.registerPacketInjector(this.getEncryptionManager().getPacketInjector());
    }

    /**
     * Reset the connection when its lost.
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
        WorldManager.getInstance().resetConnection();
    }

    public boolean isProxyAccounts() {
        return botLogin != null;
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
        BotLogin.Session session;
        try {
            session = botLogin.login(clientName);
        } catch (BotLogin.Refused ex) {
            refuseClient(ex.getMessage());
            return;
        }
        botSession = session;

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
