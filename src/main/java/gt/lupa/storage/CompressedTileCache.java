package gt.lupa.storage;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Global byte-bounded LRU for validated, published JPEG tiles.
 *
 * The key includes immutable image version identity. The cache owns one immutable
 * byte array per resident entry. Acquired TileData handles share the immutable
 * array without sharing mutable ByteBuffer position/state.
 *
 * If an entry is evicted while a handle is still alive, those bytes move from
 * residentBytes to retainedEvictedBytes until the final handle closes.
 */
public final class CompressedTileCache {
    private final long maxBytes;
    private final LinkedHashMap<Key, Entry> entries =
            new LinkedHashMap<>(64, 0.75f, true);

    private long residentBytes;
    private long retainedEvictedBytes;
    private long hits;
    private long misses;
    private long evictions;
    private long duplicateLoads;
    private long oversizedBypasses;
    private long activeLeases;

    public CompressedTileCache(long maxBytes) {
        if (maxBytes < 1) throw new IllegalArgumentException("maxBytes must be positive");
        this.maxBytes = maxBytes;
    }

    public synchronized TileData acquire(Key key) {
        Objects.requireNonNull(key);
        Entry entry = entries.get(key);
        if (entry == null) {
            misses++;
            return null;
        }
        hits++;
        return lease(entry);
    }

    /**
     * Inserts a successfully validated tile and returns a handle for the caller.
     * Failed reads never reach this method and therefore are never cached.
     */
    public TileData insertAndAcquire(Key key, TileData loaded) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(loaded);

        int length = loaded.jpegLength();
        if (length > maxBytes) {
            synchronized (this) {
                oversizedBypasses++;
            }
            return loaded;
        }

        byte[] ownedBytes = loaded.jpegUnsafe();
        int width = loaded.width();
        int height = loaded.height();

        synchronized (this) {
            Entry existing = entries.get(key);
            if (existing != null) {
                duplicateLoads++;
                loaded.close();
                return lease(existing);
            }

            evictUntilFits(length);
            Entry entry = new Entry(ownedBytes, width, height);
            entries.put(key, entry);
            residentBytes += length;
            loaded.close();
            return lease(entry);
        }
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(
                maxBytes,
                residentBytes,
                retainedEvictedBytes,
                entries.size(),
                activeLeases,
                hits,
                misses,
                evictions,
                duplicateLoads,
                oversizedBypasses);
    }

    private TileData lease(Entry entry) {
        entry.leases++;
        activeLeases++;
        return TileData.shared(
                entry.jpeg,
                entry.width,
                entry.height,
                () -> release(entry));
    }

    private void evictUntilFits(long incomingBytes) {
        Iterator<Map.Entry<Key, Entry>> iterator = entries.entrySet().iterator();
        while (residentBytes + incomingBytes > maxBytes && iterator.hasNext()) {
            Entry evicted = iterator.next().getValue();
            iterator.remove();
            evicted.resident = false;
            residentBytes -= evicted.jpeg.length;
            evictions++;
            if (evicted.leases > 0) {
                retainedEvictedBytes += evicted.jpeg.length;
            }
        }
    }

    private synchronized void release(Entry entry) {
        if (entry.leases < 1) {
            throw new IllegalStateException("cache tile lease released more than once");
        }
        entry.leases--;
        activeLeases--;
        if (!entry.resident && entry.leases == 0) {
            retainedEvictedBytes -= entry.jpeg.length;
        }
    }

    public record Key(
            String imageId,
            String imageVersion,
            int z,
            int x,
            int y) {
        public Key {
            if (imageId == null || imageId.isBlank()) {
                throw new IllegalArgumentException("imageId is required");
            }
            if (imageVersion == null || imageVersion.isBlank()) {
                throw new IllegalArgumentException("imageVersion is required");
            }
            if (z < 0 || x < 0 || y < 0) {
                throw new IllegalArgumentException("tile coordinates must be nonnegative");
            }
        }

        public static Key of(
                PublishedImageStore.OpenedImage opened,
                int z,
                int x,
                int y) {
            Objects.requireNonNull(opened);
            return new Key(
                    opened.catalogImage().imageId(),
                    opened.catalogImage().imageVersion(),
                    z,
                    x,
                    y);
        }
    }

    public record Snapshot(
            long maxBytes,
            long residentBytes,
            long retainedEvictedBytes,
            int entries,
            long activeLeases,
            long hits,
            long misses,
            long evictions,
            long duplicateLoads,
            long oversizedBypasses) {}

    private static final class Entry {
        private final byte[] jpeg;
        private final int width;
        private final int height;
        private boolean resident = true;
        private int leases;

        private Entry(byte[] jpeg, int width, int height) {
            this.jpeg = jpeg;
            this.width = width;
            this.height = height;
        }
    }
}
