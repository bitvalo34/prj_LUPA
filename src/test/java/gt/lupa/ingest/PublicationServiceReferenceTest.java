package gt.lupa.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import gt.lupa.storage.CatalogPublisher;
import gt.lupa.storage.ImageLevel;
import gt.lupa.storage.ImageManifest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PublicationServiceReferenceTest {
    @TempDir Path temp;

    @Test
    void referencePolicyPublishesMetadataWithoutDuplicatingOriginal() throws Exception {
        Path original = temp.resolve("huge-source.tif");
        Files.write(original, new byte[]{1, 2, 3, 4, 5});
        Path dataRoot = temp.resolve("data");
        StorageLayout layout = new StorageLayout(dataRoot);
        layout.initialize();

        Path job = layout.createJobDirectory();
        Path stagedVersion = job.resolve("publish/photo/v1");
        Files.createDirectories(stagedVersion.resolve("tiles/0"));
        Files.writeString(stagedVersion.resolve("manifest.json"), "{}\n");

        IngestCliConfig config = new IngestCliConfig(
                original,
                "photo",
                "Photo",
                "Private permission",
                dataRoot,
                "vips",
                "vipsheader",
                Duration.ofSeconds(10),
                85,
                OriginalPolicy.REFERENCE
        );
        ImageInspection inspection = new ImageInspection(
                original,
                original.toString(),
                ActualFormat.TIFF,
                "tiffload",
                100,
                80,
                3,
                "srgb",
                false,
                1
        );
        PreflightReport preflight = new PreflightReport(
                original,
                Files.size(original),
                "tif",
                dataRoot,
                Files.getFileStore(dataRoot).getUsableSpace(),
                "vips-8.15.1",
                false
        );
        ImageManifest manifest = new ImageManifest(
                1,
                "photo",
                "v1",
                100,
                80,
                256,
                0,
                "onetile",
                List.of(new ImageLevel(0, 100, 80))
        );
        StagingResult staging = new StagingResult(job, stagedVersion, "photo", "v1", 100, 80, 0, 1);
        PyramidValidationReport validation = new PyramidValidationReport(manifest, 1, 1234);

        PublicationResult result = new PublicationService(new CatalogPublisher())
                .publish(config, inspection, preflight, staging, validation);

        Path privateVersion = dataRoot.resolve("originals/photo/v1");
        assertEquals(original.toAbsolutePath().normalize(), result.privateOriginalPath());
        assertEquals(OriginalPolicy.REFERENCE, result.originalPolicy());
        assertFalse(Files.exists(privateVersion.resolve("source.tiff")));
        assertTrue(Files.exists(privateVersion.resolve("import-metadata.json")));
        assertTrue(Files.exists(dataRoot.resolve("pyramids/photo/v1/manifest.json")));
        assertTrue(Files.exists(dataRoot.resolve("catalog.json")));

        JsonNode metadata = new ObjectMapper().readTree(privateVersion.resolve("import-metadata.json").toFile());
        assertEquals("reference", metadata.path("originalPolicy").asText());
        assertEquals(original.toAbsolutePath().normalize().toString(), metadata.path("privateOriginalPath").asText());
    }
}
