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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Wire formats taken from the 26.3 server jar: ClientboundMoveEntityPacket (VarInt properties + VecDelta) and
 * ClientboundEntityPositionSyncPacket (PositionPath).
 */
class EntityRegistryMovementTest {
    private static final int ENTITY_ID = 42;

    private Entity setUp(Version version) {
        WorldManager.setInstance(mock(WorldManager.class));
        Config.setInstance(new Config());
        Config.setProtocolVersion(version.protocolVersion);

        Entity entity = mock(Entity.class);
        entity.uuid = new UUID(1, 2);
        when(entity.getId()).thenReturn(ENTITY_ID);
        return entity;
    }

    private EntityRegistry registryWith(Entity entity) {
        EntityRegistry registry = new EntityRegistry(mock(WorldManager.class));
        registry.addEntity(null, provider -> entity);
        return registry;
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

    /**
     * Completes with the absolute position the registry hands to readPosition (parsing runs on the registry's
     * executor thread).
     */
    private static CompletableFuture<double[]> capturePosition(Entity entity) {
        CompletableFuture<double[]> read = new CompletableFuture<>();
        doAnswer(inv -> {
            DataTypeProvider p = inv.getArgument(0);
            read.complete(new double[] { p.readDouble(), p.readDouble(), p.readDouble() });
            return null;
        }).when(entity).readPosition(any());
        return read;
    }

    private static void writeDouble(PacketBuilder builder, double d) {
        builder.writeLong(Double.doubleToLongBits(d));
    }

    @Test
    void relativeMoveBefore26_3() {
        Entity entity = setUp(Version.V26_2);
        EntityRegistry registry = registryWith(entity);

        PacketBuilder builder = new PacketBuilder(0x00);
        builder.writeVarInt(ENTITY_ID);
        builder.writeShort(100);
        builder.writeShort(-20);
        builder.writeShort(5);
        builder.writeBoolean(true);

        registry.updatePositionRelative(parse(builder));
        verify(entity, timeout(1000)).incrementPosition(100, -20, 5);
    }

    @Test
    void linearRelativeMove26_3() {
        Entity entity = setUp(Version.V26_3);
        EntityRegistry registry = registryWith(entity);

        PacketBuilder builder = new PacketBuilder(0x00);
        builder.writeVarInt(ENTITY_ID);
        builder.writeVarInt(1); // on ground, no steps
        builder.writeShort(100);
        builder.writeShort(-20);
        builder.writeShort(5);

        registry.updatePositionRelative(parse(builder));
        verify(entity, timeout(1000)).incrementPosition(100, -20, 5);
    }

    @Test
    void steppedRelativeMove26_3() {
        Entity entity = setUp(Version.V26_3);
        EntityRegistry registry = registryWith(entity);

        PacketBuilder builder = new PacketBuilder(0x00);
        builder.writeVarInt(ENTITY_ID);
        builder.writeVarInt(3 << 1); // three steps, not on ground
        for (int i = 0; i < 3; i++) {
            builder.writeVarInt(i);      // tick offset
            builder.writeShort(10);
            builder.writeShort(-1);
            builder.writeShort(i);
        }
        builder.writeByte((byte) 0); // yRot
        builder.writeByte((byte) 0); // xRot

        registry.updatePositionRelative(parse(builder));
        verify(entity, timeout(1000)).incrementPosition(30, -3, 3);
    }

    @Test
    void linearPositionSync26_3() throws Exception {
        Entity entity = setUp(Version.V26_3);
        CompletableFuture<double[]> read = capturePosition(entity);
        EntityRegistry registry = registryWith(entity);

        PacketBuilder builder = new PacketBuilder(0x00);
        builder.writeVarInt(ENTITY_ID);
        builder.writeVarInt(0); // linear
        writeDouble(builder, 1.5);
        writeDouble(builder, 64);
        writeDouble(builder, -300.25);

        registry.updatePositionSync(parse(builder));
        assertThat(read.get(1, TimeUnit.SECONDS)).containsExactly(1.5, 64, -300.25);
    }

    @Test
    void steppedPositionSync26_3() throws Exception {
        Entity entity = setUp(Version.V26_3);
        CompletableFuture<double[]> read = capturePosition(entity);
        EntityRegistry registry = registryWith(entity);

        PacketBuilder builder = new PacketBuilder(0x00);
        builder.writeVarInt(ENTITY_ID);
        builder.writeVarInt(1); // stepped
        builder.writeVarInt(2);
        writeDouble(builder, 10);
        writeDouble(builder, 20);
        writeDouble(builder, 30);
        builder.writeVarInt(1);
        writeDouble(builder, 11);
        writeDouble(builder, 21);
        writeDouble(builder, 31);
        builder.writeVarInt(2);

        registry.updatePositionSync(parse(builder));
        assertThat(read.get(1, TimeUnit.SECONDS)).containsExactly(11, 21, 31);
    }
}
