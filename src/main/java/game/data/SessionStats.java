package game.data;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Keeps track of the numbers shown in the corner of the map: how fast chunks are coming in and how long the current
 * download session has been running.
 * <p>
 * A session starts with the first chunk that is received while saving isn't paused, or when saving is resumed. It
 * ends when saving is paused.
 */
public class SessionStats {
    // chunks per second is averaged over this window, so it doesn't jump around with every chunk batch
    static final long RATE_WINDOW_MILLIS = 5000;

    private final LongSupplier clock;
    private final Deque<Long> recentChunks = new ArrayDeque<>();
    // start of the current session, or -1 if there is no session running
    private final AtomicLong sessionStart = new AtomicLong(-1);

    public SessionStats() {
        this(System::currentTimeMillis);
    }

    SessionStats(LongSupplier clock) {
        this.clock = clock;
    }

    /**
     * Record that a chunk was received from the server.
     *
     * @param paused whether saving is paused, chunks received while paused don't start a session
     */
    public void chunkReceived(boolean paused) {
        long now = clock.getAsLong();
        synchronized (recentChunks) {
            recentChunks.addLast(now);
            prune(now);
        }
        if (!paused) {
            sessionStart.compareAndSet(-1, now);
        }
    }

    /**
     * Saving was resumed, start a new session.
     */
    public void startSession() {
        sessionStart.set(clock.getAsLong());
    }

    /**
     * Saving was paused, the session is over.
     */
    public void endSession() {
        sessionStart.set(-1);
    }

    public double chunksPerSecond() {
        synchronized (recentChunks) {
            prune(clock.getAsLong());
            return recentChunks.size() * 1000.0 / RATE_WINDOW_MILLIS;
        }
    }

    /**
     * How long the current session has been running in milliseconds, or -1 if there is no session.
     */
    public long sessionMillis() {
        long start = sessionStart.get();
        return start < 0 ? -1 : Math.max(0, clock.getAsLong() - start);
    }

    private void prune(long now) {
        while (!recentChunks.isEmpty() && recentChunks.peekFirst() <= now - RATE_WINDOW_MILLIS) {
            recentChunks.removeFirst();
        }
    }

    /**
     * Total size of all files in the given directory, 0 if it doesn't exist. Files that disappear or can't be read
     * while walking the directory (e.g. a region file being replaced) are skipped.
     */
    public static long directorySize(Path dir) {
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        AtomicLong size = new AtomicLong();
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    size.addAndGet(attrs.size());
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ex) {
            // keep what was counted so far
        }
        return size.get();
    }

    public static String formatSize(long bytes) {
        double mb = bytes / (1024.0 * 1024.0);
        if (mb < 1024) {
            return String.format("%.1f MB", mb);
        }
        return String.format("%.2f GB", mb / 1024.0);
    }

    public static String formatDuration(long millis) {
        long seconds = millis / 1000;
        return String.format("%d:%02d:%02d", seconds / 3600, (seconds / 60) % 60, seconds % 60);
    }
}
