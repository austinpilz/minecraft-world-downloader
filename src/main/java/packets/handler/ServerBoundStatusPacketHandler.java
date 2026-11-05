package packets.handler;

import config.Config;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import proxy.ConnectionManager;
import proxy.accounts.LoginPackets;

public class ServerBoundStatusPacketHandler extends PacketHandler {
    private final HashMap<String, PacketOperator> operations = new HashMap<>();

    public ServerBoundStatusPacketHandler(ConnectionManager connectionManager) {
        super(connectionManager);

        // with proxy-held accounts there's no server connection until a bot logs in, so the proxy answers the
        // server list ping itself
        operations.put("StatusRequest", provider -> {
            if (!connectionManager.isProxyAccounts()) {
                return true;
            }
            reply(LoginPackets.statusResponse(Config.getProtocolVersion(), "Minecraft World Downloader fleet proxy"));
            return false;
        });

        operations.put("Ping", provider -> {
            if (!connectionManager.isProxyAccounts()) {
                return true;
            }
            reply(LoginPackets.pong(provider.readLong()));
            return false;
        });
    }

    private void reply(packets.builder.PacketBuilder packet) {
        try {
            getConnectionManager().getEncryptionManager().streamToClient(packet.build());
        } catch (IOException ex) {
            // the client is gone already
        }
    }

    @Override
    public Map<String, PacketOperator> getOperators() {
        return operations;
    }

    @Override
    public boolean isClientBound() {
        return false;
    }
}
