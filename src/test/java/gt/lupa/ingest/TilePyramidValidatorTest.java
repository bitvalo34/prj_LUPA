package gt.lupa.ingest;

import static org.junit.jupiter.api.Assertions.assertThrows;

import gt.lupa.storage.CatalogJson;
import gt.lupa.storage.ImageLevel;
import gt.lupa.storage.ImageManifest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TilePyramidValidatorTest {
    @TempDir Path temp;

    @Test
    void rejectsMissingExpectedTileBeforePublication() throws Exception {
        Path version = temp.resolve("job/publish/photo/v1");
        Files.createDirectories(version.resolve("tiles/0"));
        ImageManifest manifest = new ImageManifest(
                1,
                "photo",
                "v1",
                120,
                80,
                256,
                0,
                "onetile",
                List.of(new ImageLevel(0, 120, 80))
        );
        Files.write(version.resolve("manifest.json"), new CatalogJson().writeManifest(manifest));

        IngestCliConfig config = new IngestCliConfig(
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
        StagingResult staging = new StagingResult(
                temp.resolve("job"),
                version,
                "photo",
                "v1",
                120,
                80,
                0,
                1
        );

        TilePyramidValidator validator = new TilePyramidValidator(
                new ProcessRunner(16 * 1024),
                new VipsImageInspector(new ProcessRunner(16 * 1024))
        );
        assertThrows(IngestException.class, () -> validator.validate(config, staging));
    }
}
