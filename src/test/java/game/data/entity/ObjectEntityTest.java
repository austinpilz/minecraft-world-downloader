package game.data.entity;

import config.Config;
import config.Version;
import game.data.WorldManager;
import org.junit.jupiter.api.Test;
import packets.DataTypeProvider;
import packets.UUID;
import packets.builder.PacketBuilder;
import packets.lib.ByteQueue;
import packets.version.DataTypeProvider_1_20_6;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Wire format taken from the 26.1 server jar: ClientboundAddEntityPacket writes the movement as an LpVec3 between the
 * position and the rotations, where older versions wrote three shorts at the end.
 */
class ObjectEntityTest {
    private void setUp(Version version) {
        EntityNames names = mock(EntityNames.class);
        when(names.getName(anyInt())).thenReturn("minecraft:zombie");
        WorldManager worldManager = mock(WorldManager.class);
        when(worldManager.getEntityMap()).thenReturn(names);
        WorldManager.setInstance(worldManager);

        Config.setInstance(new Config());
        Config.setProtocolVersion(version.protocolVersion);
    }

    private static DataTypeProvider parse(PacketBuilder builder) {
        ByteQueue built = builder.build();
        byte[] arr = new byte[built.size()];
        built.copyTo(arr);

        DataTypeProvider provider = new DataTypeProvider_1_20_6(arr);
        provider.readVarInt(); // length
        provider.readVarInt(); // packet ID
        return provider;
    }

    private static PacketBuilder start() {
        PacketBuilder builder = new PacketBuilder(0x00);
        builder.writeVarInt(7);
        builder.writeUUID(new UUID(1, 2));
        builder.writeVarInt(5);
        builder.writeLong(Double.doubleToLongBits(10.5));
        builder.writeLong(Double.doubleToLongBits(64.0));
        builder.writeLong(Double.doubleToLongBits(-20.25));
        return builder;
    }

    private static void writeRotationsAndData(PacketBuilder builder) {
        builder.writeByte((byte) 10);
        builder.writeByte((byte) 20);
        builder.writeByte((byte) 30);
        builder.writeVarInt(99);
    }

    private static void assertParsed(DataTypeProvider provider) {
        Entity entity = ObjectEntity.parse(provider);

        assertThat(entity).isNotNull();
        assertThat(new double[] { entity.x, entity.y, entity.z }).containsExactly(10.5, 64.0, -20.25);
        assertThat(entity.pitch).isEqualTo(10);
        assertThat(entity.yaw).isEqualTo(20);
        assertThat(provider.hasNext()).isFalse();
    }

    @Test
    void zeroVelocity26_1() {
        setUp(Version.V26_1_2);
        PacketBuilder builder = start();
        builder.writeByte((byte) 0);
        writeRotationsAndData(builder);

        assertParsed(parse(builder));
    }

    @Test
    void velocity26_1() {
        setUp(Version.V26_1_2);
        PacketBuilder builder = start();
        builder.writeByte((byte) 0x03);
        builder.writeByte((byte) 0x12);
        builder.writeInt(0x34567890);
        writeRotationsAndData(builder);

        assertParsed(parse(builder));
    }

    @Test
    void largeVelocity26_1() {
        setUp(Version.V26_1_2);
        PacketBuilder builder = start();
        // the continuation bit adds a VarInt to the scale
        builder.writeByte((byte) 0x07);
        builder.writeByte((byte) 0x12);
        builder.writeInt(0x34567890);
        builder.writeVarInt(300);
        writeRotationsAndData(builder);

        assertParsed(parse(builder));
    }

    @Test
    void velocityAtTheEndBefore1_21_9() {
        setUp(Version.V1_21_8);
        PacketBuilder builder = start();
        writeRotationsAndData(builder);
        builder.writeShort(100);
        builder.writeShort(-200);
        builder.writeShort(300);

        assertParsed(parse(builder));
    }
}
