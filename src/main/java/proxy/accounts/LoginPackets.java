package proxy.accounts;

import com.google.gson.Gson;
import java.util.Map;
import packets.UUID;
import packets.builder.PacketBuilder;

/**
 * Packets the proxy sends itself when it holds the accounts, since the client connects offline and so can't send
 * them for us. The protocol version is passed in rather than read from the config so these can be tested.
 */
public final class LoginPackets {
    // https://minecraft.wiki/w/Java_Edition_protocol/Protocol_History
    private static final int V1_19 = 759;
    private static final int V1_19_1 = 760;
    private static final int V1_19_3 = 761;
    private static final int V1_20_2 = 764;

    private LoginPackets() { }

    /**
     * The Login Start the server gets, with the account's name and UUID instead of the offline client's.
     */
    public static PacketBuilder loginStart(int protocolVersion, String name, String uuid) {
        PacketBuilder builder = new PacketBuilder(0x00);
        builder.writeString(name);

        if (protocolVersion >= V1_20_2) {
            builder.writeUUID(new UUID(uuid));
        } else if (protocolVersion >= V1_19_3) {
            builder.writeBoolean(true);
            builder.writeUUID(new UUID(uuid));
        } else if (protocolVersion >= V1_19_1) {
            builder.writeBoolean(false); // no chat signing key
            builder.writeBoolean(true);
            builder.writeUUID(new UUID(uuid));
        } else if (protocolVersion >= V1_19) {
            builder.writeBoolean(false); // no chat signing key
        }
        return builder;
    }

    /**
     * The Encryption Response, with the shared secret and verify token already encrypted with the server's key.
     */
    public static PacketBuilder encryptionResponse(int protocolVersion, byte[] sharedSecret, byte[] verifyToken) {
        PacketBuilder builder = new PacketBuilder(0x01);
        builder.writeVarInt(sharedSecret.length);
        builder.writeByteArray(sharedSecret);

        // 1.19 to 1.19.2 could send a signature instead of the verify token
        if (protocolVersion >= V1_19 && protocolVersion < V1_19_3) {
            builder.writeBoolean(true);
        }
        builder.writeVarInt(verifyToken.length);
        builder.writeByteArray(verifyToken);
        return builder;
    }

    /**
     * Disconnect during login. The reason is a JSON text component in every version.
     */
    public static PacketBuilder loginDisconnect(String reason) {
        PacketBuilder builder = new PacketBuilder(0x00);
        builder.writeString(new Gson().toJson(Map.of("text", reason)));
        return builder;
    }

    /**
     * Reply to a status request. The client's own protocol version is echoed back so it shows as compatible.
     */
    public static PacketBuilder statusResponse(int protocolVersion, String motd) {
        Map<String, Object> status = Map.of(
            "version", Map.of("name", "MWD fleet", "protocol", protocolVersion),
            "players", Map.of("max", 0, "online", 0),
            "description", Map.of("text", motd)
        );

        PacketBuilder builder = new PacketBuilder(0x00);
        builder.writeString(new Gson().toJson(status));
        return builder;
    }

    public static PacketBuilder pong(long payload) {
        PacketBuilder builder = new PacketBuilder(0x01);
        builder.writeLong(payload);
        return builder;
    }
}
