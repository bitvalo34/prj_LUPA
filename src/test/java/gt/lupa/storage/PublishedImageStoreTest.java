package gt.lupa.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PublishedImageStoreTest {
    @TempDir Path temp;

    @Test
    void opensCatalogVersionReadsManifestAndResolvesLevelZeroAndEdgeTile() throws Exception {
        Path dataRoot = temp.resolve("data");
        Path version = dataRoot.resolve("pyramids/photo/v1");
        Files.createDirectories(version.resolve("tiles/0"));
        Files.createDirectories(version.resolve("tiles/1"));

        ImageManifest manifest = new ImageManifest(
                1,
                "photo",
                "v1",
                300,
                257,
                256,
                0,
                "onetile",
                List.of(
                        new ImageLevel(0, 150, 129),
                        new ImageLevel(1, 300, 257)
                )
        );
        Files.write(version.resolve("manifest.json"), new CatalogJson().writeManifest(manifest));
        Files.write(version.resolve("tiles/0/0_0.jpg"), new byte[]{1});
        Files.write(version.resolve("tiles/1/0_0.jpg"), new byte[]{1});
        Files.write(version.resolve("tiles/1/1_0.jpg"), new byte[]{1});
        Files.write(version.resolve("tiles/1/0_1.jpg"), new byte[]{1});
        Files.write(version.resolve("tiles/1/1_1.jpg"), new byte[]{1});

        new CatalogPublisher().publish(
                dataRoot.resolve("catalog.json"),
                new CatalogImage("photo", "v1", 300, 257, 256, 1)
        );

        PublishedImageStore store = new PublishedImageStore(dataRoot);
        PublishedImageStore.OpenedImage opened = store.openCurrent("photo");
        assertEquals("v1", opened.catalogImage().imageVersion());
        assertEquals(2, opened.manifest().levels().size());
        assertTrue(store.resolveTile(opened, 0, 0, 0).endsWith("tiles/0/0_0.jpg"));
        assertTrue(store.resolveTile(opened, 1, 1, 1).endsWith("tiles/1/1_1.jpg"));
        assertThrows(CatalogException.class, () -> store.resolveTile(opened, 1, 2, 0));
    }

    @Test
    void incompleteStagingAndUnreferencedPyramidAreNotPublishedImages() throws Exception {
        Path dataRoot = temp.resolve("data");
        Files.createDirectories(dataRoot.resolve("staging/job-incomplete/publish/ghost/v1"));
        Files.createDirectories(dataRoot.resolve("pyramids/orphan/v1"));
        Files.createDirectories(dataRoot);
        Files.write(
                dataRoot.resolve("catalog.json"),
                new CatalogJson().writeCatalog(new CatalogSnapshot(1, List.of()))
        );

        PublishedImageStore store = new PublishedImageStore(dataRoot);
        assertThrows(CatalogException.class, () -> store.openCurrent("ghost"));
        assertThrows(CatalogException.class, () -> store.openCurrent("orphan"));
    }
}
