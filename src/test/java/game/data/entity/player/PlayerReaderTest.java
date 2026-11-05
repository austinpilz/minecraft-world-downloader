package game.data.entity.player;

import config.Config;
import config.Version;
import game.data.WorldManager;
import game.data.entity.PlayerEntity;
import org.junit.jupiter.api.Test;
import packets.DataTypeProvider;
import packets.UUID;
import packets.builder.PacketBuilder;
import packets.lib.ByteQueue;
import packets.version.DataTypeProvider_1_20_6;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Wire format taken from the 26.1 server jar: ClientboundPlayerInfoUpdatePacket has eight actions, the last two
 * being UPDATE_LIST_ORDER (VarInt) and UPDATE_HAT (boolean), and display names are NBT text components.
 */
class PlayerReaderTest {
    private static DataTypeProvider parse(PacketBuilder builder) {
        ByteQueue built = builder.build();
        byte[] arr = new byte[built.size()];
        built.copyTo(arr);

        DataTypeProvider provider = new DataTypeProvider_1_20_6(arr);
        provider.readVarInt(); // length
        provider.readVarInt(); // packet ID
        return provider;
    }

    private static void writePlayer(PacketBuilder builder, UUID uuid, String name, String displayName, int order) {
        builder.writeUUID(uuid);

        // add player
        builder.writeString(name);
        builder.writeVarInt(1);
        builder.writeString("textures");
        builder.writeString("e30=");
        builder.writeBoolean(true);
        builder.writeString("signature");

        // initialize chat, without a session
        builder.writeBoolean(false);
        // game mode, listed, latency
        builder.writeVarInt(1);
        builder.writeBoolean(true);
        builder.writeVarInt(42);

        // display name, as a string tag
        builder.writeBoolean(displayName != null);
        if (displayName != null) {
            byte[] bytes = displayName.getBytes(StandardCharsets.UTF_8);
            builder.writeByte((byte) 8);
            builder.writeShort(bytes.length);
            for (byte b : bytes) {
                builder.writeByte(b);
            }
        }

        // list order, hat
        builder.writeVarInt(order);
        builder.writeBoolean(true);
    }

    @Test
    void allActionsForSeveralPlayers26_1() {
        WorldManager.setInstance(mock(WorldManager.class));
        Config.setInstance(new Config());
        Config.setProtocolVersion(Version.V26_1_2.protocolVersion);

        UUID first = new UUID(1, 2);
        UUID second = new UUID(3, 4);
        PacketBuilder builder = new PacketBuilder(0x00);
        builder.writeByte((byte) 0xFF);
        builder.writeVarInt(2);
        writePlayer(builder, first, "Acct1", "Fancy name", 3);
        writePlayer(builder, second, "Acct2", null, 0);

        DataTypeProvider provider = parse(builder);
        Map<UUID, PlayerEntity> players = new HashMap<>();
        PlayerReader.getVersioned().updatePlayerAction(players, provider);

        assertThat(players).containsOnlyKeys(first, second);
        assertThat(provider.hasNext()).isFalse();
    }
}
