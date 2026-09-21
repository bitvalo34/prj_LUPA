package gt.lupa.storage;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CachingTileReaderTest {
    @Test
    void successfulReadIsSharedThroughVersionedCacheButFailuresAreNotCached()
            throws Exception {
        AtomicInteger reads = new AtomicInteger();

        TileReader delegate = (opened, z, x, y) -> {
            int call = reads.incrementAndGet();
            if (x == 1 && call == 2) {
                throw new TileReadException("synthetic failure");
            }
            return new TileData(new byte[]{1, 2, 3, 4}, 1, 1);
        };

        CompressedTileCache cache = new CompressedTileCache(1024);
        CachingTileReader reader = new CachingTileReader(delegate, cache);
        PublishedImageStore.OpenedImage v1 = opened("v1");
        PublishedImageStore.OpenedImage v2 = opened("v2");

        try (TileData first = reader.read(v1, 0, 0, 0);
             TileData second = reader.read(v1, 0, 0, 0)) {
            assertArrayEquals(first.jpeg(), second.jpeg());
        }
        assertEquals(1, reads.get(), "second read should be a cache hit");

        assertThrows(
                TileReadException.class,
                () -> reader.read(v1, 0, 1, 0));
        try (TileData recovered = reader.read(v1, 0, 1, 0)) {
            assertEquals(4, recovered.jpegLength());
        }
        assertEquals(3, reads.get(), "failed read must not be cached");

        try (TileData otherVersion = reader.read(v2, 0, 0, 0)) {
            assertEquals(4, otherVersion.jpegLength());
        }
        assertEquals(4, reads.get(), "version must be part of cache identity");
    }

    private static PublishedImageStore.OpenedImage opened(String version) {
        ImageManifest manifest = new ImageManifest(
                1,
                "photo",
                version,
                1,
                1,
                256,
                0,
                "onetile",
                List.of(new ImageLevel(0, 1, 1)));
        CatalogImage catalog =
                new CatalogImage("photo", version, 1, 1, 256, 0);
        return new PublishedImageStore.OpenedImage(catalog, manifest);
    }
}
