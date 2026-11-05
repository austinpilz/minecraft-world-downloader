package game.data;

import game.data.commandblock.CommandBlockManager;
import game.data.container.ContainerManager;
import game.data.coordinates.Coordinate2D;
import game.data.coordinates.CoordinateDim2D;
import game.data.coordinates.CoordinateDouble3D;
import game.data.dimension.Dimension;
import game.data.dimension.DimensionType;
import game.data.villagers.VillagerManager;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import proxy.PacketInjector;

/**
 * State of one connected player: where it is, and whether what it receives may be saved. A single user has one long
 * lived session, in fleet mode every bot has its own while it's connected.
 *
 * Code handling a bot's packets runs with the bot's session bound to the thread (see {@link #bind}), so the
 * WorldManager can tell whose position and dimension it should use. Work handed to another thread is wrapped with
 * {@link #capture} to keep the binding.
 */
public class PlayerSession {
    private static final ThreadLocal<PlayerSession> bound = new ThreadLocal<>();

    private final String name;
    private final boolean fleet;
    private final CoordinateDouble3D position;
    private volatile double rotation;
    private volatile Dimension dimension;
    private volatile DimensionType dimensionType;
    private volatile PacketInjector injector;

    private volatile boolean saving = true;
    private volatile String quarantineReason;
    private volatile boolean loggedIn;
    private volatile String exitIp;
    private volatile Runnable kick;

    private final long connectedAt = System.currentTimeMillis();
    private volatile long lastPacketAt = connectedAt;
    private final AtomicLong chunksReceived = new AtomicLong();

    // chunks the server has sent this player and not yet told it to forget
    private final Set<CoordinateDim2D> loadedChunks = ConcurrentHashMap.newKeySet();

    private ContainerManager containerManager;
    private CommandBlockManager commandBlockManager;
    private VillagerManager villagerManager;

    private PlayerSession(String name, boolean fleet, CoordinateDouble3D position, Dimension dimension) {
        this.name = name;
        this.fleet = fleet;
        this.position = position;
        this.dimension = dimension;
    }

    /**
     * The session of a single user, which keeps its position in level.dat.
     */
    static PlayerSession single(CoordinateDouble3D position, Dimension dimension) {
        return new PlayerSession(null, false, position, dimension);
    }

    /**
     * A session for one bot of a fleet. Its dimension is unknown until the server tells it where it is.
     */
    public static PlayerSession fleet(String name) {
        return new PlayerSession(name, true, new CoordinateDouble3D(0, 0, 0), null);
    }

    /**
     * Bind the session to the current thread, or unbind with null.
     */
    public static void bind(PlayerSession session) {
        if (session == null) {
            bound.remove();
        } else {
            bound.set(session);
        }
    }

    public static PlayerSession bound() {
        return bound.get();
    }

    /**
     * Wrap the task so that it runs with the session of the thread creating it, when another thread picks it up.
     */
    public static Runnable capture(Runnable task) {
        PlayerSession session = bound.get();
        if (session == null) {
            return task;
        }
        return () -> {
            PlayerSession previous = bound.get();
            bound.set(session);
            try {
                task.run();
            } finally {
                bind(previous);
            }
        };
    }

    /**
     * Whether chunks and entities received by this player may go into the world.
     */
    public boolean acceptsData() {
        return saving && quarantineReason == null;
    }

    /**
     * Stop taking anything this player receives, for example because the server moved it to another world. Stays
     * in place until {@link #resume} is called, so the user decides whether the new place belongs in this world.
     * @return false if it was quarantined already
     */
    public boolean quarantine(String reason) {
        if (quarantineReason != null) {
            return false;
        }
        quarantineReason = reason;
        return true;
    }

    public void resume() {
        quarantineReason = null;
    }

    public String getQuarantineReason() {
        return quarantineReason;
    }

    public boolean isQuarantined() {
        return quarantineReason != null;
    }

    public boolean isSaving() {
        return saving;
    }

    public void setSaving(boolean saving) {
        this.saving = saving;
    }

    /**
     * Disconnect the player, if it can be.
     */
    public void kick() {
        Runnable kick = this.kick;
        if (kick != null) {
            kick.run();
        }
    }

    public void setKick(Runnable kick) {
        this.kick = kick;
    }

    public String getName() {
        return name;
    }

    public boolean isFleet() {
        return fleet;
    }

    CoordinateDouble3D getPosition() {
        return position;
    }

    public Coordinate2D getChunkPosition() {
        return position.discretize().globalToChunk();
    }

    public double getRotation() {
        return rotation;
    }

    void setRotation(double rotation) {
        this.rotation = rotation;
    }

    public Dimension getDimension() {
        return dimension;
    }

    void setDimension(Dimension dimension) {
        this.dimension = dimension;
    }

    public DimensionType getDimensionType() {
        return dimensionType;
    }

    void setDimensionType(DimensionType dimensionType) {
        this.dimensionType = dimensionType;
    }

    public PacketInjector getInjector() {
        return injector;
    }

    public void setInjector(PacketInjector injector) {
        this.injector = injector;
    }

    /**
     * Note that the server sent a Login packet.
     * @return true if it had sent one before on this connection
     */
    boolean markLoggedIn() {
        boolean before = loggedIn;
        loggedIn = true;
        return before;
    }

    public boolean isLoggedIn() {
        return loggedIn;
    }

    public String getExitIp() {
        return exitIp;
    }

    public void setExitIp(String exitIp) {
        this.exitIp = exitIp;
    }

    public long getConnectedAt() {
        return connectedAt;
    }

    public long getLastPacketAt() {
        return lastPacketAt;
    }

    public void notePacket() {
        lastPacketAt = System.currentTimeMillis();
    }

    public long getChunksReceived() {
        return chunksReceived.get();
    }

    Set<CoordinateDim2D> loadedChunks() {
        return loadedChunks;
    }

    void chunkLoaded(CoordinateDim2D coordinate) {
        loadedChunks.add(coordinate);
        chunksReceived.incrementAndGet();
    }

    synchronized ContainerManager getContainerManager() {
        if (containerManager == null) {
            containerManager = new ContainerManager();
        }
        return containerManager;
    }

    synchronized CommandBlockManager getCommandBlockManager() {
        if (commandBlockManager == null) {
            commandBlockManager = new CommandBlockManager();
        }
        return commandBlockManager;
    }

    synchronized VillagerManager getVillagerManager() {
        if (villagerManager == null) {
            villagerManager = new VillagerManager();
        }
        return villagerManager;
    }

    @Override
    public String toString() {
        return name == null ? "player" : name;
    }
}
