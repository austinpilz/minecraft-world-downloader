package proxy;

import config.Config;
import game.data.WorldManager;
import game.data.coordinates.Coordinate2D;
import game.data.coordinates.Coordinate3D;

import java.lang.management.ManagementFactory;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Diagnostic that periodically reports what the server is sending, what the client is acknowledging, and how long
 * the proxy itself spends relaying. Used to tell apart "the server stopped sending chunks" from "the proxy is holding
 * them up". Enabled with --log-traffic; every hook is a no-op otherwise.
 */
public final class TrafficMonitor {
    private static final long INTERVAL_SECONDS = 5;

    private static volatile boolean enabled;

    private static final LongAdder bytesFromServer = new LongAdder();
    private static final LongAdder bytesFromClient = new LongAdder();
    private static final Map<String, LongAdder> clientBound = new ConcurrentHashMap<>();
    private static final Map<String, LongAdder> serverBound = new ConcurrentHashMap<>();

    private static final LongAdder relayNanos = new LongAdder();
    private static final AtomicLong slowestRelayNanos = new AtomicLong();
    private static final LongAdder clientWriteNanos = new LongAdder();
    private static final AtomicLong slowestClientWriteNanos = new AtomicLong();
    private static final LongAdder injectedSent = new LongAdder();

    private static volatile long lastChunkFromServer;
    private static volatile long lastBytesFromServer;

    private TrafficMonitor() { }

    public static synchronized void enableIfRequested() {
        if (enabled || !Config.logTraffic()) {
            return;
        }
        enabled = true;
        lastChunkFromServer = lastBytesFromServer = System.nanoTime();

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Traffic Monitor");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(TrafficMonitor::report, INTERVAL_SECONDS, INTERVAL_SECONDS, TimeUnit.SECONDS);
        System.out.println("[traffic] Logging proxy traffic every " + INTERVAL_SECONDS + " seconds.");
    }

    public static void bytesFromServer(int amount) {
        if (!enabled) { return; }
        bytesFromServer.add(amount);
        lastBytesFromServer = System.nanoTime();
    }

    public static void bytesFromClient(int amount) {
        if (!enabled) { return; }
        bytesFromClient.add(amount);
    }

    public static void packet(boolean isClientBound, String type) {
        if (!enabled) { return; }
        String key = type == null ? "other" : type;
        (isClientBound ? clientBound : serverBound).computeIfAbsent(key, k -> new LongAdder()).increment();

        if (isClientBound && "LevelChunkWithLight".equals(type)) {
            lastChunkFromServer = System.nanoTime();
        }
    }

    /**
     * Time taken on the proxy thread to handle and forward one client-bound packet, including writing it (and any
     * injected packets) to the client.
     */
    public static void relayed(long nanos) {
        if (!enabled) { return; }
        relayNanos.add(nanos);
        slowestRelayNanos.accumulateAndGet(nanos, Math::max);
    }

    public static void clientWrite(long nanos) {
        if (!enabled) { return; }
        clientWriteNanos.add(nanos);
        slowestClientWriteNanos.accumulateAndGet(nanos, Math::max);
    }

    public static void injectedSent() {
        if (!enabled) { return; }
        injectedSent.increment();
    }

    private static long take(Map<String, LongAdder> counts, String type) {
        LongAdder adder = counts.get(type);
        return adder == null ? 0 : adder.sumThenReset();
    }

    private static long ms(long nanos) {
        return TimeUnit.NANOSECONDS.toMillis(nanos);
    }

    /**
     * Memory the whole process has claimed, including everything outside the Java heap (native libraries, graphics
     * drivers). On Windows this is the "commit size" Task Manager shows, which is what ran out when the downloader
     * crashed overnight with a 16 GB heap that was only 2 GB full. A steady climb here while the heap stays flat
     * points to a native leak. On other systems the JVM reports total virtual memory instead, which is less telling.
     */
    private static long processCommittedBytes() {
        if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
            return os.getCommittedVirtualMemorySize();
        }
        return -1;
    }

    private static void report() {
        try {
            long serverBytes = bytesFromServer.sumThenReset();
            long clientBytes = bytesFromClient.sumThenReset();
            if (serverBytes == 0 && clientBytes == 0) {
                return; // not connected / idle, don't spam
            }

            long chunks = take(clientBound, "LevelChunkWithLight");
            long forgets = take(clientBound, "ForgetLevelChunk");
            long batchStarts = take(clientBound, "ChunkBatchStart");
            long batchEnds = take(clientBound, "ChunkBatchFinished");
            long centerUpdates = take(clientBound, "SetChunkCacheCenter");
            long batchAcks = take(serverBound, "ChunkBatchReceived");
            long moves = take(serverBound, "MovePlayerPos") + take(serverBound, "MovePlayerPosRot") + take(serverBound, "MoveVehicle");
            clientBound.clear();
            serverBound.clear();

            long relay = relayNanos.sumThenReset();
            long slowestRelay = slowestRelayNanos.getAndSet(0);
            long write = clientWriteNanos.sumThenReset();
            long slowestWrite = slowestClientWriteNanos.getAndSet(0);
            long injected = injectedSent.sumThenReset();
            int injectedQueued = Config.getPacketInjector() == null ? 0 : Config.getPacketInjector().size();

            long now = System.nanoTime();
            String position = "?";
            Coordinate3D pos = WorldManager.getInstance().getPlayerPosition();
            if (pos != null) {
                Coordinate2D chunk = pos.globalToChunk();
                position = "(" + chunk.getX() + ", " + chunk.getZ() + ")";
            }

            Runtime runtime = Runtime.getRuntime();
            long heapUsed = runtime.totalMemory() - runtime.freeMemory();

            System.out.printf(
                "[traffic] SERVER sent %,d KB: %d chunks, %d batches (%d started), %d center updates, %d unloads"
                    + " | CLIENT: %d batch acks, %d moves, at chunk %s"
                    + " | PROXY: relaying took %d ms (slowest packet %d ms), client writes %d ms (slowest %d ms),"
                    + " %d extended chunks sent, %d queued"
                    + " | last chunk from server %.1fs ago, last byte %.1fs ago"
                    + " | MEMORY: process %,d MB committed, heap %,d MB used%n",
                serverBytes / 1024, chunks, batchEnds, batchStarts, centerUpdates, forgets,
                batchAcks, moves, position,
                ms(relay), ms(slowestRelay), ms(write), ms(slowestWrite), injected, injectedQueued,
                (now - lastChunkFromServer) / 1e9, (now - lastBytesFromServer) / 1e9,
                processCommittedBytes() >> 20, heapUsed >> 20
            );
        } catch (Exception ex) {
            System.out.println("[traffic] Could not report: " + ex);
        }
    }
}
