package game.data.region;

import game.data.WorldManager;
import game.data.coordinates.Coordinate2D;
import game.data.coordinates.CoordinateDim2D;
import game.data.chunk.Chunk;
import game.data.chunk.ChunkBinary;
import game.data.dimension.Dimension;

import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * Class relating to a region (32x32 chunk area), corresponds to one MCA file. Changes to the chunks and the file are
 * made while holding the region's lock, see {@link RegionStore}.
 */
public class Region {
    public static final int REGION_SIZE = 32;

    private final int UNLOAD_RANGE = 24;
    private final Map<Coordinate2D, Chunk> chunks;
    private final CoordinateDim2D regionCoordinates;

    private volatile boolean updatedSinceLastWrite;
    // chunks were added to the files but writing them failed, retried on the next save
    private boolean writePending;
    private final Set<Coordinate2D> toDelete;
    private final ReentrantLock lock;

    private final McaFile file;
    private final McaFile fileEntities;

    /**
     * Initialise the region with the given coordinates.
     * @param regionCoordinates coordinates of the region (so global coordinates / 16 / 32)
     * @param lock              lock guarding this region, held while the existing file is read
     */
    Region(CoordinateDim2D regionCoordinates, ReentrantLock lock) {
        this.regionCoordinates = regionCoordinates;
        this.lock = lock;
        this.chunks = new ConcurrentHashMap<>();
        this.updatedSinceLastWrite = false;
        this.toDelete = ConcurrentHashMap.newKeySet();

        this.file = new McaFile(regionCoordinates);
        this.fileEntities = new McaFile(regionCoordinates, true);
    }

    /**
     * Add a chunk to the region.
     * @param coordinate the coordinate of the new chunk
     * @param chunk      the chunk to add
     */
    public void addChunk(Coordinate2D coordinate, Chunk chunk, boolean overrideExisting) {
        lock.lock();
        try {
            addChunkLocked(coordinate, chunk, overrideExisting);
        } finally {
            lock.unlock();
        }
    }

    private void addChunkLocked(Coordinate2D coordinate, Chunk chunk, boolean overrideExisting) {
        if (overrideExisting) {
            toDelete.remove(coordinate);
            try {
                ChunkBinary cb = file.getChunkBinary(coordinate);
                if (cb != null) {
                    chunk.parse(cb.getNbt());
                }
            } catch (Exception ex) {
                // if we can't read in the current chunk, that's fine, just continue.
            }

            chunks.put(coordinate, chunk);
        } else if (!chunks.containsKey(coordinate)) {
            toDelete.add(coordinate);
            chunks.putIfAbsent(coordinate, chunk);
        }
        updatedSinceLastWrite = true;
    }


    /**
     * Delete chunk when the server tells us to unload it.
     * @param coordinate chunk coordinate to unload
     */
    public void removeChunk(Coordinate2D coordinate) {
        Chunk chunk;
        lock.lock();
        try {
            chunk = chunks.get(coordinate);

            if (chunk == null) {
                return;
            }

            if (chunk.isSaved()) {
                chunks.remove(coordinate);
            } else {
                // kept until the next save has written it
                toDelete.add(coordinate);
                updatedSinceLastWrite = true;
            }
        } finally {
            lock.unlock();
        }
        chunk.unload();
    }


    /**
     * Returns true if the region has no chunks in it (e.g. they have all been deleted)
     */
    public boolean isEmpty() {
        return chunks.isEmpty();
    }

    public Chunk getChunk(Coordinate2D coordinate) {
        // if the chunk is already marked for deletion, don't return it
        if (toDelete.contains(coordinate)) { return null; }
        return chunks.get(coordinate);
    }

    /**
     * Write any unsaved chunks to the region file, then forget chunks that are out of range of every player in this
     * dimension.
     * @param playerChunks chunk position of each player in this region's dimension
     */
    public void save(Collection<Coordinate2D> playerChunks) throws IOException {
        lock.lock();
        try {
            if (toFile(playerChunks) != null) {
                writePending = true;
            }
            if (!writePending) {
                return;
            }
            write(file);
            write(fileEntities);
            writePending = false;
        } finally {
            lock.unlock();
        }
    }

    private static void write(McaFile file) throws IOException {
        if (file != null && !file.isEmpty()) {
            file.write();
        }
    }

    /**
     * Convert this region to an McaFile object. Will delete any chunks out of the render distance if they have already
     * been saved. Will update the Gui with the chunk that's about to be saved.
     * @return the McaFile corresponding to this region
     */
    McaFilePair toFile(Collection<Coordinate2D> playerChunks) {
        if (!updatedSinceLastWrite) {
            return null;
        }

        updatedSinceLastWrite = false;

        Map<Integer, ChunkBinary> chunkBinaryMap = new HashMap<>();
        Map<Integer, ChunkBinary> chunkEntityBinaryMap = new HashMap<>();
        chunks.keySet().forEach(coordinate -> {
            try {
                Chunk chunk = chunks.get(coordinate);

                // mark the chunk for deletion -- this is really only a back-up, the unload-chunk packet sent by the
                // server is what should be used to unload chunks correctly.
                if (playerChunks.stream().noneMatch(player -> player.isInRangeManhattan(coordinate, UNLOAD_RANGE))) {
                    toDelete.add(coordinate);
                }

                if (chunk.isSaved()) {
                    return;
                }

                chunk.setSaved(true);

                // get the chunk in binary format and get its coordinates as an Mca compatible integer. Then add
                // these to the map of chunk binaries.
                ChunkBinary binary = ChunkBinary.fromChunk(chunk);
                ChunkBinary entityBinary = null;
                if (chunk.hasSeparateEntities()) {
                    entityBinary = ChunkBinary.entitiesFromChunk(chunk);
                }

                Coordinate2D localCoordinate = coordinate.toRegionLocal();
                int pos = 4 * ((localCoordinate.getX() & 31) + (localCoordinate.getZ() & 31) * 32);
                if (binary != null) {
                    chunkBinaryMap.put(pos, binary);
                }
                if (entityBinary != null) {
                    chunkEntityBinaryMap.put(pos, entityBinary);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        });

        // delete chunks and their sent-later tile entities
        for (Coordinate2D c : toDelete) {
            WorldManager.getInstance().unloadEntities(c.addDimension(regionCoordinates.getDimension()));
            chunks.remove(c);
        }
        toDelete.clear();

        if (chunkBinaryMap.isEmpty() && chunkEntityBinaryMap.isEmpty()) {
            return null;
        }

        file.addChunks(chunkBinaryMap);
        fileEntities.addChunks(chunkEntityBinaryMap);
        return new McaFilePair(file, fileEntities);
    }

    public McaFile getFile() {
        return file;
    }

    public CoordinateDim2D getRegionCoordinates() {
        return regionCoordinates;
    }

    /**
     * Mark region as having modified chunks.
     */
    public void touch() {
        this.updatedSinceLastWrite = true;
    }

    public int countChunks() {
        return this.chunks.size();
    }

    /**
     * Unload all chunks in this region. Used when player disconnects or changes dimension.
     */
    public void unloadAll() {
        for (Coordinate2D co : this.chunks.keySet()) {
            removeChunk(co);
        }
    }

    public boolean canRemove() {
        return chunks.isEmpty() && !writePending;
    }

    public void forEach(Consumer<Chunk> f) {
        chunks.values().forEach(f);
    }
}
