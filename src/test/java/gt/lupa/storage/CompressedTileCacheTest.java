package gt.lupa.storage;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class CompressedTileCacheTest {
    @Test
    void lruNeverExceedsResidentBudgetAndKeepsVersionsSeparate() {
        CompressedTileCache cache = new CompressedTileCache(8);

        CompressedTileCache.Key a =
                new CompressedTileCache.Key("photo", "v1", 0, 0, 0);
        CompressedTileCache.Key b =
                new CompressedTileCache.Key("photo", "v1", 0, 1, 0);
        CompressedTileCache.Key c =
                new CompressedTileCache.Key("photo", "v2", 0, 0, 0);

        cache.insertAndAcquire(a, tile(1, 2, 3, 4)).close();
        cache.insertAndAcquire(b, tile(5, 6, 7, 8)).close();

        try (TileData ignored = cache.acquire(a)) {
            assertNotNull(ignored);
        }

        cache.insertAndAcquire(c, tile(9, 10, 11, 12)).close();

        assertNull(cache.acquire(b), "least-recently-used entry should be evicted");
        try (TileData v1 = cache.acquire(a);
             TileData v2 = cache.acquire(c)) {
            assertNotNull(v1);
            assertNotNull(v2);
            assertArrayEquals(new byte[]{1, 2, 3, 4}, v1.jpeg());
            assertArrayEquals(new byte[]{9, 10, 11, 12}, v2.jpeg());
        }

        CompressedTileCache.Snapshot snapshot = cache.snapshot();
        assertTrue(snapshot.residentBytes() <= snapshot.maxBytes());
        assertEquals(8, snapshot.residentBytes());
        assertEquals(2, snapshot.entries());
        assertTrue(snapshot.evictions() >= 1);
    }

    @Test
    void evictionAccountsBytesStillReferencedByAConsumer() {
        CompressedTileCache cache = new CompressedTileCache(4);
        CompressedTileCache.Key a =
                new CompressedTileCache.Key("photo", "v1", 0, 0, 0);
        CompressedTileCache.Key b =
                new CompressedTileCache.Key("photo", "v1", 0, 1, 0);

        TileData held = cache.insertAndAcquire(a, tile(1, 2, 3, 4));
        cache.insertAndAcquire(b, tile(5, 6, 7, 8)).close();

        assertEquals(4, cache.snapshot().residentBytes());
        assertEquals(4, cache.snapshot().retainedEvictedBytes());

        held.close();
        assertEquals(0, cache.snapshot().retainedEvictedBytes());
    }

    @Test
    void concurrentHandlesDoNotShareMutableStateOrContent() throws Exception {
        CompressedTileCache cache = new CompressedTileCache(16);
        CompressedTileCache.Key key =
                new CompressedTileCache.Key("photo", "v1", 0, 0, 0);
        cache.insertAndAcquire(key, tile(1, 2, 3, 4)).close();

        try (var executor = Executors.newFixedThreadPool(8)) {
            List<Callable<Void>> work = new ArrayList<>();
            for (int i = 0; i < 64; i++) {
                work.add(() -> {
                    try (TileData tile = cache.acquire(key)) {
                        assertNotNull(tile);
                        byte[] copy = tile.jpeg();
                        copy[0] = 99;
                        assertArrayEquals(new byte[]{1, 2, 3, 4}, tile.jpeg());
                    }
                    return null;
                });
            }
            for (var future : executor.invokeAll(work)) {
                future.get();
            }
        }

        assertEquals(0, cache.snapshot().activeLeases());
        assertEquals(4, cache.snapshot().residentBytes());
        assertEquals(0, cache.snapshot().retainedEvictedBytes());
    }

    private static TileData tile(int... bytes) {
        byte[] jpeg = new byte[bytes.length];
        for (int i = 0; i < bytes.length; i++) jpeg[i] = (byte) bytes[i];
        return new TileData(jpeg, 1, 1);
    }
}
