package gt.lupa.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PublishedTileReaderTest {
    @TempDir Path temp;

    @Test
    void readsDecodesAndChecksRealEdgeDimensions() throws Exception {
        Path dataRoot = temp.resolve("data");
        Path version = dataRoot.resolve("pyramids/photo/v1");
        Files.createDirectories(version.resolve("tiles/0"));
        Files.createDirectories(version.resolve("tiles/1"));

        ImageManifest manifest = new ImageManifest(
                1, "photo", "v1", 300, 257, 256, 0, "onetile",
                List.of(
                        new ImageLevel(0, 150, 129),
                        new ImageLevel(1, 300, 257)));
        Files.write(version.resolve("manifest.json"), new CatalogJson().writeManifest(manifest));
        writeJpeg(version.resolve("tiles/0/0_0.jpg"), 150, 129);
        writeJpeg(version.resolve("tiles/1/1_1.jpg"), 44, 1);

        new CatalogPublisher().publish(
                dataRoot.resolve("catalog.json"),
                new CatalogImage("photo", "v1", 300, 257, 256, 1));

        PublishedImageStore store = new PublishedImageStore(dataRoot);
        PublishedImageStore.OpenedImage opened = store.openCurrent("photo");
        PublishedTileReader reader = new PublishedTileReader(store, 262144);

        TileData thumbnail = reader.read(opened, 0, 0, 0);
        assertEquals(150, thumbnail.width());
        assertEquals(129, thumbnail.height());

        TileData edge = reader.read(opened, 1, 1, 1);
        assertEquals(44, edge.width());
        assertEquals(1, edge.height());
        assertTrue(edge.jpeg().length > 4);
    }

    @Test
    void missingOrMalformedPublishedTileFails() throws Exception {
        Path dataRoot = temp.resolve("data");
        Path version = dataRoot.resolve("pyramids/photo/v1");
        Files.createDirectories(version.resolve("tiles/0"));

        ImageManifest manifest = new ImageManifest(
                1, "photo", "v1", 256, 256, 256, 0, "onetile",
                List.of(new ImageLevel(0, 256, 256)));
        Files.write(version.resolve("manifest.json"), new CatalogJson().writeManifest(manifest));
        new CatalogPublisher().publish(
                dataRoot.resolve("catalog.json"),
                new CatalogImage("photo", "v1", 256, 256, 256, 0));

        PublishedImageStore store = new PublishedImageStore(dataRoot);
        PublishedImageStore.OpenedImage opened = store.openCurrent("photo");
        PublishedTileReader reader = new PublishedTileReader(store, 262144);

        assertThrows(TileReadException.class, () -> reader.read(opened, 0, 0, 0));

        Files.write(version.resolve("tiles/0/0_0.jpg"), new byte[]{1, 2, 3, 4});
        assertThrows(TileReadException.class, () -> reader.read(opened, 0, 0, 0));
    }

    private static void writeJpeg(Path path, int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        assertTrue(ImageIO.write(image, "jpeg", path.toFile()));
    }
}
