package game.data.entity;

import config.Config;
import config.Version;
import game.data.PlayerSession;
import game.data.WorldManager;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import packets.DataTypeProvider;
import packets.UUID;
import packets.builder.PacketBuilder;
import packets.lib.ByteQueue;
import packets.version.DataTypeProvider_1_20_6;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Bots of a fleet see the same entities, which are only forgotten once none of them can see it any more.
 */
class EntityViewersTest {
    private static final int ENTITY_ID = 42;

    private final PlayerSession a = PlayerSession.fleet("BotA");
    private final PlayerSession b = PlayerSession.fleet("BotB");
    private EntityRegistry registry;
    private Entity entity;

    @AfterEach
    void tearDown() {
        PlayerSession.bind(null);
    }

    private void setUp() {
        WorldManager.setInstance(mock(WorldManager.class));
        Config.setInstance(new Config());
        Config.setProtocolVersion(Version.V1_20_6.protocolVersion);

        entity = mock(Entity.class);
        entity.uuid = new UUID(1, 2);
        when(entity.getId()).thenReturn(ENTITY_ID);
        registry = new EntityRegistry(mock(WorldManager.class));
    }

    private void addAs(PlayerSession session) {
        PlayerSession.bind(session);
        registry.addEntity(null, provider -> entity);
        PlayerSession.bind(null);
    }

    private void destroyAs(PlayerSession session) {
        PacketBuilder builder = new PacketBuilder(0x00);
        builder.writeVarInt(1);
        builder.writeVarInt(ENTITY_ID);

        PlayerSession.bind(session);
        registry.destroyEntities(parse(builder));
        PlayerSession.bind(null);
    }

    private void drain() throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        registry.addEntity(null, provider -> {
            done.countDown();
            return null;
        });
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        // the latch is counted down inside the task, give it a moment to finish
        Thread.sleep(50);
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

    @Test
    void entityStaysWhileAnotherBotSeesIt() throws InterruptedException {
        setUp();
        addAs(a);
        addAs(b);
        drain();
        assertThat(registry.countActiveEntities()).isEqualTo(1);

        destroyAs(a);
        assertThat(registry.countActiveEntities()).isEqualTo(1);

        destroyAs(b);
        assertThat(registry.countActiveEntities()).isZero();
    }

    @Test
    void disconnectingBotForgetsOnlyWhatNobodyElseSees() throws InterruptedException {
        setUp();
        addAs(a);
        addAs(b);
        drain();

        registry.forgetSession(a);
        assertThat(registry.countActiveEntities()).isEqualTo(1);

        registry.forgetSession(b);
        assertThat(registry.countActiveEntities()).isZero();
    }

    @Test
    void stoppedBotDoesntAddOrDestroy() throws InterruptedException {
        setUp();
        addAs(a);
        drain();

        b.quarantine("test");
        addAs(b);
        destroyAs(b);
        drain();
        assertThat(registry.countActiveEntities()).isEqualTo(1);
    }
}
