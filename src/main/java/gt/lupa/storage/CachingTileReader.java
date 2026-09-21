package gt.lupa.storage;

import java.util.Objects;

/**
 * Read-through cache for immutable published JPEG tiles.
 *
 * Simultaneous misses may perform duplicate disk reads. E22 deliberately keeps
 * this simple: TileReadAdmission bounds the reads, avoiding a second subscriber
 * table that would need its own independent bounds.
 */
public final class CachingTileReader implements TileReader {
    private final TileReader delegate;
    private final CompressedTileCache cache;

    public CachingTileReader(TileReader delegate, CompressedTileCache cache) {
        this.delegate = Objects.requireNonNull(delegate);
        this.cache = Objects.requireNonNull(cache);
    }

    @Override
    public TileData read(
            PublishedImageStore.OpenedImage opened,
            int z,
            int x,
            int y) throws TileReadException {
        CompressedTileCache.Key key =
                CompressedTileCache.Key.of(opened, z, x, y);

        TileData cached = cache.acquire(key);
        if (cached != null) return cached;

        TileData loaded = delegate.read(opened, z, x, y);
        try {
            return cache.insertAndAcquire(key, loaded);
        } catch (RuntimeException e) {
            loaded.close();
            throw e;
        }
    }
}
