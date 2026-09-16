package gt.lupa.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CatalogPublisherTest {
    @TempDir Path temp;

    @Test
    void createsCatalogAndReplacesOnlyCurrentVersionForAnImage() throws Exception {
        Path catalog = temp.resolve("catalog.json");
        CatalogPublisher publisher = new CatalogPublisher();
        publisher.publish(catalog, new CatalogImage("photo", "v1", 1000, 800, 256, 2));
        publisher.publish(catalog, new CatalogImage("other", "v1", 640, 480, 256, 2));
        publisher.publish(catalog, new CatalogImage("photo", "v2", 1000, 800, 256, 2));

        CatalogSnapshot snapshot = new CatalogJson().parseCatalog(Files.readAllBytes(catalog));
        assertEquals(2, snapshot.images().size());
        assertEquals("v2", snapshot.images().stream()
                .filter(image -> image.imageId().equals("photo"))
                .findFirst().orElseThrow().imageVersion());
    }

    @Test
    void corruptExistingCatalogIsNeverReplacedWithEmptyCatalog() throws Exception {
        Path catalog = temp.resolve("catalog.json");
        byte[] corrupt = "{ definitely-not-json".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(catalog, corrupt);

        assertThrows(
                CatalogException.class,
                () -> new CatalogPublisher().publish(
                        catalog,
                        new CatalogImage("photo", "v1", 1000, 800, 256, 2)
                )
        );
        assertArrayEquals(corrupt, Files.readAllBytes(catalog));
    }
}
