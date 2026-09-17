package gt.lupa.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import gt.lupa.storage.CatalogJson;
import gt.lupa.storage.ImageLevel;
import gt.lupa.storage.ImageManifest;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TilePyramidValidatorTest {
    @TempDir Path temp;

    @Test
    void rejectsMissingExpectedTileBeforePublication() throws Exception {
        Path version = temp.resolve("job/publish/photo/v1");
        Files.createDirectories(version.resolve("tiles/0"));
        writeManifest(version, 120, 80);

        TilePyramidValidator validator = new TilePyramidValidator(2);
        assertThrows(IngestException.class, () -> validator.validate(config(), staging(version, 120, 80)));
    }

    @Test
    void validatesJpegInProcessWithoutExternalVipsPerTile() throws Exception {
        Path version = temp.resolve("job/publish/photo/v1");
        Path level = version.resolve("tiles/0");
        Files.createDirectories(level);
        writeManifest(version, 120, 80);
        writeRgbJpeg(level.resolve("0_0.jpg"), 120, 80);

        // These executables deliberately do not exist. A successful validation proves that
        // per-tile validation no longer shells out to vips/vipsheader.
        IngestCliConfig config = new IngestCliConfig(
                temp.resolve("unused.jpg"),
                "photo",
                "Photo",
                "Own",
                temp.resolve("data"),
                "definitely-missing-vips",
                "definitely-missing-vipsheader",
                Duration.ofSeconds(10),
                85
        );

        PyramidValidationReport report = new TilePyramidValidator(2)
                .validate(config, staging(version, 120, 80));

        assertEquals(1L, report.tileCount());
        assertEquals(Files.size(level.resolve("0_0.jpg")), report.jpegBytes());
    }

    @Test
    void rejectsWrongTileDimensions() throws Exception {
        Path version = temp.resolve("job/publish/photo/v1");
        Path level = version.resolve("tiles/0");
        Files.createDirectories(level);
        writeManifest(version, 120, 80);
        writeRgbJpeg(level.resolve("0_0.jpg"), 119, 80);

        assertThrows(
                IngestException.class,
                () -> new TilePyramidValidator(2).validate(config(), staging(version, 120, 80))
        );
    }

    @Test
    void rejectsCorruptJpegEvenWithJpegSignature() throws Exception {
        Path version = temp.resolve("job/publish/photo/v1");
        Path level = version.resolve("tiles/0");
        Files.createDirectories(level);
        writeManifest(version, 120, 80);
        Files.write(level.resolve("0_0.jpg"), new byte[]{(byte) 0xff, (byte) 0xd8, 0x00, 0x01, 0x02});

        assertThrows(
                IngestException.class,
                () -> new TilePyramidValidator(2).validate(config(), staging(version, 120, 80))
        );
    }

    private IngestCliConfig config() {
        return new IngestCliConfig(
                temp.resolve("unused.jpg"),
                "photo",
                "Photo",
                "Own",
                temp.resolve("data"),
                "vips",
                "vipsheader",
                Duration.ofSeconds(10),
                85
        );
    }

    private StagingResult staging(Path version, int width, int height) {
        return new StagingResult(
                temp.resolve("job"),
                version,
                "photo",
                "v1",
                width,
                height,
                0,
                1
        );
    }

    private static void writeManifest(Path version, int width, int height) throws Exception {
        ImageManifest manifest = new ImageManifest(
                1,
                "photo",
                "v1",
                width,
                height,
                256,
                0,
                "onetile",
                List.of(new ImageLevel(0, width, height))
        );
        Files.createDirectories(version);
        Files.write(version.resolve("manifest.json"), new CatalogJson().writeManifest(manifest));
    }

    private static void writeRgbJpeg(Path path, int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int r = (x * 255) / Math.max(1, width - 1);
                int g = (y * 255) / Math.max(1, height - 1);
                int b = 128;
                image.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        }
        if (!ImageIO.write(image, "jpg", path.toFile())) {
            throw new IllegalStateException("JDK JPEG writer unavailable");
        }
    }
}
